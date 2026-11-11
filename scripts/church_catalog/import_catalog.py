#!/usr/bin/env python3
"""Build a reproducible, reference-complete church catalogue without a country node index."""

import argparse
from collections import Counter
from datetime import datetime, timezone
from encodings.idna import nameprep
import hashlib
from importlib.metadata import version
import json
import math
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import tempfile
import unicodedata
from urllib.parse import urlsplit

import osmium


DENOMINATIONS = frozenset(("orthodox", "russian_orthodox", "greek_orthodox",
                           "serbian_orthodox", "georgian_orthodox"))
TYPES = {"n": "node", "w": "way", "r": "relation"}
AMENITIES = frozenset(("place_of_worship", "monastery"))
SOURCE_KEYS = frozenset(("name", "name:ru", "alt_name", "alt_name:ru", "official_name",
                         "official_name:ru", "short_name", "short_name:ru", "amenity",
                         "religion", "denomination", "building", "place_of_worship",
                         "tower:type", "website", "phone", "email"))
GENERATOR_VERSION = "1.0.0"
GIB = 1024 ** 3


class CatalogError(Exception):
    pass


class GeometryError(CatalogError):
    def __init__(self, reason):
        self.reason = reason
        super().__init__(reason)


def text_tag(tags, key, limit=512):
    value = tags.get(key, "").strip()
    if len(value) > limit or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
        return ""
    return value


def safe_website(value):
    """A display URL comes only from a source tag, never inferred from the church name."""
    if not value or len(value) > 2048 or any(c.isspace() or ord(c) < 32 or
                                            127 <= ord(c) <= 159 or unicodedata.category(c) == "Cf" for c in value):
        return None
    try:
        uri = urlsplit(value)
        # Match ScheduleUrl.publicSource: retain an explicitly published protocol.
        if (uri.scheme not in ("http", "https") or not uri.hostname or uri.username is not None or
                uri.password is not None or "\\" in value or "%" in uri.netloc):
            return None
        if uri.port not in (None, 80 if uri.scheme == "http" else 443):
            return None
        if not re.fullmatch(r"[^:]+(?::[0-9]{1,5})?", uri.netloc):
            return None
        # Python's built-in IDNA codec and java.net.IDN both use IDNA 2003. Canonicalize
        # an explicitly published Cyrillic host, without inferring a protocol or domain.
        unicode_host = uri.hostname.translate({0x3002: ".", 0xFF0E: ".", 0xFF61: "."}).removesuffix(".")
        # The built-in codec does not apply STD3's edge-hyphen rule to Unicode labels.
        # Check its nameprep result before punycode to match Java USE_STD3_ASCII_RULES.
        if any(not label or nameprep(label).startswith("-") or nameprep(label).endswith("-")
               for label in unicode_host.split(".")):
            return None
        hostname = unicode_host.encode("idna").decode("ascii").lower()
        if ("." not in hostname or hostname.endswith((".local", ".localhost")) or
                not re.fullmatch(r"[a-z0-9.-]+", hostname) or re.fullmatch(r"[0-9.]+", hostname)):
            return None
        if any(not label or len(label) > 63 or label.startswith("-") or label.endswith("-")
               for label in hostname.split(".")) or len(hostname) > 253:
            return None
        if any(c in value for c in '<>"{}|^`') or re.search(r"%(?![0-9a-fA-F]{2})", value):
            return None
        # Keep the raw path/query escapes and the explicitly published standard port.
        port = f":{uri.port}" if uri.port is not None else ""
        return uri._replace(netloc=hostname + port).geturl()
    except (ValueError, UnicodeError):
        return None


