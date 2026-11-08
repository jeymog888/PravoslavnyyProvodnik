import copy
import importlib.util
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "feoria_harness.py"
spec = importlib.util.spec_from_file_location("feoria_harness", MODULE_PATH)
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
P = "ru.liturgyguide"
DISPLAY = f"Display: mDisplayId=0\n init=320x640 160dpi cur=320x640\n mCurrentFocus=Window{{abc u0 {P}/.MainActivity}}\n"
WINDOW = f"  Window #0 Window{{abc u0 {P}/.MainActivity}}:\n package={P}\n isOnScreen=true\n isVisible=true\n"
PNG = b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + struct.pack(">II", 320, 640) + b"\0" * 20


def node(text="Play", clickable=True, enabled=True, bounds="[0,0][100,60]", children="", package=P,
         class_name="android.view.View", selected=False):
    return f'<node text="{text}" class="{class_name}" selected="{str(selected).lower()}" package="{package}" enabled="{str(enabled).lower()}" clickable="{str(clickable).lower()}" bounds="{bounds}">{children}</node>'


def xml(children=None, **kwargs):
    if children is None:
        children = node(**kwargs)
    return ('<hierarchy rotation="0">' + node(text="", clickable=False, bounds="[0,0][320,640]", children=children) + '</hierarchy>').encode()


def view(data=None, windows=WINDOW, displays=DISPLAY):
    return h.View(data or xml(), windows, displays, P)


class Clock:
    def __init__(self):
        self.value = 0

    def __call__(self):
        self.value += 0.3
        return self.value

    def sleep(self, value):
        self.value += value


class FakeAdb:
    serial = "fixture-device"

    def __init__(self, apk_hash="a" * 64, views=None):
        self.apk_hash = apk_hash
        self.views = list(views or [view()])
        self.calls = []
        self.png = PNG

    def installed_hash(self, package):
        self.calls.append(("hash", package))
        return self.apk_hash

    def run(self, *args, **kwargs):
        self.calls.append(args)
        if args[0] == "install":
            return b"Success\n"
        if args[:2] == ("exec-out", "screencap"):
            return self.png
        raise AssertionError(args)

    def shell(self, *args, **kwargs):
        self.calls.append(args)
        return ""

    def view(self, package, allowed_windows=(), **kwargs):
        self.calls.append(("view", package))
        return self.views.pop(0) if len(self.views) > 1 else self.views[0]

    def facts(self, package):
        return {"serial": self.serial, "sdk": "35", "fingerprint": "fixture/fingerprint", "wm_size": "Physical size: 320x640",
                "density": "Physical density: 160", "font_scale": "1.0", "locale": "ru-RU", "timezone": "Europe/Moscow",
                "device_epoch_seconds": "1794140000", "wifi_setting": "0", "mobile_data_setting": "0", "location_mode": "0",
                "permissions": {"android.permission.RECORD_AUDIO": "false"}, "version_code": "4", "version_name": "0.4.0-guide"}


def manifest(review=False):
    return {"schema": 1, "package": P, "build_type": "debug", "conditions": {"data": "fixture", "clock": "device"},
            "cases": [{"id": "reader", "mode": "static", "expected": [{"text": "Play", "enabled": True}],
                       "conditions": {"player": "paused", "data": "fixture"}, "human_review_required": review,
                       "observed_requirements": {"sdk": "35", "font_scale": "1.0"}}]}


