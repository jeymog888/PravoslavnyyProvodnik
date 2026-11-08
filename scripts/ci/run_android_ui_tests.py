#!/usr/bin/env python3
"""Gate one CI UI-suite on observed emulator readiness and preserve failure evidence."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time

HOME_PACKAGE = "com.google.android.apps.nexuslauncher"
HOME_COMPONENT = HOME_PACKAGE + "/.NexusLauncherActivity"
HOME_TITLES = {HOME_COMPONENT, HOME_PACKAGE + "/" + HOME_PACKAGE + ".NexusLauncherActivity"}
ANR_TITLE = "Application Not Responding: " + HOME_PACKAGE
BOOT_WINDOW = ("com.android.settings/com.android.settings.FallbackHome", "1000", "com.android.settings")
DECOR = {("com.android.systemui", name) for name in (
    "StatusBar", "NavigationBar0", "NavigationBar", "ScreenDecorOverlay", "ScreenDecorOverlayBottom",
    "com.android.systemui.wallpapers.ImageWallpaper")}
DECOR.add((HOME_PACKAGE, "Taskbar"))
HEALTH_READS = (
    ("boot", ("getprop", "sys.boot_completed")),
    ("qemu", ("getprop", "ro.kernel.qemu")),
    ("home", ("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")),
    ("power", ("dumpsys", "-t", "2", "power")),
    ("windows", ("dumpsys", "-t", "2", "window", "windows")),
    ("displays", ("dumpsys", "-t", "2", "window", "displays")),
    ("policy", ("dumpsys", "-t", "2", "window", "policy")),
)


def readiness(data):
    """API 35 CI-image format only. Missing/ambiguous evidence never grants readiness."""
    try:
        def unique(pattern, text):
            values = re.findall(pattern, text, re.M)
            if len(values) != 1:
                raise ValueError("missing/ambiguous field: " + pattern)
            return values[0]

        if any(not isinstance(data.get(key), str) for key, _ in HEALTH_READS):
            raise ValueError("health read failed")
        home_lines = data["home"].strip().splitlines()
        home = home_lines[-1] if home_lines else ""
        if len(home_lines) > 2 or (len(home_lines) == 2 and not re.fullmatch(
                r"priority=-?\d+ preferredOrder=-?\d+ match=0x[0-9a-f]+ specificIndex=-?\d+ isDefault=(?:true|false)", home_lines[0].strip())):
            raise ValueError("unknown HOME resolver format")
        if home != HOME_COMPONENT:
            return {"verdict": "NOT_READY", "reason": "HOME resolver is not the known CI launcher"}
        if data["boot"].strip() != "1" or data["qemu"].strip() != "1":
            return {"verdict": "NOT_READY", "reason": "boot/emulator identity not ready"}
        power = data["power"]
        if (unique(r"^\s*mWakefulness=(\w+)\s*$", power) != "Awake" or
                unique(r"^\s*mWakefulnessChanging=(true|false)\s*$", power) != "false" or
                unique(r"^\s*mHalInteractiveModeEnabled=(true|false)\s*$", power) != "true"):
            return {"verdict": "NOT_READY", "reason": "screen is not stably awake and interactive"}
        keyguard = unique(r"^    KeyguardServiceDelegate\n((?:      .*\n)+)", data["policy"])
        if unique(r"^      showing=(true|false)\s*$", keyguard) != "false":
            return {"verdict": "NOT_READY", "reason": "keyguard showing"}
        focus_lines = re.findall(r"^\s*mCurrentFocus=(.+)$", data["windows"] + "\n" + data["displays"], re.M)
        focus = [re.fullmatch(r"Window\{(\S+) u(\d+) (.+)\}", value.strip()) for value in focus_lines]
        if not focus or not all(focus) or len({match.groups() for match in focus}) != 1:
            raise ValueError("missing/ambiguous current focus")
        identity = focus[0].groups()
        blocks = re.split(r"(?=^  Window #\d+ Window\{)", data["windows"], flags=re.M)[1:]
        if not blocks:
            raise ValueError("missing window inventory")
        inventory = {}
        for block in blocks:
            header = unique(r"^  Window #\d+ Window\{(\S+) u(\d+) (.+)\}:$", block)
            if header[:2] in inventory:
                raise ValueError("duplicate window identity")
            owner_line = unique(r"^[ \t]+mOwnerUid=[^\r\n]*$", block)
            owner = re.fullmatch(r"[ \t]+mOwnerUid=(\d+) showForAllUsers=(?:true|false) package=([\w.]+) appop=\S+[ \t]*", owner_line)
            visible_line = unique(r"^[ \t]+isOnScreen=[^\r\n]*$", block)
            visible_match = re.fullmatch(r"[ \t]+isOnScreen=(true|false)[ \t]*", visible_line)
            if owner is None or visible_match is None:
                raise ValueError("unknown window owner/visibility format")
            owner = owner.groups()
            visible = visible_match[1] == "true"
            inventory[header[:2]] = (header[2], owner[0], owner[1], visible)
        focused = inventory.get(identity[:2])
        if focused is None or focused[0] != identity[2] or not focused[3]:
            raise ValueError("focus absent/not visible in inventory")
        known_anr = focused[:3] == (ANR_TITLE, "1000", "android")
        boot_transition = focused[:3] == BOOT_WINDOW
        for title, uid, package, visible in inventory.values():
            if not visible:
                continue
            allowed = (package, title) in DECOR or (package == HOME_PACKAGE and title in HOME_TITLES)
            if known_anr and (title, uid, package) == (ANR_TITLE, "1000", "android"):
                allowed = True
            if boot_transition and (title, uid, package) == BOOT_WINDOW:
                allowed = True
            if not allowed:
                return {"verdict": "NOT_READY", "reason": "unexpected visible window", "window": [package, title]}
        if boot_transition:
            return {"verdict": "BOOT_TRANSITION", "reason": "visible focused Settings FallbackHome; not normal HOME", "home": home}
        if known_anr:
            return {"verdict": "KNOWN_LAUNCHER_ANR", "reason": "visible focused Android-owned launcher ANR", "home": home}
        if focused[2] == HOME_PACKAGE and focused[0] in HOME_TITLES:
            return {"verdict": "READY", "reason": "awake unlocked normal HOME with known visible windows", "home": home}
        return {"verdict": "NOT_READY", "reason": "normal HOME does not own focus"}
    except (ValueError, IndexError, TypeError) as error:
        return {"verdict": "UNKNOWN", "reason": str(error)}


def prepare(args, read, snapshot, result, clock=None):
    clock = clock or time.monotonic
    # Original evidence is immutable and captured before the only optional recovery.
    original = snapshot("preflight")
    state = readiness(original)
    result["readiness"] = [{"phase": "preflight", **state}]
    deadline = clock() + 20
    allow_boot_transition = state["verdict"] == "BOOT_TRANSITION"
    if state["verdict"] == "KNOWN_LAUNCHER_ANR" and args.recover_known_launcher_anr and args.serial == "emulator-5554" and original.get("screen_recorded") is True:
        result["recovery_count"] = 1
        for name, command in (
                ("restart-launcher-stop", ("am", "force-stop", HOME_PACKAGE)),
                ("restart-launcher-start", ("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME", "-n", HOME_COMPONENT))):
            if read(name, "shell", *command, timeout=5, deadline=deadline) is None:
                result["environment_error"] = "launcher preparation command failed"
                return False
        post = readiness(snapshot("post-recovery", health_only=True, with_screen=True, deadline=deadline))
        result["readiness"].append({"phase": "post-recovery", **post})
        if post["verdict"] != "READY":
            result["environment_error"] = "launcher restart did not produce observed readiness"
            return False
    elif state["verdict"] not in ("READY", "BOOT_TRANSITION"):
        result["environment_error"] = "original preflight is not ready; no authorized known recovery"
        return False
    # Two fresh independent observations; no UI test retry or post-start recovery.
    consecutive = 0
    index = 0
    while clock() < deadline:
        index += 1
        state = readiness(snapshot("readiness-" + str(index), health_only=True, deadline=deadline))
        result["readiness"].append({"phase": "readiness-" + str(index), **state})
        if clock() >= deadline:
            break
        if state["verdict"] == "READY":
            consecutive += 1
            if consecutive == 2:
                return True
        elif allow_boot_transition and state["verdict"] == "BOOT_TRANSITION":
            consecutive = 0
            time.sleep(min(0.25, max(0, deadline - clock())))
        else:
            result["environment_error"] = "post-preparation environment is not ready"
            return False
    result["environment_error"] = "readiness preparation budget exhausted"
    return False


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--recover-known-launcher-anr", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    if not args.serial or args.serial.startswith("-") or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", args.run_id):
        parser.error("explicit serial and safe run-id are required")
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("test command required after --")
    args.out.mkdir(parents=True, exist_ok=False)
    journal = []

    def read(name, *adb_args, timeout=5, deadline=None):
        record = {"name": name, "argv": [args.adb, "-s", args.serial, *adb_args],
                  "started_ns": time.time_ns(), "started_monotonic_ns": time.monotonic_ns()}
        data = None
        try:
            if deadline is not None:
                timeout = min(timeout, deadline - time.monotonic())
            if timeout <= 0:
                raise TimeoutError("preparation deadline exhausted")
            completed = subprocess.run(record["argv"], capture_output=True, timeout=timeout)
            (args.out / (name + ".stdout")).write_bytes(completed.stdout)
            (args.out / (name + ".stderr")).write_bytes(completed.stderr)
            record.update(exit_code=completed.returncode, stdout_bytes=len(completed.stdout), stderr_bytes=len(completed.stderr))
            if completed.returncode == 0:
                data = completed.stdout.decode(errors="replace")
        except (OSError, subprocess.TimeoutExpired, TimeoutError) as error:
            record["error"] = str(error)
        record["finished_monotonic_ns"] = time.monotonic_ns()
        journal.append(record)
        (args.out / "commands.json").write_text(json.dumps(journal, indent=2) + "\n")
        return data

    def snapshot(phase, health_only=False, with_screen=False, deadline=None):
        data = {name: read(phase + "-" + name, "shell", *tail, deadline=deadline) for name, tail in HEALTH_READS}
        if not health_only:
            for name, tail in (
                ("sdk", ("getprop", "ro.build.version.sdk")),
                ("fingerprint", ("getprop", "ro.build.fingerprint")),
                ("screen-off-timeout", ("settings", "get", "system", "screen_off_timeout")),
                ("stay-on", ("settings", "get", "global", "stay_on_while_plugged_in")),
                ("show-ime", ("settings", "get", "secure", "show_ime_with_hard_keyboard")),
                ("activity", ("dumpsys", "-t", "2", "activity", "activities")),
                ("input-method", ("dumpsys", "-t", "2", "input_method")),
            ):
                read(phase + "-" + name, "shell", *tail, deadline=deadline)
        if not health_only or with_screen:
            read(phase + "-screen.png", "exec-out", "screencap", "-p", deadline=deadline)
            source = args.out / (phase + "-screen.png.stdout")
            if source.exists() and source.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                source.rename(args.out / (phase + "-screen.png"))
                data["screen_recorded"] = True
        return data

    def best_effort(label, operation):
        try:
            operation()
        except Exception as error:
            print(f"CI diagnostics {label} failed: {error}", file=sys.stderr, flush=True)

    result = {"serial": args.serial, "run_id": args.run_id, "command": command,
              "started_ns": time.time_ns(), "tests_started": False, "test_exit_code": None,
              "recovery_count": 0, "environment": "NOT_READY"}
    exit_code = 3
    mirror = "/data/local/tmp/feoria-ci-diagnostics/" + args.run_id
    try:
        if prepare(args, read, snapshot, result):
            # mkdir without -p for this run prevents reusing/overwriting another run's evidence.
            created = read("prepare-mirror", "shell", "mkdir -p /data/local/tmp/feoria-ci-diagnostics && mkdir " + mirror)
            if created is None:
                result["environment_error"] = "could not create a unique shell-owned evidence directory"
            else:
                result["environment"] = "READY"
                result["test_command_attempted"] = True
                exit_code = subprocess.run(command).returncode
                result["tests_started"] = True
                result["test_exit_code"] = exit_code
    except Exception as error:
        result["wrapper_error"] = str(error)
    finally:
        # Before emulator-runner stops the device; late diagnostics cannot replace the test exit.
        best_effort("after-suite", lambda: snapshot("after-suite"))
        best_effort("pull-first-failure-mirror", lambda: read("pull-first-failure-mirror", "pull", mirror,
             str(args.out / "first-failure-mirror"), timeout=20))
        best_effort("pull-first-failure", lambda: read("pull-first-failure", "pull",
             "/sdcard/Android/data/ru.liturgyguide/files/ci-diagnostics/" + args.run_id,
             str(args.out / "first-failure"), timeout=20))
        result["finished_ns"] = time.time_ns()
        best_effort("suite-result", lambda: (args.out / "suite-result.json").write_text(json.dumps(result, indent=2) + "\n"))
    return exit_code if exit_code >= 0 else 128 - exit_code


if __name__ == "__main__":
    raise SystemExit(main())
