#!/usr/bin/env python3
"""Build a small source-backed OSM city/metro gazetteer with one indexed-free PBF pass."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
from importlib.metadata import version
import json
import math
import os
from pathlib import Path
import re
import resource
import shutil
import sys
import tempfile
import time
from urllib.parse import urlsplit

GENERATOR_VERSION = "1.0.0"
SCHEMA_VERSION = 1
GIB = 1024 ** 3
MAX_RECORDS = 20_000
MAX_CANDIDATES = 50_000
MAX_OUTPUT_BYTES = 16 * 1024 ** 2
MAX_ALIASES_CHARACTERS = 12 * 2048
MAX_SOURCE_TAGS = 100
NAME_KEYS = ("name", "name:ru", "alt_name", "alt_name:ru", "official_name", "official_name:ru",
             "short_name", "short_name:ru", "loc_name", "loc_name:ru", "name:en", "int_name")
CONTEXT_KEYS = ("addr:city", "addr:region:ru", "addr:region", "addr:state", "addr:country",
                "addr:district", "is_in", "network:ru", "network", "operator", "colour", "ref")
LIFECYCLES = ("disused", "abandoned", "construction", "proposed")
LIFECYCLE_KEYS = LIFECYCLES + tuple(f"{prefix}:{kind}" for prefix in LIFECYCLES
                                    for kind in ("place", "railway", "public_transport", "station"))
CHECK_KEYS = ("place", "railway", "station", "public_transport", "subway") + LIFECYCLE_KEYS
SOURCE_KEYS = frozenset(NAME_KEYS + CONTEXT_KEYS + CHECK_KEYS)
COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
LICENSE_URL = "https://opendatacommons.org/licenses/odbl/1-0/"


class GazetteerError(Exception):
    pass


def text_tag(tags, key, limit=300):
    value = tags.get(key, "")
    if not isinstance(value, str):
        return ""
    value = value.strip()
    if len(value) > limit or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
        return ""
    return value


def lifecycle_conflicts(tags):
    # Check raw values: an overlong/malformed positive lifecycle tag must not disappear
    # through the display-string bounds and accidentally turn an inactive place active.
    return [key for key in LIFECYCLE_KEYS if key in tags and
            (not isinstance(tags[key], str) or tags[key].strip().lower() not in ("", "no", "false", "0"))]


def classify(tags):
    city = tags.get("place") in ("city", "town")
    station = tags.get("railway") == "station" and tags.get("station") == "subway"
    if city and station:
        return None, "conflicting_object_kind"
    if not city and not station:
        return None, "not_city_or_subway_station"
    if lifecycle_conflicts(tags):
        return None, "lifecycle_conflict"
    return "cities" if city else "stations", None


def make_entry(object_id, latitude, longitude, tags):
    category, rejected = classify(tags)
    if rejected:
        return None, None, rejected
    if isinstance(object_id, bool) or not isinstance(object_id, int) or not 0 < object_id <= 9223372036854775807:
        return None, None, "invalid_id"
    if any(isinstance(n, bool) or not isinstance(n, (int, float)) or not math.isfinite(n)
           for n in (latitude, longitude)) or not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
        return None, None, "invalid_coordinate"
    name = text_tag(tags, "name:ru") or text_tag(tags, "name")
    if not name:
        return None, None, "missing_valid_name"
    aliases = []
    seen = {name}
    for key in NAME_KEYS:
        for value in text_tag(tags, key, 2048).split(";"):
            value = value.strip()
            if value and len(value) <= 300 and value not in seen:
                aliases.append(value)
                seen.add(value)
    if sum(map(len, aliases)) > MAX_ALIASES_CHARACTERS:
        return None, None, "aliases_limit"
    context = list(dict.fromkeys(filter(None, (text_tag(tags, "addr:district"), text_tag(tags, "is_in")))))
    context_label = " · ".join(context)
    if len(context_label) > 600:
        return None, None, "context_limit"
    source_tags = {key: text_tag(tags, key, 2048) for key in sorted(SOURCE_KEYS)
                   if text_tag(tags, key, 2048)}
    if len(source_tags) > MAX_SOURCE_TAGS:
        return None, None, "source_tags_limit"
    row = {
        "id": f"osm:node:{object_id}", "name": name, "aliases": aliases,
        "center": {"latitude": latitude, "longitude": longitude},
        "region": text_tag(tags, "addr:region:ru") or text_tag(tags, "addr:region") or text_tag(tags, "addr:state"),
        "country": text_tag(tags, "addr:country"), "city": text_tag(tags, "addr:city"),
        "network": text_tag(tags, "network:ru") or text_tag(tags, "network"),
        "contextLabel": context_label,
        "sourceUrl": f"https://www.openstreetmap.org/node/{object_id}", "sourceTags": source_tags,
    }
    return category, row, None


def normalized_timestamp(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None or parsed < datetime(1970, 1, 1, tzinfo=timezone.utc):
            raise ValueError("timezone or range")
        return parsed.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    except (AttributeError, TypeError, ValueError) as failure:
        raise GazetteerError("PBF needs a valid source timestamp with timezone") from failure


def source_metadata(url, sha256, size, timestamp):
    uri = urlsplit(url)
    if (uri.scheme != "https" or uri.netloc != "download.geofabrik.de" or uri.query or uri.fragment or
            not re.fullmatch(r"/russia(?:-[0-9]{6}|-latest)?\.osm\.pbf", uri.path)):
        raise GazetteerError("source URL must identify a public Geofabrik Russia PBF")
    if not re.fullmatch(r"[a-f0-9]{64}", sha256) or isinstance(size, bool) or not isinstance(size, int) or size <= 0:
        raise GazetteerError("source SHA-256 and size are required")
    return {"url": url, "sha256": sha256, "sizeBytes": size,
            "dumpTimestamp": normalized_timestamp(timestamp), "license": "ODbL-1.0",
            "attribution": "© OpenStreetMap contributors", "copyrightUrl": COPYRIGHT_URL,
            "licenseUrl": LICENSE_URL}


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 ** 2), b""):
            digest.update(block)
    return digest.hexdigest()


def check_space(directory, minimum, pending_bytes=0):
    if shutil.disk_usage(directory).free - pending_bytes < minimum:
        raise GazetteerError("required free disk reserve would not remain")


def stat_identity(path):
    stat = path.stat()
    return stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns


def atomic_write(path, content, minimum_free):
    path.parent.mkdir(parents=True, exist_ok=True)
    check_space(path.parent, minimum_free, len(content))
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class CatalogBuilder:
    def __init__(self, maximum_records=MAX_RECORDS):
        if not 1 <= maximum_records <= MAX_RECORDS:
            raise GazetteerError("record limit out of range")
        self.maximum_records = maximum_records
        self.cities = []
        self.stations = []
        self.ids = set()
        self.observed_ids = set()
        self.rejected = Counter()

    def add(self, object_id, latitude, longitude, tags):
        if isinstance(object_id, int) and not isinstance(object_id, bool) and 0 < object_id <= 9223372036854775807:
            # A history/repeated node cannot become a single current place just because one
            # revision was rejected. Keep all observed candidate identities, bounded upstream.
            if object_id in self.observed_ids:
                raise GazetteerError(f"duplicate node identity: osm:node:{object_id}")
            if len(self.observed_ids) >= MAX_CANDIDATES:
                raise GazetteerError("candidate identity limit exceeded")
            self.observed_ids.add(object_id)
        category, row, reason = make_entry(object_id, latitude, longitude, tags)
        if row is None:
            self.rejected[reason] += 1
            return
        if row["id"] in self.ids:
            raise GazetteerError(f"duplicate node identity: {row['id']}")
        if len(self.ids) >= self.maximum_records:
            raise GazetteerError("record limit exceeded")
        self.ids.add(row["id"])
        getattr(self, category).append(row)

    def encode(self, source, osmium_version):
        if not self.cities or not self.stations:
            raise GazetteerError("both source collections must be nonempty")
        output = {"schemaVersion": SCHEMA_VERSION,
                  "metadata": {"source": source, "generatorVersion": GENERATOR_VERSION,
                               "osmiumVersion": osmium_version,
                               "cityCount": len(self.cities), "stationCount": len(self.stations),
                               "scope": "Explicit city/town nodes and active subway station nodes; no administrative boundary inference"},
                  "cities": sorted(self.cities, key=lambda row: row["id"]),
                  "stations": sorted(self.stations, key=lambda row: row["id"])}
        encoded = (json.dumps(output, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
        if len(encoded) > MAX_OUTPUT_BYTES:
            raise GazetteerError("output byte limit exceeded")
        return encoded


def build(args):
    import osmium  # Pure record-contract tests do not require the PBF reader.
    source_path = args.input.resolve()
    output_path = args.output.resolve()
    report_path = args.report.resolve()
    if source_path in (output_path, report_path) or output_path == report_path:
        raise GazetteerError("input, asset and report must use separate paths")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    minimum = args.minimum_free_gib * GIB
    check_space(output_path.parent, minimum)
    check_space(report_path.parent, minimum)
    original_stat = stat_identity(source_path)
    started = time.monotonic()
    digest = file_sha256(source_path)
    if digest != args.sha256:
        raise GazetteerError("input SHA-256 differs from the explicitly expected snapshot")
    if original_stat != stat_identity(source_path):
        raise GazetteerError("input changed while its hash was checked")
    builder = CatalogBuilder(args.max_records)
    counts = Counter()
    pool = osmium.io.ThreadPool(2)
    processor = osmium.FileProcessor(source_path, thread_pool=pool).with_filter(osmium.filter.TagFilter(
        ("place", "city"), ("place", "town"), ("station", "subway")))
    source = source_metadata(args.source_url, digest, original_stat[2], processor.header.get("osmosis_replication_timestamp"))
    next_space_check = time.monotonic() + 20
    for obj in processor:
        counts["candidateObjects"] += 1
        if counts["candidateObjects"] > MAX_CANDIDATES:
            raise GazetteerError("candidate limit exceeded")
        if time.monotonic() >= next_space_check:
            check_space(output_path.parent, minimum)
            next_space_check = time.monotonic() + 20
        if not obj.is_node():
            counts["skippedWays" if obj.is_way() else "skippedRelations"] += 1
            continue
        counts["candidateNodes"] += 1
        valid = obj.location.valid()
        builder.add(obj.id, obj.location.lat if valid else None, obj.location.lon if valid else None, dict(obj.tags))
    if original_stat != stat_identity(source_path):
        raise GazetteerError("input changed during PBF reading")
    encoded = builder.encode(source, version("osmium"))
    atomic_write(output_path, encoded, minimum)
    report = {"source": source, "generatorVersion": GENERATOR_VERSION, "osmiumVersion": version("osmium"),
              "cityCount": len(builder.cities), "stationCount": len(builder.stations),
              "counts": dict(sorted(counts.items())), "rejected": dict(sorted(builder.rejected.items())),
              "assetSha256": hashlib.sha256(encoded).hexdigest(), "assetBytes": len(encoded),
              "elapsedSeconds": round(time.monotonic() - started, 3),
              "peakRssMiB": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss /
                                   (1024 ** 2 if sys.platform == "darwin" else 1024), 2),
              "pbfObjectPasses": 1, "separateSha256Read": True, "nodeGeometryIndex": False,
              "freeBytesAfter": shutil.disk_usage(output_path.parent).free}
    report_bytes = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode()
    atomic_write(report_path, report_bytes, minimum)
    print(json.dumps(report, ensure_ascii=False, indent=2))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--source-url", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--minimum-free-gib", type=int, default=4)
    parser.add_argument("--max-records", type=int, default=MAX_RECORDS)
    args = parser.parse_args(argv)
    if args.minimum_free_gib < 4:
        parser.error("minimum free disk reserve cannot be below 4 GiB")
    try:
        build(args)
    except (GazetteerError, OSError, ValueError, RuntimeError) as error:
        print(f"Gazetteer import failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