def candidate(tags, include_unconfirmed=False):
    """Same explicit Orthodox denominations as DiscoveryJson; no inference from names."""
    if text_tag(tags, "amenity") not in AMENITIES:
        return None, "other_amenity"
    if text_tag(tags, "religion") != "christian":
        return None, "non_christian_or_missing_religion"
    if text_tag(tags, "tower:type") == "bell_tower":
        return None, "bell_tower"
    denomination = text_tag(tags, "denomination")
    orthodox = bool(set(x.strip() for x in denomination.split(";")) & DENOMINATIONS)
    # A malformed nonempty tag must not become an unknown denomination.
    unknown = not tags.get("denomination", "").strip()
    if not orthodox and not (include_unconfirmed and unknown):
        return None, "other_or_unconfirmed_denomination"
    name = text_tag(tags, "name:ru", 300) or text_tag(tags, "name", 300)
    if not name:
        return None, "missing_valid_name"
    chapel = orthodox and (text_tag(tags, "building") == "chapel" or
                          text_tag(tags, "place_of_worship") == "chapel" or
                          name.lower().startswith("часовня"))
    kind = ("WORSHIP_PLACE" if not orthodox else "MONASTERY" if
            text_tag(tags, "amenity") == "monastery" else "CHAPEL" if chapel else "CHURCH")
    aliases = []
    for key in ("name", "name:ru", "alt_name", "alt_name:ru", "official_name",
                "official_name:ru", "short_name", "short_name:ru"):
        for alias in text_tag(tags, key, 2048).split(";"):
            alias = alias.strip()
            if alias and len(alias) <= 300 and alias != name and alias not in aliases:
                aliases.append(alias)
    city = text_tag(tags, "addr:city", 200)
    street = ", ".join(filter(None, (text_tag(tags, "addr:street", 200),
                                     text_tag(tags, "addr:housenumber", 30))))
    address = text_tag(tags, "addr:full", 512) or ", ".join(filter(None, (city, street)))
    website = next((url for key in ("website", "contact:website")
                    if (url := safe_website(text_tag(tags, key, 2048)))), None)
    source_tags = {key: text_tag(tags, key, 2048) for key in sorted(tags)
                   if key in SOURCE_KEYS or key.startswith(("addr:", "contact:"))}
    source_tags = {key: value for key, value in source_tags.items() if value}
    return {"name": name, "address": address, "city": city,
            "region": text_tag(tags, "addr:region", 200) or text_tag(tags, "addr:state", 200),
            "country": text_tag(tags, "addr:country", 200), "website": website,
            "isChapel": chapel, "kind": kind, "aliases": aliases,
            "sourceTags": source_tags}, None


