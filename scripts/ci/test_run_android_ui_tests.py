#!/usr/bin/env python3
"""Host-only negative controls. No adb/emulator/Gradle is executed."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ci", Path(__file__).with_name("run_android_ui_tests.py"))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


def window(token, title, owner, uid=10179, visible=True):
    return (f"  Window #0 Window{{{token} u0 {title}}}:\n"
            f"    mOwnerUid={uid} showForAllUsers=false package={owner} appop=NONE\n"
            f"    isOnScreen={str(visible).lower()}\n")


def fixture(anr=False):
    title = ci.HOME_PACKAGE + "/" + ci.HOME_PACKAGE + ".NexusLauncherActivity"
    data = {"boot": "1", "qemu": "1", "home": ci.HOME_COMPONENT,
            "power": "  mWakefulness=Awake\n  mWakefulnessChanging=false\n  mHalInteractiveModeEnabled=true\n",
            "policy": "    KeyguardServiceDelegate\n      showing=false\n      inputRestricted=false\n",
            "windows": window("home", title, ci.HOME_PACKAGE),
            "displays": f"  mCurrentFocus=Window{{home u0 {title}}}\n"}
    if anr:
        data["windows"] += window("anr", ci.ANR_TITLE, "android", 1000)
        data["displays"] = f"  mCurrentFocus=Window{{anr u0 {ci.ANR_TITLE}}}\n"
    return data


def boot_fixture():
    data = fixture()
    data["windows"] = window("boot", ci.BOOT_WINDOW[0], ci.BOOT_WINDOW[2], int(ci.BOOT_WINDOW[1]))
    data["displays"] = "  mCurrentFocus=Window{boot u0 " + ci.BOOT_WINDOW[0] + "}\n"
    return data


class ParserChecks(unittest.TestCase):
    def test_ready(self):
        self.assertEqual(ci.readiness(fixture())["verdict"], "READY")

    def test_fallback_home_is_only_boot_transition(self):
        self.assertEqual(ci.readiness(boot_fixture())["verdict"], "BOOT_TRANSITION")
        data = boot_fixture(); data["windows"] += window("overlay", "Other", "android", 1000)
        self.assertEqual(ci.readiness(data)["verdict"], "NOT_READY")
        data = boot_fixture(); data["windows"] = data["windows"].replace("mOwnerUid=1000 ", "mOwnerUid=1001 ")
        self.assertEqual(ci.readiness(data)["verdict"], "NOT_READY")

    def test_known_anr(self):
        self.assertEqual(ci.readiness(fixture(True))["verdict"], "KNOWN_LAUNCHER_ANR")

    def test_missing_any_required_read_fails(self):
        for key, _ in ci.HEALTH_READS:
            data = fixture(); data[key] = None
            self.assertEqual(ci.readiness(data)["verdict"], "UNKNOWN", key)

    def test_ambiguous_or_null_focus(self):
        for focus in ["  mCurrentFocus=null\n", fixture()["displays"] + "  mCurrentFocus=Window{other u0 other}\n"]:
            data = fixture(); data["displays"] = focus
            self.assertEqual(ci.readiness(data)["verdict"], "UNKNOWN")

    def test_hidden_or_wrong_owner_anr_never_recovers(self):
        for value in ["android.fake", ci.HOME_PACKAGE]:
            data = fixture(True); data["windows"] = data["windows"].replace("package=android ", "package=" + value + " ")
            self.assertNotEqual(ci.readiness(data)["verdict"], "KNOWN_LAUNCHER_ANR")
        data = fixture(True); data["windows"] = data["windows"].replace("mOwnerUid=1000 ", "mOwnerUid=1001 ")
        self.assertNotEqual(ci.readiness(data)["verdict"], "KNOWN_LAUNCHER_ANR")
        data = fixture(True); data["windows"] = data["windows"].rsplit("isOnScreen=true", 1)[0] + "isOnScreen=false\n"
        self.assertEqual(ci.readiness(data)["verdict"], "UNKNOWN")

    def test_other_anr_overlay_and_owner_unknown(self):
        for title in ["Application Not Responding: ru.liturgyguide", "Application Error: " + ci.HOME_PACKAGE, "Permission dialog"]:
            data = fixture(True)
            for key in ["windows", "displays"]: data[key] = data[key].replace(ci.ANR_TITLE, title)
            self.assertEqual(ci.readiness(data)["verdict"], "NOT_READY")
        data = fixture(); data["windows"] += window("overlay", "Overlay", "com.example")
        self.assertEqual(ci.readiness(data)["verdict"], "NOT_READY")

    def test_sleep_keyguard_and_bad_resolver_fail(self):
        for key, value in [("boot", "0"), ("qemu", "0"), ("home", "com.other/.Home"),
                           ("power", fixture()["power"].replace("Awake", "Asleep")),
                           ("policy", fixture()["policy"].replace("showing=false", "showing=true"))]:
            data = fixture(); data[key] = value
            self.assertNotEqual(ci.readiness(data)["verdict"], "READY", key)

    def test_unknown_visibility_or_duplicate_inventory_fails(self):
        for windows in [fixture()["windows"].replace("isOnScreen=true", "isOnScreen=unknown"), fixture()["windows"] * 2,
                        fixture()["windows"] + "    isOnScreen=unknown\n",
                        fixture()["windows"] + "    mOwnerUid=unexpected-format\n"]:
            data = fixture(); data["windows"] = windows
            self.assertEqual(ci.readiness(data)["verdict"], "UNKNOWN")


class WrapperChecks(unittest.TestCase):
    def run_fake(self, states, exit_code=0, recovery=True, failed_command=None, serial="emulator-5554", png=True):
        calls = []
        health_index = -1
        state = None
        health_tails = {tail: name for name, tail in ci.HEALTH_READS}
        def fake_run(argv, **kwargs):
            nonlocal health_index, state
            calls.append(argv)
            if argv == ["test-suite"]:
                return subprocess.CompletedProcess(argv, exit_code)
            self.assertEqual(argv[:3], ["fake-adb", "-s", serial])
            tail = tuple(argv[4:]) if argv[3] == "shell" else ()
            if tail == ci.HEALTH_READS[0][1]:
                health_index += 1
                state = states[min(health_index, len(states) - 1)]
            if tail in health_tails:
                output = state[health_tails[tail]].encode()
            elif argv[3:] == ["exec-out", "screencap", "-p"]:
                output = b"\x89PNG\r\n\x1a\nfixture" if png else b"not a png"
            else:
                output = b""
            return subprocess.CompletedProcess(argv, 1 if failed_command and failed_command in " ".join(argv) else 0, output, b"")
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp) / "evidence"
            argv = ["--adb", "fake-adb", "--serial", serial, "--run-id", "123-1", "--out", str(out)]
            if recovery: argv.append("--recover-known-launcher-anr")
            with patch.object(ci.subprocess, "run", fake_run):
                code = ci.main(argv + ["--", "test-suite"])
            result = json.loads((out / "suite-result.json").read_text())
            journal = json.loads((out / "commands.json").read_text())
            self.assertTrue(journal)
            self.assertTrue(any(x[3:4] == ["pull"] for x in calls))
            return code, result, calls

    def test_boot_transition_to_home_starts_once_without_reset(self):
        code, result, calls = self.run_fake([boot_fixture(), boot_fixture(), fixture()])
        self.assertEqual(code, 0); self.assertEqual(result["recovery_count"], 0)
        self.assertEqual(calls.count(["test-suite"]), 1)
        self.assertFalse(any("force-stop" in call for call in calls))

    def test_stuck_boot_transition_reaches_budget_without_suite(self):
        ticks = iter(range(0, 1000))
        with patch.object(ci.time, "monotonic", lambda: next(ticks)), patch.object(ci.time, "sleep", lambda _: None):
            code, result, calls = self.run_fake([boot_fixture()])
        self.assertEqual(code, 3); self.assertFalse(result["tests_started"])
        self.assertEqual(result["recovery_count"], 0); self.assertNotIn(["test-suite"], calls)
        self.assertIn("budget exhausted", result["environment_error"])

    def test_boot_to_anr_or_unknown_is_not_recovered(self):
        unknown = fixture(); unknown["displays"] = "  mCurrentFocus=null\n"
        for state in [fixture(True), unknown]:
            code, result, calls = self.run_fake([boot_fixture(), state])
            self.assertEqual(code, 3); self.assertFalse(result["tests_started"])
            self.assertEqual(result["recovery_count"], 0)
            self.assertFalse(any("force-stop" in call for call in calls))

    def test_ready_launches_once_and_keeps_nonzero_exit(self):
        code, result, calls = self.run_fake([fixture()], exit_code=17)
        self.assertEqual(code, 17); self.assertEqual(result["test_exit_code"], 17)
        self.assertEqual(calls.count(["test-suite"]), 1); self.assertEqual(result["recovery_count"], 0)

    def test_known_anr_restarts_once_then_launches_once(self):
        code, result, calls = self.run_fake([fixture(True), fixture()])
        self.assertEqual(code, 0); self.assertEqual(result["recovery_count"], 1)
        self.assertEqual(sum("force-stop" in x for x in calls), 1)
        self.assertEqual(calls.count(["test-suite"]), 1)

    def test_repeated_anr_does_not_launch_or_restart_twice(self):
        code, result, calls = self.run_fake([fixture(True)])
        self.assertEqual(code, 3); self.assertFalse(result["tests_started"])
        self.assertIsNone(result["test_exit_code"]); self.assertEqual(sum("force-stop" in x for x in calls), 1)
        self.assertNotIn(["test-suite"], calls)

    def test_unknown_anr_no_recovery_or_suite(self):
        data = fixture(True)
        for key in ["windows", "displays"]: data[key] = data[key].replace(ci.ANR_TITLE, "Application Not Responding: ru.liturgyguide")
        _, result, calls = self.run_fake([data])
        self.assertEqual(result["recovery_count"], 0); self.assertNotIn(["test-suite"], calls)

    def test_recovery_needs_opt_in_device_and_original_png(self):
        for kwargs in [{"recovery": False}, {"serial": "emulator-5556"}, {"png": False}]:
            _, result, calls = self.run_fake([fixture(True), fixture()], **kwargs)
            self.assertEqual(result["recovery_count"], 0); self.assertNotIn(["test-suite"], calls)

    def test_failed_restart_or_existing_mirror_prevents_suite(self):
        for failed in ["force-stop", "am start", "mkdir"]:
            _, result, calls = self.run_fake([fixture(True), fixture()], failed_command=failed)
            self.assertFalse(result["tests_started"]); self.assertNotIn(["test-suite"], calls)

    def test_readiness_lost_before_second_read_prevents_suite(self):
        _, result, calls = self.run_fake([fixture(), fixture(), fixture(True)])
        self.assertFalse(result["tests_started"]); self.assertEqual(result["recovery_count"], 0)
        self.assertNotIn(["test-suite"], calls)


if __name__ == "__main__":
    unittest.main()
