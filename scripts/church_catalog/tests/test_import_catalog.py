import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from xml.sax.saxutils import quoteattr

import osmium


MODULE_PATH = Path(__file__).parents[1] / "import_catalog.py"
spec = importlib.util.spec_from_file_location("import_catalog", MODULE_PATH)
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)
STAMP = "2026-11-10T21:00:00Z"
SOURCE = "https://download.geofabrik.de/russia-261110.osm.pbf"
BASE = {"amenity": "place_of_worship", "religion": "christian",
        "denomination": "orthodox", "name": "Храм"}


def tags_xml(tags):
    return "".join(f"<tag k={quoteattr(k)} v={quoteattr(v)}/>" for k, v in tags.items())


def node(object_id, lat, lon, tags=None):
    return f'<node id="{object_id}" lat="{lat}" lon="{lon}" version="1">{tags_xml(tags or {})}</node>'


def way(object_id, refs, tags=None):
    members = "".join(f'<nd ref="{ref}"/>' for ref in refs)
    return f'<way id="{object_id}" version="1">{members}{tags_xml(tags or {})}</way>'


def relation(object_id, members, tags=None):
    refs = "".join(f'<member type="{kind}" ref="{ref}" role="outer"/>' for kind, ref in members)
    return f'<relation id="{object_id}" version="1">{refs}{tags_xml(tags or {})}</relation>'