class SelectorTests(unittest.TestCase):
    def test_selected_mode_is_observed_on_its_own_ancestor(self):
        selected = node(text="", selected=True, children=node(text="Russian", clickable=False))
        other = node(text="", selected=False, children=node(text="Original", clickable=False))
        observed = view(xml(children=selected + other))
        ancestor = {"class": "android.view.View", "selected": True}
        observed.expect([{"text": "Russian", "ancestor": ancestor}])
        with self.assertRaisesRegex(h.GateError, "postcondition absent"):
            observed.expect([{"text": "Original", "ancestor": ancestor}])

    def test_disabled_play_is_observed_without_treating_its_icon_as_disabled(self):
        data = xml(children=node(text="", class_name="android.widget.Button", enabled=False,
                                 children=node(text="Play", clickable=False, enabled=True)))
        observed = view(data)
        selector = {"text": "Play", "ancestor": {"class": "android.widget.Button", "enabled": False}}
        observed.expect([selector])
        with self.assertRaisesRegex(h.GateError, "disabled"):
            observed.target(selector)

    def test_ancestor_predicates_are_bounded_not_recursive(self):
        with self.assertRaisesRegex(h.GateError, "unsupported selector"):
            h.validate_selector({"text": "Play", "ancestor": {"class": "android.view.View",
                                "ancestor": {"class": "android.view.View"}}})

    def test_unique_clickable_ancestor_and_deduplicated_labels(self):
        labels = node(clickable=False) + node(clickable=False)
        data = xml(children=node(text="", children=labels))
        self.assertEqual(view(data).target({"text": "Play"}), (50, 30))

    def test_ambiguous_targets_rejected(self):
        with self.assertRaisesRegex(h.GateError, "ambiguous"):
            view(xml(children=node() + node())).target({"text": "Play"})

    def test_disabled_target_and_disabled_outer_ancestor_rejected(self):
        for data in (xml(enabled=False), xml(children=node(text="", clickable=False, enabled=False, children=node()))):
            with self.subTest(data=data), self.assertRaisesRegex(h.GateError, "disabled"):
                view(data).target({"text": "Play"})

    def test_offscreen_and_zero_sized_bounds_rejected(self):
        for value in ("[0,0][0,0]", "[-5,0][100,60]", "[0,0][321,60]"):
            with self.subTest(value=value), self.assertRaisesRegex(h.GateError, "bounds"):
                view(xml(bounds=value)).target({"text": "Play"})

    def test_wrong_xml_package_rejected(self):
        with self.assertRaisesRegex(h.GateError, "wrong package"):
            view(xml(package="com.example.other"))

    def test_wrong_foreground_rejected(self):
        with self.assertRaisesRegex(h.GateError, "foreground"):
            view(displays=DISPLAY.replace(P, "com.example.other"))

    def test_owned_popup_foreground_is_resolved_by_exact_inventory_identity(self):
        popup = f"  Window #1 Window{{def u0 Pop-Up Window}}:\n package={P}\n isOnScreen=true\n"
        display = DISPLAY.replace(f"abc u0 {P}/.MainActivity", "def u0 Pop-Up Window")
        self.assertEqual(view(windows=WINDOW + popup, displays=display).target({"text": "Play"}), (50, 30))

    def test_foreign_popup_cannot_borrow_application_focus(self):
        popup = "  Window #1 Window{def u0 Pop-Up Window}:\n package=com.example.other\n isOnScreen=true\n"
        display = DISPLAY.replace(f"abc u0 {P}/.MainActivity", "def u0 Pop-Up Window") + f"mFocusedApp={P}/.MainActivity\n"
        with self.assertRaisesRegex(h.GateError, "foreground"):
            view(windows=WINDOW + popup, displays=display)

    def test_focus_title_cannot_override_foreign_owner(self):
        with self.assertRaisesRegex(h.GateError, "foreground"):
            view(windows=WINDOW.replace(f"package={P}", "package=com.example.other"))

    def test_null_missing_and_conflicting_focus_are_rejected(self):
        for display in (DISPLAY.replace(f"Window{{abc u0 {P}/.MainActivity}}", "null"),
                        DISPLAY.replace(f"abc u0 {P}/.MainActivity", "def u0 Pop-Up Window"),
                        DISPLAY + "mCurrentFocus=Window{def u0 Pop-Up Window}\n"):
            with self.subTest(display=display), self.assertRaisesRegex(h.GateError, "foreground"):
                view(displays=display)

    def test_hidden_focused_window_is_rejected(self):
        with self.assertRaisesRegex(h.GateError, "foreground"):
            view(windows=WINDOW.replace("isOnScreen=true", "isOnScreen=false"))

    def test_duplicate_window_token_is_rejected_even_with_a_different_title(self):
        duplicate = WINDOW.replace("Window #0", "Window #1").replace(f"{P}/.MainActivity", "Pop-Up Window")
        with self.assertRaisesRegex(h.GateError, "ambiguous window inventory"):
            view(windows=WINDOW + duplicate)

    def test_unexpected_visible_overlay_rejected(self):
        extra = "  Window #1 Window{def u0 MaliciousOverlay}:\n package=com.example.other\n isOnScreen=true\n"
        with self.assertRaisesRegex(h.GateError, "overlay"):
            view(windows=WINDOW + extra)

    def test_non_visible_overlay_does_not_block(self):
        extra = "  Window #1 Window{def u0 Hidden}:\n package=com.example.other\n isOnScreen=false\n"
        self.assertEqual(view(windows=WINDOW + extra).target({"text": "Play"}), (50, 30))

    def test_unknown_window_visibility_rejected(self):
        with self.assertRaisesRegex(h.GateError, "visibility"):
            view(windows=WINDOW.replace("isOnScreen=true", "newUnknownFormat=true"))

    def test_taskbar_requires_explicit_allowance(self):
        extra = "  Window #1 Window{def u0 Taskbar}:\n package=com.google.android.apps.nexuslauncher\n isOnScreen=true\n"
        with self.assertRaisesRegex(h.GateError, "overlay"):
            view(windows=WINDOW + extra)
        allowed = [{"package": "com.google.android.apps.nexuslauncher", "title": "Taskbar"}]
        self.assertEqual(h.View(xml(), WINDOW + extra, DISPLAY, P, allowed).size, (320, 640))

    def test_multiple_xml_windows_rejected(self):
        with self.assertRaisesRegex(h.GateError, "ambiguous active"):
            view(("<hierarchy>" + node() + node() + "</hierarchy>").encode())

    def test_visible_postcondition_and_timeout_use_real_expect(self):
        clock = Clock()
        with self.assertRaisesRegex(h.GateError, "postcondition timeout"):
            h.wait_for(FakeAdb(), P, [{"text": "Missing"}], timeout=1, clock=clock, sleep=clock.sleep)
        self.assertIsInstance(h.wait_for(FakeAdb(), P, [{"text": "Play"}]), h.View)

    def test_static_capture_accepts_stable_tree(self):
        before, png, after = h.static_capture(FakeAdb(), P, [{"text": "Play"}])
        self.assertEqual(before.canonical, after.canonical)
        self.assertEqual(png, PNG)

    def test_changing_tree_cannot_be_static_capture(self):
        # Text differs, but both have expected Play label. No custom fake predicate.
        first = view(xml(children=node() + node(text="First", clickable=False)))
        second = view(xml(children=node() + node(text="Second", clickable=False)))
        adb = FakeAdb(views=[first, second] * 20)
        with self.assertRaisesRegex(h.GateError, "static capture"):
            h.static_capture(adb, P, [{"text": "Play"}], timeout=5, clock=Clock())

    def test_late_correct_postcondition_cannot_pass(self):
        clock = Clock()
        adb = FakeAdb()
        def slow_view(*args, **kwargs):
            clock.value += 5
            return view()
        adb.view = slow_view
        with self.assertRaisesRegex(h.GateError, "postcondition timeout"):
            h.wait_for(adb, P, [{"text": "Play"}], timeout=1, clock=clock, sleep=clock.sleep)

    def test_late_identical_capture_cannot_pass(self):
        clock = Clock()
        adb = FakeAdb()
        def slow_screenshot(*args, **kwargs):
            clock.value += 5
            return PNG
        adb.run = slow_screenshot
        with self.assertRaisesRegex(h.GateError, "static capture timeout"):
            h.static_capture(adb, P, [{"text": "Play"}], timeout=2, clock=clock)

    def test_screenshot_dimensions_checked(self):
        adb = FakeAdb()
        adb.png = PNG[:16] + struct.pack(">II", 100, 200) + PNG[24:]
        with self.assertRaisesRegex(h.GateError, "dimensions"):
            h.static_capture(adb, P, [{"text": "Play"}])

    def test_wrong_installed_apk_rejected(self):
        with self.assertRaisesRegex(h.GateError, "installed APK mismatch"):
            h.verify_installed(FakeAdb(), P, "b" * 64)

    def test_split_apk_is_not_silently_accepted(self):
        adb = h.Adb("unused", "fixture-device")
        with patch.object(adb, "shell", return_value="package:/data/app/base.apk\npackage:/data/app/split.apk"):
            with self.assertRaisesRegex(h.GateError, "no splits"):
                adb.installed_hash(P)


class ReceiptAndAggregateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.apk = self.base / "fixture.apk"
        self.apk.write_bytes(b"fixture-apk-not-a-real-device-build")
        self.build_dir = self.base / "build-receipt"
        self.build_dir.mkdir()
        (self.build_dir / "build.log").write_text("fixture build log\n")
        files = [{"path": "app/src/main/fixture", "sha256": "a" * 64}]
        inputs = {"sha256": h.digest(h.canonical(files)), "files": files}
        self.build = {"schema": 1, "kind": "build", "status": "PASS", "exit_code": 0, "inputs_before": inputs, "inputs_after": copy.deepcopy(inputs),
                      "build_type": "debug", "log_sha256": h.sha(self.build_dir / "build.log"),
                      "apk": {"path": str(self.apk), "sha256": h.sha(self.apk), "package": P, "version_code": "4", "version_name": "0.4.0-guide", "certificate_sha256": "c" * 64, "debuggable": True}}
        h.write_json(self.build_dir / "build.json", self.build)
        self.manifest_path = self.base / "manifest.json"
        self.run_dir = self.base / "run"
        self.adb = FakeAdb(h.sha(self.apk))

    def begin(self, review=False):
        h.write_json(self.manifest_path, manifest(review))
        h.begin_run(self.manifest_path, self.build_dir / "build.json", self.run_dir, self.adb)

    def capture(self):
        return h.capture_case(self.run_dir, self.adb, "reader")

    def alter_case(self, function):
        path = self.run_dir / "cases/reader/case.json"
        data = h.read_json(path)
        function(data)
        path.write_text(json.dumps(data))

    def test_complete_observed_corpus_passes_without_visual_claim(self):
        self.begin()
        self.capture()
        report = h.aggregate(self.run_dir)
        self.assertEqual(report["required_cases"], 1)
        self.assertEqual(report["visual_reviews"], [])
        self.assertIn("not an automated accessibility PASS", report["limits"])

    def test_missing_required_case_fails(self):
        self.begin()
        with self.assertRaisesRegex(h.GateError, "required case missing"):
            h.aggregate(self.run_dir)

    def test_duplicate_manifest_ids_rejected(self):
        value = manifest()
        value["cases"].append(copy.deepcopy(value["cases"][0]))
        with self.assertRaisesRegex(h.GateError, "duplicate case"):
            h.validate_manifest(value)

    def test_no_cases_or_unknown_conditions_rejected(self):
        values = [manifest(), manifest(), manifest()]
        values[0]["cases"] = []
        values[1]["conditions"]["clock"] = "UNKNOWN"
        values[2]["cases"][0]["conditions"]["data"] = None
        for value in values:
            with self.subTest(value=value), self.assertRaises(h.GateError):
                h.validate_manifest(value)

    def test_skipped_case_fails(self):
        self.begin()
        self.capture()
        self.alter_case(lambda value: value.update(status="SKIPPED"))
        with self.assertRaisesRegex(h.GateError, "did not pass"):
            h.aggregate(self.run_dir)

    def test_empty_png_fails(self):
        self.begin()
        self.capture()
        (self.run_dir / "cases/reader/screen.png").write_bytes(b"")
        with self.assertRaisesRegex(h.GateError, "empty/changed"):
            h.aggregate(self.run_dir)

    def test_old_artifact_fails_even_when_content_hash_matches(self):
        self.begin()
        self.capture()
        os.utime(self.run_dir / "cases/reader/screen.png", ns=(1, 1))
        with self.assertRaisesRegex(h.GateError, "stale artifact"):
            h.aggregate(self.run_dir)

    def test_case_from_other_run_fails(self):
        self.begin()
        self.capture()
        self.alter_case(lambda value: value.update(run_id="earlier-run"))
        with self.assertRaisesRegex(h.GateError, "identity is stale"):
            h.aggregate(self.run_dir)

    def test_duplicate_result_fails(self):
        self.begin()
        self.capture()
        other = self.run_dir / "cases/duplicate"
        other.mkdir()
        (other / "case.json").write_bytes((self.run_dir / "cases/reader/case.json").read_bytes())
        with self.assertRaisesRegex(h.GateError, "missing or extra"):
            h.aggregate(self.run_dir)

    def test_unknown_observed_required_condition_fails(self):
        self.begin()
        self.capture()
        self.alter_case(lambda value: value["observed"].update(font_scale="UNKNOWN"))
        with self.assertRaisesRegex(h.GateError, "UNKNOWN"):
            h.aggregate(self.run_dir)

    def test_changed_manifest_cannot_bless_old_results(self):
        self.begin()
        self.capture()
        path = self.run_dir / "manifest.json"
        path.write_text(path.read_text() + " ")
        with self.assertRaisesRegex(h.GateError, "manifest changed"):
            h.aggregate(self.run_dir)

    def test_review_is_separate_and_required_only_when_declared(self):
        self.begin(review=True)
        self.capture()
        with self.assertRaisesRegex(h.GateError, "visual review missing"):
            h.aggregate(self.run_dir)
        h.review_case(self.run_dir, "reader", "fixture-reviewer", "ACCEPT", "Fixture only: test acceptance binding")
        self.assertEqual(h.aggregate(self.run_dir)["visual_reviews"], ["reader"])

    def test_optional_explicit_rejection_prevents_pass(self):
        self.begin(review=False)
        self.capture()
        self.assertEqual(h.aggregate(self.run_dir)["status"], "PASS")
        h.review_case(self.run_dir, "reader", "fixture-reviewer", "REJECT", "Fixture: found visible clipping")
        with self.assertRaisesRegex(h.GateError, "visual review not accepted"):
            h.aggregate(self.run_dir)

    def test_changed_runner_version_cannot_continue_or_accept_run(self):
        self.begin()
        self.capture()
        path = self.run_dir / "run.json"
        record = h.read_json(path)
        record["harness_sha256"] = "f" * 64
        path.write_text(json.dumps(record))
        with self.assertRaisesRegex(h.GateError, "harness changed after begin"):
            h.load_run(self.run_dir, self.adb)
        with self.assertRaisesRegex(h.GateError, "harness changed after begin"):
            h.aggregate(self.run_dir)

    def test_failed_action_prevents_later_capture_from_masking_failure(self):
        self.begin()
        with self.assertRaisesRegex(h.GateError, "target not found"):
            h.tap(self.run_dir, self.adb, "reader", {"text": "Absent"}, [{"text": "Play"}])
        self.capture()
        with self.assertRaisesRegex(h.GateError, "failed action"):
            h.aggregate(self.run_dir)
        self.assertFalse(any(call[:2] == ("input", "tap") for call in self.adb.calls))

    def test_successful_tap_records_postcondition(self):
        self.begin()
        result = h.tap(self.run_dir, self.adb, "reader", {"text": "Play"}, [{"text": "Play"}])
        self.assertEqual(result["coordinates"], [50, 30])
        self.capture()
        self.assertEqual(h.aggregate(self.run_dir)["status"], "PASS")

    def test_incomplete_action_after_crash_fails(self):
        self.begin()
        self.capture()
        (self.run_dir / "actions/incomplete").mkdir(parents=True)
        with self.assertRaisesRegex(h.GateError, "incomplete action"):
            h.aggregate(self.run_dir)

    def test_capture_cannot_be_replaced_in_same_run(self):
        self.begin()
        self.capture()
        with self.assertRaisesRegex(h.GateError, "already attempted"):
            self.capture()

    def test_apk_swapped_after_begin_is_detected(self):
        self.begin()
        self.adb.apk_hash = "f" * 64
        with self.assertRaisesRegex(h.GateError, "installed APK mismatch"):
            self.capture()

    def test_build_failure_or_changed_inputs_rejected(self):
        for kind in ("failure", "inputs", "apk"):
            value = copy.deepcopy(self.build)
            if kind == "failure":
                value["exit_code"] = 1
            elif kind == "inputs":
                value["inputs_after"]["files"][0]["sha256"] = "b" * 64
                value["inputs_after"]["sha256"] = h.digest(h.canonical(value["inputs_after"]["files"]))
            else:
                value["apk"]["sha256"] = "f" * 64
            with self.subTest(kind=kind), self.assertRaises(h.GateError):
                h.validate_build(value, self.build_dir / "build.log", self.apk)

    def test_optional_unknown_fact_does_not_claim_known_or_block_irrelevant_case(self):
        self.begin()
        original = self.adb.facts
        self.adb.facts = lambda package: dict(original(package), mobile_data_setting="UNKNOWN")
        result = self.capture()
        self.assertEqual(result["observed"]["mobile_data_setting"], "UNKNOWN")
        self.assertEqual(h.aggregate(self.run_dir)["status"], "PASS")

    def test_required_unknown_fact_stops_capture(self):
        self.begin()
        original = self.adb.facts
        self.adb.facts = lambda package: dict(original(package), timezone="UNKNOWN")
        with self.assertRaisesRegex(h.GateError, "required device fact UNKNOWN"):
            self.capture()
        with self.assertRaisesRegex(h.GateError, "did not pass"):
            h.aggregate(self.run_dir)

    def test_actual_build_function_rejects_failure_changed_inputs_and_old_apk(self):
        for fault in ("failure", "changed", "no-output", "none"):
            with self.subTest(fault=fault):
                repo = self.base / fault
                assets = repo / "app/src/main/assets"
                assets.mkdir(parents=True)
                (assets / "fixture.json").write_text("1")
                apk = repo / "app/build/outputs/apk/debug/app-debug.apk"
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b"old APK must not be accepted")
                def gradle(*args, **kwargs):
                    if fault != "no-output":
                        apk.write_bytes(b"new fixture APK")
                    if fault == "changed":
                        (assets / "fixture.json").write_text("2")
                    return type("Result", (), {"returncode": 1 if fault == "failure" else 0})()
                metadata = {"package": P, "version_code": "4", "version_name": "fixture", "certificate_sha256": "c" * 64, "debuggable": True}
                output = repo / "receipt"
                with patch.object(h, "run_command", return_value=b"fixture-git-state"), patch.object(h.subprocess, "run", side_effect=gradle), patch.object(h, "apk_metadata", return_value=metadata):
                    if fault == "none":
                        self.assertEqual(h.build_receipt(repo, apk, output, "debug", ["assembleDebug"], "aapt", "signer")["status"], "PASS")
                    else:
                        with self.assertRaises(h.GateError):
                            h.build_receipt(repo, apk, output, "debug", ["assembleDebug"], "aapt", "signer")
                        self.assertEqual(h.read_json(output / "build.json")["status"], "FAIL")

    def test_fingerprint_includes_untracked_production_but_excludes_docs(self):
        repo = self.base / "repo"
        (repo / "app/src/main/assets").mkdir(parents=True)
        (repo / "docs").mkdir()
        (repo / "app/build.gradle.kts").write_text("fixture")
        (repo / "app/src/main/assets/day.json").write_text("1")
        before = h.source_fingerprint(repo)
        (repo / "docs/notes.md").write_text("Unrelated research")
        self.assertEqual(h.source_fingerprint(repo), before)
        (repo / "app/src/main/assets/build").mkdir()
        (repo / "app/src/main/assets/build/production.json").write_text("Untracked production asset")
        self.assertNotEqual(h.source_fingerprint(repo)["sha256"], before["sha256"])
        (repo / "app/src/main/assets/day.json").write_text("2")
        self.assertNotEqual(h.source_fingerprint(repo)["sha256"], before["sha256"])


if __name__ == "__main__":
    unittest.main()