def valid_id(object_id):
    return isinstance(object_id, int) and 0 < object_id <= 9223372036854775807


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def normalized_timestamp(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("missing timezone")
        return parsed.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    except ValueError as error:
        raise CatalogError("dump timestamp must be an ISO 8601 timestamp with timezone") from error


def atomic_json(path, data, compact=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(data, stream, ensure_ascii=False, allow_nan=False,
                      separators=(",", ":") if compact else None, indent=None if compact else 2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def free_space(path, minimum):
    if shutil.disk_usage(path).free < minimum:
        raise CatalogError("less than the required free disk reserve remains")


def merge_boxes(boxes):
    if not boxes:
        raise GeometryError("empty_geometry")
    return (min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes))


class Geometry:
    def __init__(self, nodes, ways, relations, invalid_nodes):
        self.nodes, self.ways, self.relations = nodes, ways, relations
        self.invalid_nodes = invalid_nodes
        self.cache = {}
        self.heights = {}

    def bbox(self, kind, object_id, active=None):
        key = (kind, object_id)
        active = set() if active is None else active
        if key in self.cache:
            result = self.cache[key]
            if isinstance(result, GeometryError):
                raise result
            if len(active) + self.heights[key] > 128:
                raise GeometryError("relation_depth_limit")
            return result
        if not valid_id(object_id):
            raise GeometryError("invalid_reference_id")
        if key in active:
            raise GeometryError("cyclic_relation")
        if len(active) >= 128:
            raise GeometryError("relation_depth_limit")
        active.add(key)
        try:
            if kind == "n":
                if object_id in self.invalid_nodes:
                    raise GeometryError("invalid_node_location")
                if object_id not in self.nodes:
                    raise GeometryError("missing_node")
                lat, lon = self.nodes[object_id]
                result = (lat, lon, lat, lon)
                height = 1
            elif kind == "w":
                if object_id not in self.ways:
                    raise GeometryError("missing_way")
                refs = self.ways[object_id]
                if len(refs) < 2:
                    raise GeometryError("short_way")
                result = merge_boxes([self.bbox("n", ref, active) for ref in refs])
                height = 1 + max(self.heights[("n", ref)] for ref in refs)
            elif kind == "r":
                if object_id not in self.relations:
                    raise GeometryError("missing_relation")
                result = merge_boxes([self.bbox(t, ref, active) for t, ref in self.relations[object_id]])
                height = 1 + max(self.heights[(t, ref)] for t, ref in self.relations[object_id])
            else:
                raise GeometryError("invalid_member_type")
            # A literal min/max average would move an antimeridian site to longitude 0.
            # Reject this uncommon shape instead of inventing a centre in another hemisphere.
            if result[3] - result[1] > 180:
                raise GeometryError("antimeridian_geometry")
            self.heights[key] = height
            self.cache[key] = result
            return result
        except GeometryError as error:
            # Depth exhaustion depends on the caller's path, not on this subtree alone.
            if error.reason != "relation_depth_limit":
                self.cache[key] = error
            raise
        finally:
            active.remove(key)


def build_catalog(input_path, output_path, report_path, source_url,
                  dump_timestamp=None, expected_sha256=None, include_unconfirmed=False,
                  scratch_dir=None, min_free_bytes=4 * GIB, max_scratch_bytes=2 * GIB,
                  max_geometry_references=5_000_000):
    input_path, output_path, report_path = map(Path, (input_path, output_path, report_path))
    if len({p.resolve() for p in (input_path, output_path, report_path)}) != 3:
        raise CatalogError("input, output and report paths must be different")
    source_uri = urlsplit(source_url)
    if source_uri.scheme != "https" or not source_uri.hostname or source_uri.username is not None:
        raise CatalogError("source URL must identify the HTTPS source download")
    report = {"status": "failed", "generatorVersion": GENERATOR_VERSION,
              "counts": {}, "excludedByReason": {}, "rejectedByReason": {}, "rejectionExamples": []}
    try:
        initial = input_path.stat()
        fingerprint = (initial.st_size, initial.st_mtime_ns, initial.st_ino)
        digest = file_sha256(input_path)
        report["sourceSha256"] = digest
        if expected_sha256 and digest != expected_sha256.lower():
            raise CatalogError("source SHA-256 does not match the expected value")
        processor = osmium.FileProcessor(input_path)
        if processor.header.has_multiple_object_versions:
            raise CatalogError("history/change files are unsupported; use a current snapshot")
        header_timestamp = processor.header.get("osmosis_replication_timestamp")
        if header_timestamp:
            timestamp = normalized_timestamp(header_timestamp)
            origin = "osm_header"
            if dump_timestamp and normalized_timestamp(dump_timestamp) != timestamp:
                raise CatalogError("explicit dump timestamp disagrees with the OSM header")
        elif dump_timestamp:
            timestamp, origin = normalized_timestamp(dump_timestamp), "explicit_argument"
        else:
            raise CatalogError("missing dump timestamp in OSM header; provide --dump-timestamp from source evidence")
        source = {"name": "OpenStreetMap via Geofabrik", "url": source_url,
                  "fileName": input_path.name, "sizeBytes": initial.st_size, "sha256": digest,
                  "dumpTimestamp": timestamp, "timestampOrigin": origin,
                  "license": "ODbL-1.0", "licenseUrl": "https://opendatacommons.org/licenses/odbl/1-0/",
                  "attribution": "© OpenStreetMap contributors",
                  "copyrightUrl": "https://www.openstreetmap.org/copyright"}
        report["source"] = source
        scratch_parent = Path(scratch_dir) if scratch_dir else output_path.parent
        scratch_parent.mkdir(parents=True, exist_ok=True)
        free_space(scratch_parent, min_free_bytes)
        candidates, excluded = {}, Counter()
        with tempfile.TemporaryDirectory(prefix="church-catalog-", dir=scratch_parent) as temporary:
            database_path = Path(temporary) / "relations.sqlite"
            database = sqlite3.connect(database_path)
            try:
                database.execute("PRAGMA journal_mode=OFF")
                database.execute("PRAGMA synchronous=OFF")
                database.execute("PRAGMA cache_size=-16384")
                page_size = database.execute("PRAGMA page_size").fetchone()[0]
                database.execute("PRAGMA max_page_count=" + str(max(1, max_scratch_bytes // page_size)))
                database.execute("CREATE TABLE relations (id INTEGER PRIMARY KEY, members TEXT NOT NULL)")
                relation_count = 0
                print("Pass 1/3: candidates and relation references", file=sys.stderr, flush=True)
                processor.with_filter(osmium.filter.TagFilter(("amenity", "place_of_worship"),
                    ("amenity", "monastery")).enable_for(osmium.osm.NODE | osmium.osm.WAY))
                for obj in processor:
                    kind = obj.type_str()
                    if kind == "r" and not obj.deleted:
                        members = [(member.type, member.ref) for member in obj.members]
                        try:
                            database.execute("INSERT INTO relations VALUES (?, ?)",
                                             (obj.id, json.dumps(members, separators=(",", ":"))))
                        except sqlite3.IntegrityError as error:
                            raise CatalogError("duplicate relation ID; input is not a snapshot") from error
                        relation_count += 1
                        if relation_count % 10_000 == 0:
                            free_space(scratch_parent, min_free_bytes)
                    tags = dict(obj.tags)
                    if tags.get("amenity") not in AMENITIES:
                        continue
                    record, reason = candidate(tags, include_unconfirmed)
                    if obj.deleted or not valid_id(obj.id):
                        record, reason = None, "deleted_or_invalid_id"
                    if record is None:
                        excluded[reason] += 1
                        continue
                    key = (kind, obj.id)
                    if key in candidates:
                        raise CatalogError("duplicate eligible object ID; input is not a snapshot")
                    candidates[key] = record
                database.commit()
                report["counts"]["relationReferencesStored"] = relation_count
                report["counts"]["scratchBytes"] = database_path.stat().st_size
                report["excludedByReason"] = dict(sorted(excluded.items()))
                report["counts"]["eligible"] = len(candidates)
                if not candidates:
                    raise CatalogError("no eligible churches found; output was not replaced")
                relations, needed_ways, needed_nodes = {}, set(), set()
                queue = [object_id for kind, object_id in candidates if kind == "r"]
                needed_ways.update(object_id for kind, object_id in candidates if kind == "w")
                needed_nodes.update(object_id for kind, object_id in candidates if kind == "n")
                visited, reference_count = set(), 0
                while queue:
                    object_id = queue.pop()
                    if object_id in visited:
                        continue
                    visited.add(object_id)
                    row = database.execute("SELECT members FROM relations WHERE id=?", (object_id,)).fetchone()
                    if row is None:
                        continue  # Geometry validates missing references and rejects their owner later.
                    members = json.loads(row[0])
                    relations[object_id] = members
                    reference_count += len(members)
                    if reference_count > max_geometry_references:
                        raise CatalogError("selected geometry exceeds reference budget")
                    for kind, ref in members:
                        if kind == "r":
                            queue.append(ref)
                        elif kind == "w":
                            needed_ways.add(ref)
                        elif kind == "n":
                            needed_nodes.add(ref)
            finally:
                database.close()
            # The complete relation graph is no longer needed and is removed before pass 2.
            database_path.unlink()
            free_space(scratch_parent, min_free_bytes)
            print("Pass 2/3: selected ways", file=sys.stderr, flush=True)
            ways = {}
            for obj in osmium.FileProcessor(input_path, osmium.osm.WAY).with_filter(osmium.filter.IdFilter(needed_ways)):
                if obj.id in ways:
                    raise CatalogError("duplicate referenced way ID; input is not a snapshot")
                if obj.deleted:
                    continue
                refs = [node.ref for node in obj.nodes]
                ways[obj.id] = refs
                needed_nodes.update(refs)
                reference_count += len(refs)
                if reference_count > max_geometry_references:
                    raise CatalogError("selected geometry exceeds reference budget")
            free_space(scratch_parent, min_free_bytes)
            print("Pass 3/3: selected node coordinates", file=sys.stderr, flush=True)
            nodes, invalid_nodes = {}, set()
            for obj in osmium.FileProcessor(input_path, osmium.osm.NODE).with_filter(osmium.filter.IdFilter(needed_nodes)):
                if obj.id in nodes or obj.id in invalid_nodes:
                    raise CatalogError("duplicate referenced node ID; input is not a snapshot")
                try:
                    lat, lon = obj.location.lat, obj.location.lon
                    valid = not obj.deleted and math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180
                except (ValueError, osmium.InvalidLocationError):
                    valid = False
                if valid:
                    nodes[obj.id] = (lat, lon)
                else:
                    invalid_nodes.add(obj.id)
            report["counts"].update({"selectedRelations": len(relations), "requestedWays": len(needed_ways),
                "foundWays": len(ways), "requestedNodes": len(needed_nodes), "foundNodes": len(nodes),
                "invalidNodes": len(invalid_nodes), "selectedReferences": reference_count})
            geometry = Geometry(nodes, ways, relations, invalid_nodes)
            churches, rejected = [], Counter()
            for (kind, object_id), record in sorted(candidates.items()):
                namespace = TYPES[kind]
                identity = f"osm:{namespace}:{object_id}"
                try:
                    bounds = geometry.bbox(kind, object_id)
                except GeometryError as error:
                    rejected[error.reason] += 1
                    if len(report["rejectionExamples"]) < 100:
                        report["rejectionExamples"].append({"id": identity, "reason": error.reason})
                    continue
                churches.append({"id": identity, **record,
                    "center": {"latitude": round((bounds[0] + bounds[2]) / 2, 8),
                               "longitude": round((bounds[1] + bounds[3]) / 2, 8)},
                    "sourceUrl": f"https://www.openstreetmap.org/{namespace}/{object_id}"})
        final = input_path.stat()
        if fingerprint != (final.st_size, final.st_mtime_ns, final.st_ino):
            raise CatalogError("source file changed during import; output was not replaced")
        report["rejectedByReason"] = dict(sorted(rejected.items()))
        report["counts"].update({"emitted": len(churches), "geometryRejected": sum(rejected.values()),
                                 "byKind": dict(sorted(Counter(item["kind"] for item in churches).items())),
                                 "byObjectType": dict(sorted(Counter(item["id"].split(":")[1] for item in churches).items()))})
        if not churches:
            raise CatalogError("all eligible churches have invalid or incomplete geometry; output was not replaced")
        catalog = {"schemaVersion": 1, "metadata": {"source": source,
            "generator": {"name": "feoria-church-catalog", "version": GENERATOR_VERSION,
                          "osmiumVersion": version("osmium"), "includeUnconfirmedChristian": include_unconfirmed},
            "recordCount": len(churches), "geometryRejected": sum(rejected.values())}, "churches": churches}
        free_space(output_path.parent, min_free_bytes)
        atomic_json(output_path, catalog, compact=True)
        report["outputSha256"] = file_sha256(output_path)
        report["outputBytes"] = output_path.stat().st_size
        report["status"] = "review_required" if rejected else "complete"
        atomic_json(report_path, report)
        return report
    except Exception as error:
        report["status"] = "failed"
        report["error"] = str(error)
        atomic_json(report_path, report)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Local Geofabrik .osm.pbf snapshot (OSM XML works for fixtures)")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--source-url", required=True, help="Pinned actual source download URL")
    parser.add_argument("--dump-timestamp", help="Only if the header has no timestamp; evidenced ISO 8601 UTC timestamp")
    parser.add_argument("--expected-sha256", help="Verify a previously recorded SHA-256 before processing")
    parser.add_argument("--include-unconfirmed-christian", action="store_true")
    parser.add_argument("--scratch-dir", type=Path)
    parser.add_argument("--min-free-gib", type=float, default=4.0)
    parser.add_argument("--max-scratch-gib", type=float, default=2.0)
    parser.add_argument("--max-geometry-references", type=int, default=5_000_000)
    args = parser.parse_args()
    if args.min_free_gib < 0 or args.max_scratch_gib <= 0 or args.max_geometry_references < 1:
        parser.error("resource limits must be positive (free-space reserve may be zero for tiny fixtures)")
    try:
        report = build_catalog(args.input, args.output, args.report, args.source_url,
            args.dump_timestamp, args.expected_sha256, args.include_unconfirmed_christian,
            args.scratch_dir, int(args.min_free_gib * GIB), int(args.max_scratch_gib * GIB),
            args.max_geometry_references)
    except Exception as error:
        print(f"Import failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"status": report["status"], "counts": report["counts"],
                      "report": str(args.report)}, ensure_ascii=False))
    return 2 if report["status"] == "review_required" else 0


if __name__ == "__main__":
    sys.exit(main())