def fixture():
    objects = [node(1, 10, 20), node(2, 14, 28), node(3, 30, 50), node(4, 91, 20),
               node(100, 50, 60, BASE), node(200, 51, 61, BASE),
               node(300, 55, 37, {**BASE, "denomination": "", "name": "Часовня без конфессии"}),
               node(301, 55, 37, {**BASE, "religion": ""}),
               node(302, 55, 37, {**BASE, "religion": "muslim"}),
               node(303, 55, 37, {**BASE, "denomination": "catholic"}),
               node(304, 55, 37, {**BASE, "tower:type": "bell_tower"}),
               node(305, 55, 37, {**BASE, "name": ""}),
               node(306, 55, 37, {**BASE, "name": "Temple", "name:ru": "Храм Николая",
                    "alt_name": "Никольский; Temple", "addr:street": "Садовая", "addr:housenumber": "2",
                    "website": "https://admin:pass@example.org/", "contact:website": "http://parish.example.org/news",
                    "contact:phone": "+7 900 123 45 67"}),
               node(9999, 1, 1)]
    objects += [way(100, [1, 2], BASE), way(101, [1, 2], BASE),
                way(102, [1, 999], BASE), way(103, [1, 4], BASE), way(104, [1], BASE)]
    objects += [relation(100, [("way", 100), ("node", 3)], BASE),
                relation(201, [("way", 100)]),
                relation(202, [("relation", 201), ("node", 3)], BASE),
                relation(203, [("relation", 999)], BASE),
                relation(204, [("relation", 205)], BASE),
                relation(205, [("relation", 204)]),
                relation(206, [("way", 999)], BASE),
                relation(207, [], BASE),
                relation(208, [("way", 100), ("node", 999)], BASE)]
    return '<osm version="0.6" generator="church-catalog-tests">' + "".join(objects) + "</osm>"


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.source = self.directory / "fixture.osm"
        self.source.write_text(fixture(), encoding="utf-8")
        self.output = self.directory / "catalog.json"
        self.report_path = self.directory / "report.json"

    def build(self, **kwargs):
        arguments = dict(dump_timestamp=STAMP, min_free_bytes=0)
        arguments.update(kwargs)
        with contextlib.redirect_stderr(io.StringIO()):
            report = catalog.build_catalog(self.source, self.output, self.report_path, SOURCE, **arguments)
        return json.loads(self.output.read_text()), report

    def test_ids_preserve_namespaces_and_do_not_merge_buildings(self):
        data, report = self.build()
        ids = {item["id"] for item in data["churches"]}
        self.assertTrue({"osm:node:100", "osm:way:100", "osm:relation:100", "osm:way:101", "osm:node:200"} <= ids)
        self.assertEqual(len(ids), len(data["churches"]))
        self.assertEqual(report["counts"]["emitted"], len(ids))
        for item in data["churches"]:
            _, kind, object_id = item["id"].split(":")
            self.assertEqual(item["sourceUrl"], f"https://www.openstreetmap.org/{kind}/{object_id}")

    def test_way_and_nested_relation_use_complete_member_bbox(self):
        data, _ = self.build()
        objects = {item["id"]: item for item in data["churches"]}
        self.assertEqual(objects["osm:way:100"]["center"], {"latitude": 12.0, "longitude": 24.0})
        for identity in ("osm:relation:100", "osm:relation:202"):
            self.assertEqual(objects[identity]["center"], {"latitude": 20.0, "longitude": 35.0})
        self.assertEqual(objects["osm:node:100"]["center"], {"latitude": 50.0, "longitude": 60.0})

    def test_missing_invalid_empty_and_cyclic_geometries_are_rejected_and_reported(self):
        data, report = self.build()
        self.assertEqual(report["status"], "review_required")
        self.assertEqual(report["rejectedByReason"], {"missing_node": 2, "invalid_node_location": 1,
            "short_way": 1, "missing_relation": 1, "cyclic_relation": 1, "missing_way": 1, "empty_geometry": 1})
        self.assertEqual(report["counts"]["geometryRejected"], 8)
        self.assertEqual(data["metadata"]["geometryRejected"], 8)
        self.assertNotIn("osm:relation:208", {item["id"] for item in data["churches"]})

    def test_denominations_and_foreign_religions_are_not_inferred_from_names(self):
        data, _ = self.build(include_unconfirmed=True)
        objects = {item["id"]: item for item in data["churches"]}
        self.assertEqual(objects["osm:node:300"]["kind"], "WORSHIP_PLACE")
        self.assertFalse(objects["osm:node:300"]["isChapel"])
        for object_id in (301, 302, 303, 304, 305):
            self.assertNotIn(f"osm:node:{object_id}", objects)
        strict, _ = self.build()
        self.assertNotIn("osm:node:300", {item["id"] for item in strict["churches"]})
        record, _ = catalog.candidate({**BASE, "denomination": "greek_orthodox; russian_orthodox"})
        self.assertEqual(record["kind"], "CHURCH")

    def test_aliases_street_without_city_and_published_contacts_are_preserved(self):
        data, _ = self.build()
        record = next(item for item in data["churches"] if item["id"] == "osm:node:306")
        self.assertEqual(record["name"], "Храм Николая")
        self.assertEqual(record["aliases"], ["Temple", "Никольский"])
        self.assertEqual(record["address"], "Садовая, 2")
        self.assertEqual(record["city"], "")
        self.assertEqual(record["country"], "")
        self.assertEqual(record["website"], "http://parish.example.org/news")
        self.assertEqual(record["sourceTags"]["contact:phone"], "+7 900 123 45 67")

    def test_safe_website_is_only_a_valid_public_source_tag(self):
        for value in ("https://example.org/a?x=1%26b", "http://example.org/", "https://example.org:443/"):
            self.assertEqual(catalog.safe_website(value), value)
        for value in ("javascript:alert(1)", "//example.org", "https://user@example.org/",
                      "https://localhost/", "https://host.local/", "https://127.0.0.1/",
                      "https://[::1]/", "https://example.org:9999/", "https://example.org/%XX",
                      "https://example.org/\nfoo", "https://example.org\\@evil.test", "https://"):
            self.assertIsNone(catalog.safe_website(value), value)
        record, _ = catalog.candidate({**BASE, "website": "javascript:alert(1)"})
        self.assertIsNone(record["website"])
        self.assertEqual(record["kind"], "CHURCH")

    def test_cyrillic_website_keeps_raw_source_and_normalizes_only_explicit_hostname(self):
        original = "https://ппсобор.рф/расписание/?title=молебен%26служба"
        normalized = "https://xn--90azbbajd.xn--p1ai/расписание/?title=молебен%26служба"
        self.assertEqual(catalog.safe_website(original), normalized)
        record, _ = catalog.candidate({**BASE, "website": original})
        self.assertEqual(record["website"], normalized)
        self.assertEqual(record["sourceTags"]["website"], original)
        self.assertEqual(catalog.safe_website("http://ппсобор.рф:80/"), "http://xn--90azbbajd.xn--p1ai:80/")
        self.assertEqual(catalog.safe_website("https://straße.de/?page=a%26b%3Dc"), "https://strasse.de/?page=a%26b%3Dc")
        for invalid in ("https://логин:пароль@ппсобор.рф/", "https://ппсобор.рф%40example.org/",
                        "https://127。0。0。1/", "https://храм.local/", "https://храм.localhost/",
                        "https://-храм.рф/", "https://храм_.рф/", "https://храм..рф/",
                        "https://ппсо\u202eбор.рф/", "https://ппсо\u200bбор.рф/", "https://ппсобор.рф/\u2066x",
                        "https://ппсобор.рф:/", "https://ппсобор.рф:４４３/", "ппсобор.рф/", "//ппсобор.рф/"):
            self.assertIsNone(catalog.safe_website(invalid), invalid)

    def test_artifact_is_reproducible_and_provenance_is_file_specific(self):
        data, report = self.build()
        before = self.output.read_bytes()
        self.build()
        self.assertEqual(before, self.output.read_bytes())
        source = data["metadata"]["source"]
        self.assertEqual(source["sha256"], hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.assertEqual(source["dumpTimestamp"], STAMP)
        self.assertEqual(source["license"], "ODbL-1.0")
        self.assertEqual(source["attribution"], "© OpenStreetMap contributors")
        self.assertEqual(report["outputSha256"], hashlib.sha256(before).hexdigest())

    def test_only_referenced_nodes_are_loaded(self):
        _, report = self.build()
        self.assertEqual(report["counts"]["requestedNodes"], 8)
        self.assertEqual(report["counts"]["foundNodes"], 6)
        self.assertEqual(report["counts"]["invalidNodes"], 1)
        self.assertFalse(list(self.directory.glob("church-catalog-*")))

    def test_hash_failure_does_not_replace_existing_catalog_and_writes_failure_report(self):
        self.output.write_text("previous catalog")
        with self.assertRaisesRegex(catalog.CatalogError, "SHA-256"):
            self.build(expected_sha256="0" * 64)
        self.assertEqual(self.output.read_text(), "previous catalog")
        self.assertEqual(json.loads(self.report_path.read_text())["status"], "failed")

    def test_no_dump_timestamp_is_not_replaced_with_current_time(self):
        with self.assertRaisesRegex(catalog.CatalogError, "missing dump timestamp"):
            self.build(dump_timestamp=None)
        self.assertFalse(self.output.exists())

    def test_duplicate_snapshot_ids_fail_instead_of_silently_overwriting(self):
        self.source.write_text('<osm version="0.6">' + node(10, 1, 2, BASE) + node(10, 3, 4, BASE) + '</osm>')
        with self.assertRaisesRegex(catalog.CatalogError, "duplicate eligible"):
            self.build()
        self.assertFalse(self.output.exists())

    def test_pbf_smoke_matches_xml_and_reads_dump_timestamp_from_header(self):
        xml_data, _ = self.build()
        pbf = self.directory / "fixture.osm.pbf"
        header = osmium.io.Header()
        header.set("osmosis_replication_timestamp", STAMP)
        with osmium.SimpleWriter(pbf, header=header) as writer:
            for obj in osmium.FileProcessor(self.source):
                writer.add(obj)
        self.source = pbf
        data, _ = self.build(dump_timestamp=None)
        self.assertEqual(data["churches"], xml_data["churches"])
        self.assertEqual(data["metadata"]["source"]["timestampOrigin"], "osm_header")
        with self.assertRaisesRegex(catalog.CatalogError, "disagrees"):
            self.build(dump_timestamp="2026-11-11T00:00:00Z")

    def test_invalid_reference_id_and_missing_coordinates_never_produce_zero_center(self):
        geometry = catalog.Geometry({}, {1: [0, 2]}, {}, set())
        with self.assertRaisesRegex(catalog.GeometryError, "invalid_reference_id"):
            geometry.bbox("w", 1)
        with self.assertRaisesRegex(catalog.GeometryError, "missing_node"):
            geometry.bbox("n", 999)

    def test_complete_small_catalog_and_no_churches_failure(self):
        self.source.write_text('<osm version="0.6">' + node(1, 55.1234567, 37.1234567, BASE) + '</osm>')
        data, report = self.build()
        self.assertEqual(report["status"], "complete")
        self.assertEqual(data["churches"][0]["center"], {"latitude": 55.1234567, "longitude": 37.1234567})
        previous = self.output.read_bytes()
        self.source.write_text('<osm version="0.6">' + node(1, 1, 2) + '</osm>')
        with self.assertRaisesRegex(catalog.CatalogError, "no eligible"):
            self.build()
        self.assertEqual(self.output.read_bytes(), previous)

    def test_cli_exit_codes_distinguish_complete_review_required_and_failure(self):
        command = [sys.executable, str(MODULE_PATH), str(self.source), "--output", str(self.output),
                   "--report", str(self.report_path), "--source-url", SOURCE,
                   "--dump-timestamp", STAMP, "--min-free-gib", "0"]
        review = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(review.returncode, 2, review.stderr)
        self.assertEqual(json.loads(review.stdout)["status"], "review_required")
        self.source.write_text('<osm version="0.6">' + node(1, 55, 37, BASE) + '</osm>')
        complete = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(complete.returncode, 0, complete.stderr)
        previous = self.output.read_bytes()
        self.source.write_text('<osm version="0.6"><broken>')
        failure = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(failure.returncode, 1, failure.stderr)
        self.assertEqual(json.loads(self.report_path.read_text())["status"], "failed")
        self.assertEqual(self.output.read_bytes(), previous)

    def test_resource_limits_fail_explicitly_and_remove_temporary_index(self):
        self.output.write_text("previous catalog")
        with self.assertRaisesRegex(catalog.CatalogError, "reference budget"):
            self.build(max_geometry_references=1)
        self.assertEqual(self.output.read_text(), "previous catalog")
        self.assertFalse(list(self.directory.glob("church-catalog-*")))
        with self.assertRaisesRegex(catalog.CatalogError, "free disk reserve"):
            self.build(min_free_bytes=10 ** 20)
        self.assertEqual(json.loads(self.report_path.read_text())["status"], "failed")

    def test_relation_depth_is_independent_of_cache_and_candidate_order(self):
        relations = {i: [("r", i + 1)] for i in range(1, 130)}
        relations[130] = [("n", 1)]
        for warm in (False, True):
            geometry = catalog.Geometry({1: (55, 37)}, {}, relations, set())
            if warm:
                for identity in range(130, 4, -1):
                    self.assertEqual(geometry.bbox("r", identity), (55, 37, 55, 37))
            with self.assertRaisesRegex(catalog.GeometryError, "relation_depth_limit"):
                geometry.bbox("r", 1)
            self.assertEqual(geometry.bbox("r", 127), (55, 37, 55, 37))
            with self.assertRaisesRegex(catalog.GeometryError, "relation_depth_limit"):
                geometry.bbox("r", 1)

    def test_antimeridian_geometry_is_reported_instead_of_becoming_a_zero_longitude(self):
        self.source.write_text('<osm version="0.6">' + node(1, 65, 179.99) + node(2, 65, -179.99) +
            node(3, 55, 37, BASE) + way(1, [1, 2], BASE) + '</osm>')
        data, report = self.build()
        self.assertEqual([item["id"] for item in data["churches"]], ["osm:node:3"])
        self.assertEqual(report["rejectedByReason"], {"antimeridian_geometry": 1})
        self.assertEqual(report["status"], "review_required")


if __name__ == "__main__":
    unittest.main()
