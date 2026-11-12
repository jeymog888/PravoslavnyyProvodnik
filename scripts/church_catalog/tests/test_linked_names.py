import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from test_import_catalog import BASE, SOURCE, STAMP, catalog, node, way


DIRECTORY = Path(__file__).parents[1]
SNAPSHOT = DIRECTORY / "data/wikidata-names-20261112.json"
SNAPSHOT_SHA = "e3131820e1a681ac86f62d576a38a25487c941f099115b07d0280b8f4858760b"
TARGET = "Q4320848"
TARGET_ID = "osm:way:77503842"
POINT = (51.6596207, 39.2127643)
TITLE = "Храм Николая Чудотворца (Воронеж)"
LABEL = "Никольская церковь (Воронеж)"


class LinkedNamesTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.source = self.directory / "fixture.osm"
        self.output = self.directory / "catalog.json"
        self.report = self.directory / "report.json"
        self.snapshot = self.directory / "entities.json"
        self.entities = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
        self.real = json.loads((DIRECTORY / "tests/fixtures/linked-real-objects.json").read_text(encoding="utf-8"))
        self.target_tags = next(x["tags"] for x in self.real["objects"] if x["id"] == TARGET_ID)
        self.write_snapshot()
        self.write_real_source()

    def write_snapshot(self):
        self.snapshot.write_text(json.dumps(self.entities, ensure_ascii=False), encoding="utf-8")
        self.sha = hashlib.sha256(self.snapshot.read_bytes()).hexdigest()

    def write_source(self, objects):
        # Keep a known original record so an unsupported fallback can be rejected
        # without making this a vacuous all-empty catalogue failure.
        self.source.write_text('<osm version="0.6">' + node(9_000_000_000, 55, 37, BASE) +
                               "".join(objects) + '</osm>', encoding="utf-8")

    def write_real_source(self):
        objects = [node(int(identity), value["latitude"], value["longitude"])
                   for identity, value in self.real["nodes"].items()]
        objects.extend(way(item["numericId"], item["nodeReferences"], item["tags"])
                       for item in self.real["objects"])
        self.write_source(objects)

    def build(self, linked=True, **arguments):
        options = dict(dump_timestamp=STAMP, min_free_bytes=0)
        if linked:
            options.update(linked_names_path=self.snapshot, linked_names_sha256=self.sha)
        options.update(arguments)
        with contextlib.redirect_stderr(io.StringIO()):
            report = catalog.build_catalog(self.source, self.output, self.report, SOURCE, **options)
        return json.loads(self.output.read_text(encoding="utf-8")), report

    def resolve(self, tags=None):
        self.write_snapshot()
        return catalog.LinkedNames(self.snapshot, self.sha).resolve(self.target_tags if tags is None else tags)

    def test_real_two_churches_preserve_exact_osm_geometry_names_and_provenance(self):
        before, _ = self.build(linked=False)
        data, report = self.build()
        old = {item["id"]: item for item in before["churches"]}
        new = {item["id"]: item for item in data["churches"]}
        self.assertEqual({identity: new[identity] for identity in old}, old)
        self.assertEqual(len(new), len(old) + 2)
        target = new[TARGET_ID]
        self.assertEqual(target["name"], TITLE)
        self.assertIn(LABEL, target["aliases"])
        self.assertEqual(target["center"], {"latitude": POINT[0], "longitude": POINT[1]})
        self.assertEqual(target["address"], "Воронеж, улица Таранченко, 19А")
        self.assertEqual(target["city"], "Воронеж")
        self.assertIsNone(target["website"])
        self.assertEqual(target["sourceUrl"], "https://www.openstreetmap.org/way/77503842")
        self.assertNotIn("name", target["sourceTags"])
        self.assertNotIn("name:ru", target["sourceTags"])
        for key in ("wikidata", "wikipedia"):
            self.assertEqual(target["sourceTags"][key], self.target_tags[key])
        proof = target["nameSource"]
        self.assertEqual(proof["snapshotSha256"], self.sha)
        self.assertEqual(proof["entityId"], TARGET)
        self.assertIsNone(proof["entityRevision"])
        self.assertEqual(proof["displayNameField"], "sitelinks.ruwiki.title")
        self.assertEqual(len(proof["coordinates"]), 2)
        self.assertTrue(all(p["distanceFromOsmCenterMetres"] < 4 for p in proof["coordinates"]))
        self.assertEqual(report["linkedNames"]["accepted"], 2)
        self.assertEqual(data["metadata"]["source"]["name"], "OpenStreetMap via Geofabrik")
        first = self.output.read_bytes()
        self.build()
        self.assertEqual(self.output.read_bytes(), first)

    def test_pinned_published_response_and_extracted_osm_fixture_are_exact(self):
        self.assertEqual(hashlib.sha256(SNAPSHOT.read_bytes()).hexdigest(), SNAPSHOT_SHA)
        provenance = json.loads((DIRECTORY / "data/wikidata-names-20261112.provenance.json").read_text())
        self.assertEqual(provenance["sha256"], SNAPSHOT_SHA)
        self.assertEqual(set(provenance["requestedIds"]), set(self.entities["entities"]))
        self.assertEqual(self.real["sourcePbfSha256"], "2de4773b79e04fe66f0ef058b687a98902468d094bb62cb88214f800bd65fa19")
        self.assertEqual(self.real["sourceTimestamp"], "2026-11-10T21:21:39Z")

    def test_named_record_precedence_keeps_all_existing_fields(self):
        resolved, reason = self.resolve()
        self.assertIsNone(reason)
        tags = {**self.target_tags, "name": "Существующее имя", "name:ru": "Текущее русское имя",
                "alt_name": "Иное имя", "website": "http://published.example.org/"}
        plain, _ = catalog.candidate(tags)
        linked, _ = catalog.candidate(tags, linked_name=resolved)
        self.assertEqual(linked, plain)
        self.assertNotIn("nameSource", linked)
        self.assertNotIn("wikidata", linked["sourceTags"])

    def test_label_is_used_without_osm_wikipedia_and_no_city_is_invented(self):
        tags = {key: value for key, value in self.target_tags.items() if key not in ("wikipedia", "addr:city")}
        self.write_source([node(100, *POINT, tags)])
        data, _ = self.build()
        actual = next(x for x in data["churches"] if x["id"] == "osm:node:100")
        self.assertEqual(actual["name"], LABEL)
        self.assertIn(TITLE, actual["aliases"])
        self.assertEqual(actual["city"], "")
        self.assertEqual(actual["nameSource"]["displayNameField"], "labels.ru.value")

    def test_foreign_unknown_religion_belltower_and_other_amenity_never_gain_a_name(self):
        changes = [{"religion": ""}, {"religion": "muslim"}, {"denomination": ""},
                   {"denomination": "catholic"}, {"tower:type": "bell_tower"}, {"amenity": "school"}]
        self.write_source([node(i + 100, *POINT, {**self.target_tags, **change}) for i, change in enumerate(changes)])
        for include_unknown in (False, True):
            data, report = self.build(include_unconfirmed=include_unknown)
            self.assertEqual([x["id"] for x in data["churches"]], ["osm:node:9000000000"])
            self.assertEqual(report["linkedNames"]["accepted"], 0)

    def test_nonempty_invalid_primary_name_cannot_be_salvaged(self):
        for raw in ("x" * 301, "Поврежденное\x7f имя", "\u200b"):
            with self.subTest(raw=raw[:20]):
                result, reason = self.resolve({**self.target_tags, "name": raw})
                self.assertIsNone(result)
                self.assertEqual(reason, "linked_invalid_primary_name")
        # Normal whitespace is an absent OSM name, not a damaged nonempty value.
        self.assertIsNotNone(self.resolve({**self.target_tags, "name": "  "})[0])

    def test_qid_is_exact_and_not_a_url_list_or_zero(self):
        for raw in ("", "Q0", "q4320848", "Q4320848;Q4500934", "https://www.wikidata.org/wiki/Q4320848", "Q4320848#x", "Q" + "1" * 20):
            with self.subTest(raw=raw):
                result, reason = self.resolve({**self.target_tags, "wikidata": raw})
                self.assertIsNone(result)
                self.assertEqual(reason, "linked_missing_or_invalid_qid")

    def test_invalid_namespace_url_fragment_and_conflicting_wiki_links_are_rejected(self):
        for value in ("ru:", "ru:Категория:Храмы", "ru:Участник:Храм", "https://ru.wikipedia.org/wiki/Храм",
                      "ru:" + TITLE + "#История", "ru:%D0%A5", "RU:" + TITLE, "ru:" + TITLE + "\n"):
            with self.subTest(value=value):
                self.assertEqual(self.resolve({**self.target_tags, "wikipedia": value})[1], "linked_invalid_wikipedia")
        self.assertEqual(self.resolve({**self.target_tags, "wikipedia:ru": "Другой храм"})[1], "linked_conflicting_wikipedia")
        self.assertIsNotNone(self.resolve({**self.target_tags, "wikipedia:ru": TITLE})[0])
        self.assertIsNotNone(self.resolve({**self.target_tags, "wikipedia": "en:Saint Nicholas Church", "wikipedia:ru": TITLE})[0])
        self.entities["entities"][TARGET]["sitelinks"]["ruwiki"]["title"] = "Другой храм"
        self.assertEqual(self.resolve()[1], "linked_wikipedia_mismatch")

    def test_shared_qid_is_quarantined_with_named_or_unnamed_other_building(self):
        for other in (dict(self.target_tags), {**self.target_tags, "name": "Соседний именованный храм"}):
            with self.subTest(named="name" in other):
                self.write_source([node(100, *POINT, self.target_tags), node(101, POINT[0] + .0001, POINT[1], other)])
                before, _ = self.build(linked=False)
                after, report = self.build()
                self.assertEqual(after["churches"], before["churches"])
                self.assertIn(TARGET, report["linkedNames"]["ambiguousEntityIds"])
                self.assertEqual(report["linkedNames"]["accepted"], 0)
                self.assertEqual(report["linkedNames"]["excludedByReason"]["linked_ambiguous_qid"], 1 if "name" in other else 2)

    def test_missing_partial_redirect_and_mismatched_entity_never_supply_another_name(self):
        original = copy.deepcopy(self.entities)
        mutations = [lambda e: e["entities"].pop(TARGET),
                     lambda e: e["entities"].update({TARGET: {"id": TARGET, "missing": ""}}),
                     lambda e: e["entities"][TARGET].update({"id": "Q4500934"}),
                     lambda e: e["entities"][TARGET].update({"redirect": {"to": "Q4500934"}})]
        for mutate in mutations:
            self.entities = copy.deepcopy(original)
            mutate(self.entities)
            self.write_snapshot()
            data, report = self.build()
            ids = {x["id"] for x in data["churches"]}
            self.assertNotIn(TARGET_ID, ids)
            self.assertIn("osm:way:130017842", ids)
            self.assertIn("osm:node:9000000000", ids)
            self.assertEqual(report["linkedNames"]["accepted"], 1)

    def test_address_label_non_russian_metadata_and_unpublished_aliases_are_not_names(self):
        original = copy.deepcopy(self.entities)
        changes = [lambda e: e["labels"]["ru"].update({"value": "улица Зои Космодемьянской 17А"}),
                   lambda e: e["labels"]["ru"].update({"language": "en"}),
                   lambda e: e["labels"]["ru"].update({"value": "Saint Nicholas Church"}),
                   lambda e: e["labels"]["ru"].update({"value": "Храм\u202e другой"}),
                   lambda e: e["aliases"].update({"ru": [{"language": "en", "value": "Храм"}]}),
                   lambda e: e["sitelinks"]["ruwiki"].update({"site": "enwiki"})]
        for change in changes:
            self.entities = copy.deepcopy(original)
            change(self.entities["entities"][TARGET])
            self.assertIsNone(self.resolve()[0])

    def test_coordinate_value_type_globe_precision_and_all_current_claims_are_checked(self):
        original = copy.deepcopy(self.entities)
        changes = [lambda e: e["claims"].pop("P625"),
                   lambda e: e["claims"].update({"P625": []}),
                   lambda e: e["claims"]["P625"][0]["mainsnak"].update({"snaktype": "novalue"}),
                   lambda e: e["claims"]["P625"][0]["mainsnak"]["datavalue"]["value"].update({"globe": "http://www.wikidata.org/entity/Q111"}),
                   lambda e: e["claims"]["P625"][0]["mainsnak"]["datavalue"]["value"].update({"precision": 0}),
                   lambda e: e["claims"]["P625"][0]["mainsnak"]["datavalue"]["value"].update({"latitude": True}),
                   lambda e: e["claims"]["P625"][0]["mainsnak"]["datavalue"]["value"].update({"latitude": 91}),
                   lambda e: e["claims"]["P625"][0]["mainsnak"]["datavalue"].update({"type": "string"}),
                   lambda e: e["claims"]["P625"][0].update({"id": "Q4500934$OTHER"})]
        for change in changes:
            self.entities = copy.deepcopy(original)
            change(self.entities["entities"][TARGET])
            self.assertIsNone(self.resolve()[0])
        for rank in ("normal", "preferred"):
            self.entities = copy.deepcopy(original)
            second = self.entities["entities"][TARGET]["claims"]["P625"][1]
            second["rank"] = rank
            second["mainsnak"]["datavalue"]["value"].update({"latitude": 52, "precision": 180})
            self.write_snapshot()
            data, report = self.build()
            self.assertNotIn(TARGET_ID, {x["id"] for x in data["churches"]})
            self.assertEqual(report["linkedNames"]["excludedByReason"]["linked_coordinate_mismatch"], 1)
        # A deprecated value remains in the pinned raw input, but cannot overrule current points.
        second["rank"] = "deprecated"
        self.write_snapshot()
        data, _ = self.build()
        self.assertIn(TARGET_ID, {x["id"] for x in data["churches"]})

    def test_invalid_snapshot_and_hash_never_replace_previous_output(self):
        self.build()
        previous = self.output.read_bytes()
        invalid = [b'{"entities":{},"success":1}', b'{"entities":{},"success":1,"success":1}',
                   b'{"error":{"code":"maxlag"}}', b'{"entities":[],"success":1}',
                   b'{"entities":{"Q1":{}},"success":true}', b'\xff',
                   b'{"entities":{"Q1":{}},"success":1,"x":NaN}',
                   b'{"entities":{"Q1":{}},"success":1,"x":Infinity}', b'[]',
                   json.dumps({"entities": {f"Q{i}": {} for i in range(1, 52)}, "success": 1}).encode()]
        for raw in invalid:
            self.snapshot.write_bytes(raw)
            self.sha = hashlib.sha256(raw).hexdigest()
            with self.subTest(raw=raw[:40]), self.assertRaises(catalog.CatalogError):
                self.build()
            self.assertEqual(self.output.read_bytes(), previous)
        for arguments in ({"linked_names_sha256": "0" * 64}, {"linked_names_sha256": None},
                          {"linked_names_path": None}, {"linked_names_path": self.output}):
            with self.assertRaises(catalog.CatalogError):
                self.build(**arguments)
            self.assertEqual(self.output.read_bytes(), previous)
        self.assertEqual(json.loads(self.report.read_text())["status"], "failed")

    def test_byte_limit_and_invalid_object_id_do_not_gain_fallback(self):
        self.snapshot.write_bytes(b" " * (catalog.LINKED_NAMES_MAX_BYTES + 1))
        self.sha = hashlib.sha256(self.snapshot.read_bytes()).hexdigest()
        with self.assertRaisesRegex(catalog.CatalogError, "byte limit"):
            self.build()
        self.write_snapshot()
        self.write_source([node(0, *POINT, self.target_tags)])
        data, report = self.build()
        self.assertEqual([x["id"] for x in data["churches"]], ["osm:node:9000000000"])
        self.assertEqual(report["excludedByReason"]["deleted_or_invalid_id"], 1)

    def test_report_path_alias_cannot_overwrite_the_pinned_evidence_on_error(self):
        self.build()
        previous, evidence = self.output.read_bytes(), self.snapshot.read_bytes()
        alias = self.directory / "snapshot-alias.json"
        alias.symlink_to(self.snapshot)
        for report_path in (self.snapshot, alias):
            with self.assertRaisesRegex(catalog.CatalogError, "must differ"):
                catalog.build_catalog(self.source, self.output, report_path, SOURCE,
                    dump_timestamp=STAMP, min_free_bytes=0,
                    linked_names_path=self.snapshot, linked_names_sha256=self.sha)
            self.assertEqual(self.snapshot.read_bytes(), evidence)
            self.assertEqual(self.output.read_bytes(), previous)

    def test_full_geometry_failure_is_not_replaced_by_entity_point(self):
        self.write_source([node(1, *POINT), way(100, [1, 999], self.target_tags)])
        data, report = self.build()
        self.assertEqual([x["id"] for x in data["churches"]], ["osm:node:9000000000"])
        self.assertEqual(report["rejectedByReason"], {"missing_node": 1})
        self.assertEqual(report["linkedNames"]["excludedByReason"], {"missing_node": 1})

    def test_combined_alias_budget_and_display_utf16_limit_cannot_break_android_decoder(self):
        self.entities["entities"][TARGET]["aliases"]["ru"] = [
            {"language": "ru", "value": f"Внешнее {i} " + "б" * 280} for i in range(64)]
        self.write_snapshot()
        data, report = self.build()
        self.assertNotIn(TARGET_ID, {x["id"] for x in data["churches"]})
        self.assertEqual(report["linkedNames"]["excludedByReason"]["linked_alias_budget"], 1)
        self.entities["entities"][TARGET]["aliases"]["ru"] = []
        self.entities["entities"][TARGET]["labels"]["ru"]["value"] = "Храм " + "😀" * 148
        self.assertIsNone(self.resolve()[0])
        self.assertEqual(len(self.entities["entities"][TARGET]["labels"]["ru"]["value"]), 153)

    def test_osm_and_external_aliases_each_fit_but_combined_total_is_rejected(self):
        self.entities["entities"][TARGET]["aliases"]["ru"] = [
            {"language": "ru", "value": f"Внешнее {i} " + "б" * 280} for i in range(20)]
        tags = dict(self.target_tags)
        for key_index, key in enumerate(("alt_name", "alt_name:ru", "official_name", "official_name:ru", "short_name", "short_name:ru")):
            tags[key] = ";".join(f"Имя {key_index} {i} " + "а" * 280 for i in range(7))
            self.assertLessEqual(len(tags[key]), 2048)
        # Exercise the public candidate contract directly: libosmium's XML reader
        # applies an even smaller per-tag limit before the importer's 2048 cap.
        resolved, reason = self.resolve(tags)
        self.assertIsNone(reason)
        actual, reason = catalog.candidate(tags, linked_name=resolved)
        self.assertIsNone(actual)
        self.assertEqual(reason, "linked_alias_budget")

    def test_new_address_fields_over_android_utf16_limit_reject_only_the_new_record(self):
        astral = "\U00020000"
        variants = {
            "city": {"addr:city": "Город " + astral * 99},
            "region": {"addr:region": "Область " + astral * 97},
            "country": {"addr:country": "Страна " + astral * 98},
            # Every raw tag fits its code-point limit and the city fits Android.
            # Only the final joined address exceeds 512 UTF-16 units.
            "address": {"addr:city": astral * 100, "addr:street": astral * 150,
                        "addr:housenumber": astral * 10},
        }
        for field, changes in variants.items():
            with self.subTest(field=field):
                self.write_source([node(100, *POINT, {**self.target_tags, **changes})])
                before, _ = self.build(linked=False)
                after, report = self.build()
                self.assertEqual(after["churches"], before["churches"])
                self.assertEqual([x["id"] for x in after["churches"]], ["osm:node:9000000000"])
                self.assertEqual(report["linkedNames"]["accepted"], 0)
                self.assertEqual(report["linkedNames"]["excludedByReason"],
                                 {f"linked_{field}_android_contract": 1})

    def test_new_address_fields_keep_cyrillic_and_astral_text_at_android_boundaries(self):
        astral = "\U00020000"
        published_alias = "Имя " + astral * 200
        self.entities["entities"][TARGET]["aliases"]["ru"] = [{"language": "ru", "value": published_alias}]
        self.write_snapshot()
        tags = {**self.target_tags, "addr:city": astral * 100, "addr:street": astral * 145,
                "addr:housenumber": "а" * 18, "addr:region": "я" * 200,
                "addr:country": astral * 100}
        self.write_source([node(100, *POINT, tags)])
        data, report = self.build()
        record = next(x for x in data["churches"] if x["id"] == "osm:node:100")
        expected = {"address": f'{tags["addr:city"]}, {tags["addr:street"]}, {tags["addr:housenumber"]}',
                    "city": tags["addr:city"], "region": tags["addr:region"], "country": tags["addr:country"]}
        for field, limit in (("address", 512), ("city", 200), ("region", 200), ("country", 200)):
            self.assertEqual(record[field], expected[field])
            self.assertEqual(len(record[field].encode("utf-16-le")) // 2, limit)
        self.assertIn(published_alias, record["aliases"])
        self.assertLessEqual(len(published_alias), 300)
        self.assertGreater(len(published_alias.encode("utf-16-le")) // 2, 300)
        self.assertEqual(report["linkedNames"]["accepted"], 1)
        # An ordinary Cyrillic full address and the state fallback remain exact too.
        tags = {**self.target_tags, "addr:full": "Воронеж, улица Таранченко, дом 19А",
                "addr:state": "Воронежская область", "addr:country": "Россия"}
        self.write_source([node(100, *POINT, tags)])
        data, _ = self.build()
        record = next(x for x in data["churches"] if x["id"] == "osm:node:100")
        self.assertEqual(record["address"], tags["addr:full"])
        self.assertEqual(record["region"], tags["addr:state"])
        self.assertEqual(record["country"], tags["addr:country"])

    def test_android_address_contract_keeps_empty_and_format_text_but_rejects_iso_controls(self):
        self.assertTrue(catalog.android_string("", 0))
        self.assertTrue(catalog.android_string("а\u200dб", 3))  # Cf is not Char.isISOControl.
        self.assertTrue(catalog.android_string("\U00020000", 2))
        self.assertFalse(catalog.android_string("\U00020000", 1))
        for codepoint in (*range(32), *range(127, 160)):
            with self.subTest(codepoint=codepoint):
                self.assertFalse(catalog.android_string("а" + chr(codepoint) + "б", 3))

    def test_existing_named_address_fields_are_not_rewritten_by_the_new_contract(self):
        invalid_city = "Город " + "\U00020000" * 99
        tags = {**self.target_tags, "name": "Существующий храм", "addr:city": invalid_city}
        before, _ = catalog.candidate(tags)
        resolved, _ = self.resolve()
        after, _ = catalog.candidate(tags, linked_name=resolved)
        self.assertEqual(after, before)
        self.assertEqual(after["city"], invalid_city)
        self.assertNotIn("nameSource", after)


if __name__ == "__main__":
    unittest.main()
