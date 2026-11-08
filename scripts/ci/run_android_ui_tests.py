#!/usr/bin/env python3
"""Read-only environment evidence around one UI-suite; keep its original exit code."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not args.serial or args.serial.startswith("-") or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", args.run_id):
        parser.error("explicit serial and safe run-id are required")
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("test command required after --")
    args.out.mkdir(parents=True, exist_ok=False)
    journal = []

    def read(name, *adb_args, timeout=5):
        record = {"name": name, "argv": [args.adb, "-s", args.serial, *adb_args],
                  "started_ns": time.time_ns(), "started_monotonic_ns": time.monotonic_ns()}
        try:
            result = subprocess.run(record["argv"], capture_output=True, timeout=timeout)
            (args.out / (name + ".stdout")).write_bytes(result.stdout)
            (args.out / (name + ".stderr")).write_bytes(result.stderr)
            record.update(exit_code=result.returncode, stdout_bytes=len(result.stdout), stderr_bytes=len(result.stderr))
        except (OSError, subprocess.TimeoutExpired) as error:
            record["error"] = str(error)
        record["finished_monotonic_ns"] = time.monotonic_ns()
        journal.append(record)
        (args.out / "commands.json").write_text(json.dumps(journal, indent=2) + "\n")

    def snapshot(phase):
        # Observe without waking/unlocking/changing timeouts or retrying the test.
        for name, tail in (
            ("sdk", ("getprop", "ro.build.version.sdk")),
            ("fingerprint", ("getprop", "ro.build.fingerprint")),
            ("screen-off-timeout", ("settings", "get", "system", "screen_off_timeout")),
            ("stay-on", ("settings", "get", "global", "stay_on_while_plugged_in")),
            ("show-ime", ("settings", "get", "secure", "show_ime_with_hard_keyboard")),
            ("power", ("dumpsys", "-t", "2", "power")),
            ("windows", ("dumpsys", "-t", "2", "window", "windows")),
            ("displays", ("dumpsys", "-t", "2", "window", "displays")),
            ("policy", ("dumpsys", "-t", "2", "window", "policy")),
            ("activity", ("dumpsys", "-t", "2", "activity", "activities")),
            ("input-method", ("dumpsys", "-t", "2", "input_method")),
        ):
            read(phase + "-" + name, "shell", *tail)
        read(phase + "-screen.png", "exec-out", "screencap", "-p")
        source = args.out / (phase + "-screen.png.stdout")
        if source.exists() and source.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            source.rename(args.out / (phase + "-screen.png"))

    def best_effort(label, operation):
        try:
            operation()
        except Exception as error:
            print(f"CI diagnostics {label} failed: {error}", file=sys.stderr, flush=True)

    best_effort("preflight", lambda: snapshot("preflight"))
    result = {"serial": args.serial, "run_id": args.run_id, "command": command,
              "started_ns": time.time_ns(), "diagnostics": "observations only, not a readiness PASS or a root-cause fix"}
    exit_code = 2
    try:
        exit_code = subprocess.run(command).returncode
        result["test_exit_code"] = exit_code
    except OSError as error:
        result["launch_error"] = str(error)
    finally:
        # This must run inside emulator-runner's script, before it kills the emulator.
        best_effort("after-suite", lambda: snapshot("after-suite"))
        best_effort("pull-first-failure", lambda: read("pull-first-failure", "pull",
             "/sdcard/Android/data/ru.liturgyguide/files/ci-diagnostics/" + args.run_id,
             str(args.out / "first-failure"), timeout=20))
        result["finished_ns"] = time.time_ns()
        best_effort("suite-result", lambda: (args.out / "suite-result.json").write_text(json.dumps(result, indent=2) + "\n"))
    return exit_code if exit_code >= 0 else 128 - exit_code


if __name__ == "__main__":
    raise SystemExit(main())
