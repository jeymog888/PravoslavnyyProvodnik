import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("gazetteer", Path(__file__).parents[1] / "import_gazetteer.py")
g = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(g)


class GazetteerTest(unittest.TestCase):
    def city(self, **tags):
        return dict({"name": "Киров", "place": "city"}, **tags)

    def station(self, **tags):
        return dict({"name": "Кировская", "railway": "station", "station": "subway"}, **tags)

    def entry(self, tags, **kw):
        return g.make_entry(kw.get("object_id", 1), kw.get("latitude", 55.7729551), kw.get("longitude", 37.6312038), tags)

    def source(self, **kwargs):
        values = dict(url="https://download.geofabrik.de/russia-261110.osm.pbf", sha256="a" * 64,
                      size=4202162536, timestamp="2026-11-10T21:21:39Z")
        values.update(kwargs)
        return g.source_metadata(**values)

    def test_exact_coordinates_and_source_identity_are_preserved(self):
        category, row, reason = self.entry(self.station(network="Московский метрополитен"), object_id=277499849)
        self.assertEqual("stations", category)
        self.assertIsNone(reason)
        self.assertEqual("osm:node:277499849", row["id"])
        self.assertEqual("https://www.openstreetmap.org/node/277499849", row["sourceUrl"])
        self.assertEqual({"latitude": 55.7729551, "longitude": 37.6312038}, row["center"])
        self.assertEqual("", row["city"])
        self.assertEqual("", row["region"])
        self.assertEqual("", row["country"])
        self.assertEqual("Московский метрополитен", row["network"])

    def test_only_explicit_city_town_and_station_subway(self):
        for kind in ("city", "town"):
            self.assertEqual("cities", self.entry(self.city(place=kind))[0])
        for tags in (self.city(place="village"), self.station(railway="subway_entrance"),
                     {"name": "Станция", "subway": "yes"}, self.station(railway="stop"),
                     self.station(railway="platform"), self.station(station="train")):
            self.assertEqual("not_city_or_subway_station", self.entry(tags)[2])
        self.assertEqual("conflicting_object_kind", self.entry(self.station(place="town"))[2])

    def test_all_relevant_lifecycle_forms_reject_active_inference(self):
        for key in g.LIFECYCLE_KEYS:
            for value in ("yes", "station", "hamlet", "a" * 3000, True):
                self.assertEqual("lifecycle_conflict", self.entry(self.station(**{key: value}))[2], key)
            for value in ("", "no", "NO", "False", "0", " no "):
                self.assertIsNone(self.entry(self.station(**{key: value}))[2], (key, value))
        # A historic platform does not assert that the station itself is closed.
        self.assertIsNone(self.entry(self.station(**{"disused:platform": "yes"}))[2])

    def test_invalid_and_nonfinite_geometry_is_rejected_not_replaced(self):
        for latitude, longitude in ((None, 1), (float("nan"), 1), (1, float("inf")), (91, 0),
                                    (0, -181), (True, 0), (0, "1")):
            self.assertEqual("invalid_coordinate", self.entry(self.city(), latitude=latitude, longitude=longitude)[2])
        self.assertIsNone(self.entry(self.city(), latitude=-90, longitude=180)[2])

    def test_ids_cannot_be_boolean_negative_or_outside_osm_long(self):
        for value in (0, -1, True, "1", 9223372036854775808):
            self.assertEqual("invalid_id", self.entry(self.city(), object_id=value)[2])

    def test_published_names_aliases_and_administrative_context_only(self):
        tags = self.city(**{"name:ru": "Казань", "name:en": "Kazan", "alt_name": "Казань;Казань;Казан",
                            "loc_name": "Местное имя", "old_name": "Старое имя",
                            "addr:region:ru": "Республика Татарстан", "addr:region": "Tatarstan",
                            "addr:state": "Other", "addr:city": "Исходный город", "addr:country": "RU",
                            "addr:district": "Исходный район", "is_in": "Отдельный исходный контекст",
                            "network:ru": "Русская сеть", "network": "Network"})
        _, row, error = self.entry(tags)
        self.assertIsNone(error)
        self.assertEqual("Казань", row["name"])
        self.assertEqual(["Киров", "Казан", "Местное имя", "Kazan"], row["aliases"])
        self.assertEqual("Республика Татарстан", row["region"])
        self.assertEqual("Исходный город", row["city"])
        self.assertEqual("RU", row["country"])
        self.assertEqual("Русская сеть", row["network"])
        self.assertEqual("Исходный район · Отдельный исходный контекст", row["contextLabel"])
        self.assertNotIn("old_name", row["sourceTags"])
        self.assertNotIn("Старое имя", row["aliases"])
        self.assertEqual("Tatarstan", row["sourceTags"]["addr:region"])

    def test_context_is_deduplicated_not_promoted_to_region(self):
        _, row, _ = self.entry(self.city(**{"addr:district": "Район", "is_in": "Район"}))
        self.assertEqual("Район", row["contextLabel"])
        self.assertEqual("", row["region"])
        self.assertEqual("context_limit", self.entry(self.city(**{"addr:district": "a" * 300, "is_in": "b" * 300}))[2])

    def test_names_and_source_values_are_bounded_without_fabricated_fallback(self):
        self.assertEqual("missing_valid_name", self.entry(self.city(name="a" * 301))[2])
        self.assertEqual("missing_valid_name", self.entry(self.city(name="bad\nname"))[2])
        _, row, _ = self.entry(self.city(**{"name:ru": "a" * 301, "alt_name": "valid;" + "a" * 301,
                                           "phone": "private unrelated contact", "is_in": "a" * 3000}))
        self.assertEqual("Киров", row["name"])
        self.assertEqual(["valid"], row["aliases"])
        self.assertNotIn("phone", row["sourceTags"])
        self.assertNotIn("is_in", row["sourceTags"])
        _, supplementary, _ = self.entry(self.city(name="𐐀" * 300))
        self.assertEqual(300, len(supplementary["name"]))  # Unicode code points, not UTF-16 units.

    def test_different_ids_and_same_names_are_never_collapsed(self):
        builder = g.CatalogBuilder()
        for number in range(1, 10):
            builder.add(number, 50 + number / 100, 40, self.city(name="Октябрьский"))
        builder.add(20, 50, 40, self.station())
        output = json.loads(builder.encode(self.source(), "4.3.1"))
        self.assertEqual(9, output["metadata"]["cityCount"])
        self.assertEqual(9, len(output["cities"]))
        self.assertEqual(1, output["metadata"]["stationCount"])

    def test_duplicate_candidate_after_rejected_revision_fails_closed(self):
        builder = g.CatalogBuilder()
        builder.add(1, 50, 40, self.city(abandoned="yes"))
        with self.assertRaisesRegex(g.GazetteerError, "duplicate"):
            builder.add(1, 50, 40, self.city())
        current = g.CatalogBuilder()
        current.add(2, 50, 40, self.city())
        with self.assertRaisesRegex(g.GazetteerError, "duplicate"):
            current.add(2, 50, 40, self.station())

    def test_record_limit_and_both_collections_are_enforced(self):
        builder = g.CatalogBuilder(maximum_records=1)
        builder.add(1, 50, 40, self.city())
        with self.assertRaisesRegex(g.GazetteerError, "record limit"):
            builder.add(2, 50, 40, self.station())
        with self.assertRaisesRegex(g.GazetteerError, "nonempty"):
            builder.encode(self.source(), "4.3.1")

    def test_source_metadata_rejects_unverifiable_origins(self):
        for url in ("http://download.geofabrik.de/russia.osm.pbf", "https://evil.example/russia.osm.pbf",
                    "https://download.geofabrik.de/russia.osm.pbf?token=x", "https://user@download.geofabrik.de/russia.osm.pbf",
                    "https://download.geofabrik.de/other.osm.pbf"):
            with self.assertRaises(g.GazetteerError):
                self.source(url=url)
        for timestamp in ("2026-11-10", "bad", "1960-01-01T00:00:00Z"):
            with self.assertRaises(g.GazetteerError):
                self.source(timestamp=timestamp)
        self.assertEqual("2026-11-10T21:21:39Z", self.source(timestamp="2026-11-11T00:21:39+03:00")["dumpTimestamp"])
        self.assertEqual("ODbL-1.0", self.source()["license"])

    def test_output_is_reproducible_for_different_input_order(self):
        a, b = g.CatalogBuilder(), g.CatalogBuilder()
        rows = [(3, self.city(name="Тотьма")), (1, self.city(name="Казань")), (2, self.station())]
        for number, tags in rows:
            a.add(number, 55, 37, tags)
        for number, tags in reversed(rows):
            b.add(number, 55, 37, dict(reversed(list(tags.items()))))
        first, second = a.encode(self.source(), "4.3.1"), b.encode(self.source(), "4.3.1")
        self.assertEqual(first, second)
        self.assertEqual(hashlib.sha256(first).hexdigest(), hashlib.sha256(second).hexdigest())

    def test_atomic_write_preserves_existing_asset_when_reserve_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "asset.json"
            path.write_bytes(b"old")
            with patch.object(g, "check_space", side_effect=g.GazetteerError("reserve")):
                with self.assertRaises(g.GazetteerError):
                    g.atomic_write(path, b"replacement", 4 * g.GIB)
            self.assertEqual(b"old", path.read_bytes())
            self.assertEqual([path], list(path.parent.iterdir()))
            g.atomic_write(path, b"replacement", 0)
            self.assertEqual(b"replacement", path.read_bytes())


if __name__ == "__main__":
    unittest.main()
