#!/usr/bin/env python3
"""Small, fail-closed native evidence runner. XML success is never a visual verdict."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

SCHEMA = 1
UNKNOWN = "UNKNOWN"
IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$")
PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$")
REQUIRED_FACTS = ("serial", "sdk", "fingerprint", "wm_size", "density", "font_scale", "locale", "timezone", "device_epoch_seconds")
SAFE_DECOR = {( "com.android.systemui", name) for name in (
    "StatusBar", "NavigationBar", "NavigationBar0", "ScreenDecorOverlay", "ScreenDecorOverlayBottom")}


class GateError(RuntimeError):
    pass


def require(ok, reason):
    if not ok:
        raise GateError(reason)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def read_json(path):
    # Duplicate object keys are rejected instead of silently taking the last one.
    def pairs(items):
        out = {}
        for key, value in items:
            require(key not in out, f"duplicate JSON key: {key}")
            out[key] = value
        return out
    return json.loads(Path(path).read_text(), object_pairs_hook=pairs)


def write_json(path, value):
    path = Path(path)
    require(not path.exists(), f"artifact already exists: {path}")
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def known(value):
    if isinstance(value, dict):
        return bool(value) and all(known(v) for v in value.values())
    if isinstance(value, list):
        return bool(value) and all(known(v) for v in value)
    return value is not None and str(value).strip().upper() not in ("", "UNKNOWN", "NULL", "NONE")


def run_command(args, *, cwd=None, timeout=30):
    try:
        result = subprocess.run([str(a) for a in args], cwd=cwd, capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise GateError(f"command failed: {args[0]}: {error}") from error
    require(result.returncode == 0, f"command exit {result.returncode}: {args[0]}: {result.stderr.decode(errors='replace')[-800:]}")
    return result.stdout


def source_fingerprint(repo, extra_inputs=()):
    """Actual inputs, including untracked production files; no documentation/evidence."""
    repo = Path(repo).resolve()
    roots = [repo / x for x in ("gradle", "buildSrc", "build-logic", "app/src/main", "app/src/debug", "app/src/release")]
    files = set()
    for root in roots:
        if root.exists():
            for path in root.rglob("*"):
                generated = root.name in ("buildSrc", "build-logic") and any(p in ("build", ".gradle", ".git") for p in path.relative_to(root).parts)
                if path.is_file() and not generated:
                    files.add(path)
    for base in (repo, repo / "app"):
        for path in base.iterdir():
            if path.is_file() and (path.suffix in (".gradle", ".kts", ".properties", ".pro", ".toml") or path.name in ("gradlew", "gradlew.bat")):
                files.add(path)
    entries = []
    for path in sorted(files):
        require(not path.is_symlink(), f"symlink build input not supported: {path}")
        entries.append({"path": path.relative_to(repo).as_posix(), "sha256": sha(path)})
    for index, path in enumerate(extra_inputs):
        path = Path(path).resolve()
        require(path.is_file(), f"missing external build input: {path}")
        entries.append({"path": f"external/{index}/{path.name}", "sha256": sha(path)})
    require(entries, "empty build inputs")
    return {"sha256": digest(canonical(entries)), "files": entries}


def apk_metadata(apk, aapt, apksigner):
    badging = run_command([aapt, "dump", "badging", apk]).decode()
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(match, "APK package/version metadata unavailable")
    cert = run_command([apksigner, "verify", "--print-certs", apk]).decode()
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", cert, re.M)
    require(len(certs) == 1, "expected exactly one APK signer")
    return {"package": match[1], "version_code": match[2], "version_name": match[3],
            "certificate_sha256": certs[0].lower(), "debuggable": "application-debuggable" in badging}


def build_receipt(repo, apk, output, build_type, gradle_args, aapt, apksigner, extra_inputs=()):
    repo, apk, output = Path(repo).resolve(), Path(apk).resolve(), Path(output).resolve()
    require(build_type in ("debug", "release"), "unsupported build type")
    require(apk == repo / "app/build/outputs/apk" / build_type / f"app-{build_type}.apk", "unexpected APK output path")
    required_task = f"assemble{build_type.title()}"
    require(any(a in (required_task, f":app:{required_task}") for a in gradle_args), "matching assemble task is required")
    require(not output.exists(), "build receipt directory must be new")
    # External init scripts affect the APK and must be explicit fingerprint inputs.
    for index, arg in enumerate(gradle_args):
        require(not arg.startswith(("--init-script=", "-I")) or arg == "-I", "use separate --init-script PATH arguments")
        if arg in ("--init-script", "-I"):
            require(index + 1 < len(gradle_args), "init script path missing")
            path = Path(gradle_args[index + 1])
            if not path.is_absolute():
                path = repo / path
            extra_inputs = (*extra_inputs, path)
    output.mkdir(parents=True)
    before = source_fingerprint(repo, extra_inputs)
    source = {"commit": run_command(["git", "rev-parse", "HEAD"], cwd=repo).decode().strip(),
              "tree": run_command(["git", "rev-parse", "HEAD^{tree}"], cwd=repo).decode().strip(),
              "dirty": bool(run_command(["git", "status", "--porcelain"], cwd=repo).strip())}
    if apk.exists():
        apk.unlink()  # A successful unrelated task must never bless an old APK.
    command = [str(repo / "gradlew"), *gradle_args]
    started = time.time_ns()
    error = None
    with (output / "build.log").open("wb") as log:
        try:
            result = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
            exit_code = result.returncode
        except (OSError, subprocess.TimeoutExpired) as exc:
            exit_code, error = -1, str(exc)
    after = source_fingerprint(repo, extra_inputs)
    receipt = {"schema": SCHEMA, "kind": "build", "status": "FAIL", "started_ns": started,
               "finished_ns": time.time_ns(), "repo": str(repo), "command": command, "exit_code": exit_code,
               "source": source, "inputs_before": before, "inputs_after": after, "error": error,
               "build_type": build_type, "log_sha256": sha(output / "build.log"), "harness_sha256": sha(__file__)}
    try:
        require(exit_code == 0, "Gradle did not complete successfully")
        require(before["sha256"] == after["sha256"], "build inputs changed during Gradle")
        require(apk.is_file() and apk.stat().st_size > 0, "build produced no APK")
        receipt["apk"] = {"path": str(apk), "sha256": sha(apk), **apk_metadata(apk, aapt, apksigner)}
        require(receipt["apk"]["debuggable"] == (build_type == "debug"), "APK debug flag disagrees with build type")
        receipt["status"] = "PASS"
    except GateError as exc:
        receipt["error"] = str(exc)
    write_json(output / "build.json", receipt)
    require(receipt["status"] == "PASS", receipt["error"])
    return receipt


def validate_build(receipt, log_path, apk_path):
    require(receipt.get("schema") == SCHEMA and receipt.get("kind") == "build" and receipt.get("status") == "PASS", "unaccepted build receipt")
    require(receipt.get("exit_code") == 0, "build exit was not successful")
    before, after = receipt.get("inputs_before", {}), receipt.get("inputs_after", {})
    for inputs in (before, after):
        require(inputs.get("files") and inputs.get("sha256") == digest(canonical(inputs["files"])), "invalid source fingerprint")
    require(before["sha256"] == after["sha256"], "build inputs changed")
    require(Path(log_path).is_file() and sha(log_path) == receipt.get("log_sha256"), "build log missing/changed")
    require(Path(apk_path).is_file() and sha(apk_path) == receipt.get("apk", {}).get("sha256"), "APK differs from build receipt")


def validate_selector(selector, allow_ancestor=True):
    require(isinstance(selector, dict) and selector, "empty selector")
    allowed = {"text", "content-desc", "resource-id", "class", "enabled", "selected", "checked", "clickable"}
    require(set(selector) <= allowed | ({"ancestor"} if allow_ancestor else set()), "unsupported selector field")
    require(any(key in selector for key in ("text", "content-desc", "resource-id", "class")), "selector needs a label, ID or class")
    for key, value in selector.items():
        if key == "ancestor":
            validate_selector(value, allow_ancestor=False)
            continue
        require(isinstance(value, (str, bool)) and str(value) != "", f"invalid selector {key}")


def validate_expected(expected):
    require(isinstance(expected, list) and expected, "postcondition must contain at least one selector")
    for selector in expected:
        validate_selector(selector)


def validate_manifest(manifest):
    require(manifest.get("schema") == SCHEMA, "unsupported manifest schema")
    require(PACKAGE.fullmatch(manifest.get("package", "")), "invalid package")
    require(manifest.get("build_type") in ("debug", "release"), "build type required")
    require(known(manifest.get("conditions")), "required run conditions contain UNKNOWN/empty value")
    require(isinstance(manifest.get("cases"), list) and manifest["cases"], "manifest has no cases")
    ids = set()
    for case in manifest["cases"]:
        case_id = case.get("id", "")
        require(IDENTIFIER.fullmatch(case_id), "invalid case ID")
        require(case_id not in ids, f"duplicate case ID: {case_id}")
        ids.add(case_id)
        require(case.get("mode") == "static", "only static corpus capture is supported; dynamic evidence must remain separate")
        validate_expected(case.get("expected"))
        require(known(case.get("conditions")), f"UNKNOWN/empty required case conditions: {case_id}")
        require(isinstance(case.get("human_review_required"), bool), "declare human_review_required explicitly")
        require(PACKAGE.fullmatch(case.get("window_package", manifest["package"])), "invalid window package")
        for window in case.get("allowed_windows", []):
            require(set(window) == {"package", "title"} and PACKAGE.fullmatch(window["package"]) and known(window["title"]), "invalid allowed window")
        for key, value in case.get("observed_requirements", {}).items():
            require(known(key) and known(value), "UNKNOWN observed requirement")
    return manifest


def bounds(node, size):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    require(match, "missing/invalid node bounds")
    left, top, right, bottom = map(int, match.groups())
    require(0 <= left < right <= size[0] and 0 <= top < bottom <= size[1], "node bounds are not visible inside display")
    return left, top, right, bottom


def parse_windows(windows, displays, package, allowed_windows=()):
    # Popup titles need not contain a package. Resolve the focused window's exact identity
    # through the OS inventory instead of trusting the title or a background focused app.
    raw_focus = re.findall(r"mCurrentFocus=([^\n]+)", displays + "\n" + windows)
    focus = [re.fullmatch(r"Window\{(\S+) u(\d+) (.+?)\}", value.strip()) for value in raw_focus]
    require(focus and all(focus), "unexpected/unknown foreground package")
    identities = {match.groups() for match in focus}
    require(len(identities) == 1, "ambiguous foreground window")
    focused = next(iter(identities))
    current_size = re.findall(r"\bcur=(\d+)x(\d+)\b", displays)
    require(len(current_size) == 1, "display geometry is ambiguous or unavailable")
    size = tuple(map(int, current_size[0]))
    allow = SAFE_DECOR | {(w["package"], w["title"]) for w in allowed_windows}
    blocks = re.split(r"(?=^  Window #\d+ Window\{)", windows, flags=re.M)[1:]
    require(blocks, "window inventory unavailable")
    inventory = {}
    tokens = set()
    for block in blocks:
        # This sibling section repeats Window details, but is not another inventory entry.
        block = re.split(r"^  Hiding System Alert Windows:[ \t]*\r?$", block, maxsplit=1, flags=re.M)[0]
        header = re.search(r"^  Window #\d+ Window\{(\S+) u(\d+) (.+?)\}:", block)
        owner_lines = re.findall(r"^[ \t]+mOwnerUid=[^\r\n]*", block, flags=re.M)
        require(header and len(owner_lines) == 1, "unknown or ambiguous window owner format")
        owner = re.fullmatch(
            r"[ \t]+mOwnerUid=\d+[ \t]+showForAllUsers=(?:true|false)[ \t]+"
            r"package=([\w.]+)[ \t]+appop=\S+[ \t]*", owner_lines[0])
        require(owner, "unknown window owner format")
        identity = header.groups()
        require(identity[:2] not in tokens, "ambiguous window inventory identity")
        tokens.add(identity[:2])
        onscreen_lines = re.findall(r"^[ \t]+isOnScreen=[^\r\n]*", block, flags=re.M)
        require(len(onscreen_lines) == 1, "window visibility unavailable or ambiguous")
        onscreen = re.fullmatch(r"[ \t]+isOnScreen=(true|false)[ \t]*", onscreen_lines[0])
        require(onscreen, "unknown window visibility format")
        inventory[identity] = (owner[1], onscreen[1] == "true")
    require(inventory.get(focused) == (package, True), "unexpected/unknown foreground package")
    visible = []
    for (_, _, title), (owner, onscreen) in inventory.items():
        if not onscreen:
            continue
        identity = (owner, title)
        require(owner == package or identity in allow, f"unexpected visible overlay: {identity}")
        visible.append(identity)
    return size, sorted(visible)


class View:
    def __init__(self, xml, windows, displays, package, allowed_windows=()):
        self.xml, self.windows, self.displays, self.package = xml, windows, displays, package
        self.size, self.visible_windows = parse_windows(windows, displays, package, allowed_windows)
        try:
            self.root = ET.fromstring(xml)
        except ET.ParseError as error:
            raise GateError("invalid UI XML") from error
        roots = list(self.root)
        require(self.root.tag == "hierarchy" and len(roots) == 1 and roots[0].tag == "node", "ambiguous active XML window")
        self.nodes = list(self.root.iter("node"))
        require(self.nodes and all(n.get("package") == package for n in self.nodes), "XML contains wrong package")
        self.parents = {child: parent for parent in self.root.iter() for child in parent}
        # Sort attributes only. Text, state, bounds, ordering and hierarchy are retained.
        def tree(node):
            return [node.tag, sorted(node.attrib.items()), [tree(child) for child in node]]
        self.canonical = canonical({"tree": tree(self.root), "size": self.size, "windows": self.visible_windows})

    def matching(self, selector):
        validate_selector(selector)
        def matches(node, fields):
            return all(node.get(key) == (str(value).lower() if isinstance(value, bool) else value)
                       for key, value in fields.items() if key != "ancestor")
        def matches_ancestor(node):
            if "ancestor" not in selector:
                return True
            current = self.parents.get(node)
            while current is not None and current.tag == "node" and current.get("package") == self.package:
                if matches(current, selector["ancestor"]):
                    return True
                current = self.parents.get(current)
            return False
        return [node for node in self.nodes if matches(node, selector) and matches_ancestor(node)]

    def expect(self, expected):
        validate_expected(expected)
        for selector in expected:
            matches = self.matching(selector)
            require(matches, f"postcondition absent: {selector}")
            # A matching offscreen label does not satisfy a visible postcondition.
            require(any(self._visible(node) for node in matches), f"postcondition outside display: {selector}")
        return self

    def _visible(self, node):
        try:
            bounds(node, self.size)
            return True
        except GateError:
            return False

    def target(self, selector):
        candidates = self.matching(selector)
        require(candidates, f"target not found: {selector}")
        targets = set()
        for node in candidates:
            bounds(node, self.size)
            current, target = node, None
            while current.tag == "node":
                require(current.get("package") == self.package, "target crossed package/window")
                require(current.get("enabled") == "true", "target/ancestor disabled")
                if target is None and current.get("clickable") == "true":
                    bounds(current, self.size)
                    target = current
                current = self.parents[current]
            require(target is not None, "target has no clickable ancestor in active window")
            targets.add(target)
        require(len(targets) == 1, f"ambiguous clickable targets: {len(targets)}")
        left, top, right, bottom = bounds(next(iter(targets)), self.size)
        return (left + right) // 2, (top + bottom) // 2


class Adb:
    def __init__(self, executable, serial):
        require(known(serial) and not serial.startswith("-"), "explicit device serial is required")
        self.executable, self.serial = str(executable), serial

    def run(self, *args, timeout=30):
        return run_command([self.executable, "-s", self.serial, *args], timeout=timeout)

    def shell(self, *args, timeout=30):
        return self.run("shell", *args, timeout=timeout).decode(errors="strict").strip()

    def installed_hash(self, package):
        lines = self.shell("pm", "path", package).splitlines()
        require(len(lines) == 1 and lines[0].startswith("package:/") and lines[0].endswith("/base.apk"), "expected one installed base.apk, no splits")
        path = lines[0][len("package:"):]
        require(re.fullmatch(r"/[A-Za-z0-9_./=+~-]+/base\.apk", path), "unexpected installed APK path")
        return digest(self.run("exec-out", "cat", path, timeout=60))

    def view(self, package, allowed_windows=(), timeout=30, clock=time.monotonic):
        # One operation budget covers every device call, not one fresh timeout per call.
        deadline = clock() + timeout
        remote = f"/data/local/tmp/feoria-harness-{uuid.uuid4().hex}.xml"
        try:
            self.shell("uiautomator", "dump", remote, timeout=remaining(deadline, clock))
            xml = self.run("exec-out", "cat", remote, timeout=remaining(deadline, clock))
            windows = self.shell("dumpsys", "window", "windows", timeout=remaining(deadline, clock))
            displays = self.shell("dumpsys", "window", "displays", timeout=remaining(deadline, clock))
            remaining(deadline, clock)
            result = View(xml, windows, displays, package, allowed_windows)
        finally:
            # Cleanup is best effort only when time remains; it cannot turn a late operation into PASS.
            budget = deadline - clock()
            if budget > 0:
                try:
                    self.shell("rm", "-f", remote, timeout=min(2, budget))
                except GateError:
                    pass
        remaining(deadline, clock)
        return result

    def facts(self, package):
        def prop(name):
            return self.shell("getprop", name) or UNKNOWN
        wm_size, density = self.shell("wm", "size"), self.shell("wm", "density")
        package_dump = self.shell("dumpsys", "package", package)
        permissions = dict(re.findall(r"(android\.permission\.[A-Z_]+): granted=(true|false)", package_dump))
        return {"serial": self.serial, "sdk": prop("ro.build.version.sdk"), "fingerprint": prop("ro.build.fingerprint"),
                "wm_size": wm_size or UNKNOWN, "density": density or UNKNOWN,
                "font_scale": self.shell("settings", "get", "system", "font_scale"),
                "locale": prop("persist.sys.locale") if prop("persist.sys.locale") != UNKNOWN else prop("ro.product.locale"),
                "timezone": prop("persist.sys.timezone"), "device_epoch_seconds": self.shell("date", "+%s"),
                "wifi_setting": self.shell("settings", "get", "global", "wifi_on"),
                "mobile_data_setting": self.shell("settings", "get", "global", "mobile_data"),
                "location_mode": self.shell("settings", "get", "secure", "location_mode"),
                "permissions": permissions or {"observed": UNKNOWN},
                "version_code": (re.search(r"\bversionCode=(\d+)", package_dump) or [None, UNKNOWN])[1],
                "version_name": (re.search(r"\bversionName=(\S+)", package_dump) or [None, UNKNOWN])[1]}


def verify_installed(adb, package, expected_sha):
    actual = adb.installed_hash(package)
    require(actual == expected_sha, f"installed APK mismatch: {actual}")
    return actual


def remaining(deadline, clock=time.monotonic, reason="operation deadline exceeded"):
    budget = deadline - clock()
    require(budget > 0, reason)
    return budget


def wait_for(adb, package, expected, allowed_windows=(), timeout=10, clock=time.monotonic, sleep=time.sleep):
    validate_expected(expected)
    require(0 < timeout <= 60, "postcondition timeout must be within 0..60 seconds")
    deadline, last_error = clock() + timeout, None
    while clock() < deadline:
        # Foreground/overlay errors are safety failures, not a reason to tap through.
        view = adb.view(package, allowed_windows, timeout=remaining(deadline, clock, "postcondition timeout"), clock=clock)
        remaining(deadline, clock, "postcondition timeout after device observation")
        try:
            view.expect(expected)
        except GateError as error:
            last_error = str(error)
        else:
            remaining(deadline, clock, "postcondition timeout after evaluation")
            return view
        sleep(min(0.25, max(0, deadline - clock())))
    raise GateError(f"postcondition timeout: {last_error}")


def static_capture(adb, package, expected, allowed_windows=(), timeout=15, clock=time.monotonic):
    require(0 < timeout <= 60, "capture timeout must be within 0..60 seconds")
    deadline = clock() + timeout
    while clock() < deadline:
        before = adb.view(package, allowed_windows, timeout=remaining(deadline, clock, "static capture timeout"), clock=clock).expect(expected)
        png = adb.run("exec-out", "screencap", "-p", timeout=remaining(deadline, clock, "static capture timeout"))
        remaining(deadline, clock, "static capture timeout after screenshot")
        require(png.startswith(b"\x89PNG\r\n\x1a\n") and len(png) > 32, "invalid/empty screenshot")
        require(struct.unpack(">II", png[16:24]) == before.size, "screenshot/display dimensions differ")
        after = adb.view(package, allowed_windows, timeout=remaining(deadline, clock, "static capture timeout"), clock=clock).expect(expected)
        remaining(deadline, clock, "static capture timeout after second observation")
        if before.canonical == after.canonical:
            remaining(deadline, clock, "static capture timeout after comparison")
            return before, png, after
    raise GateError("static capture changed before/after; no visual baseline accepted")


def artifact(path, run_dir):
    path, run_dir = Path(path).resolve(), Path(run_dir).resolve()
    require(path.is_relative_to(run_dir) and path.is_file() and path.stat().st_size > 0, "missing/empty/outside artifact")
    return {"path": path.relative_to(run_dir).as_posix(), "sha256": sha(path), "bytes": path.stat().st_size}


def begin_run(manifest_path, build_path, run_dir, adb):
    manifest = validate_manifest(read_json(manifest_path))
    build_path, run_dir = Path(build_path).resolve(), Path(run_dir).resolve()
    build = read_json(build_path)
    apk = Path(build["apk"]["path"])
    validate_build(build, build_path.with_name("build.log"), apk)
    require(manifest["package"] == build["apk"]["package"] and manifest["build_type"] == build["build_type"], "manifest/build identity mismatch")
    require(not run_dir.exists(), "run directory must be new; no artifact reuse")
    run_dir.mkdir(parents=True)
    run_id, started_ns = uuid.uuid4().hex, time.time_ns()
    write_json(run_dir / "manifest.json", manifest)
    shutil.copy2(build_path, run_dir / "build.json")
    shutil.copy2(build_path.with_name("build.log"), run_dir / "build.log")
    install_output = adb.run("install", "-r", apk, timeout=120)
    (run_dir / "install.log").write_bytes(install_output)
    require(b"Success" in install_output, "adb install did not report Success")
    actual = verify_installed(adb, manifest["package"], build["apk"]["sha256"])
    facts = adb.facts(manifest["package"])
    require(facts["version_code"] == build["apk"]["version_code"] and facts["version_name"] == build["apk"]["version_name"], "installed package version mismatch")
    record = {"schema": SCHEMA, "kind": "run", "run_id": run_id, "started_ns": started_ns,
              "manifest_sha256": sha(run_dir / "manifest.json"), "build_sha256": sha(run_dir / "build.json"),
              "installed_sha256": actual, "apk": build["apk"], "serial": adb.serial, "initial_facts": facts,
              "install_log": artifact(run_dir / "install.log", run_dir), "harness_sha256": sha(__file__),
              "limits": "XML proves accessibility-tree observations only; no automatic visual/accessibility verdict"}
    write_json(run_dir / "run.json", record)
    return record


def load_run(run_dir, adb=None):
    run_dir = Path(run_dir).resolve()
    record = read_json(run_dir / "run.json")
    require(record.get("schema") == SCHEMA and record.get("kind") == "run", "invalid run record")
    require(record.get("harness_sha256") == sha(__file__), "harness changed after begin; use the original runner or a new run")
    require(sha(run_dir / "manifest.json") == record["manifest_sha256"], "manifest changed after begin")
    require(sha(run_dir / "build.json") == record["build_sha256"], "build receipt changed after begin")
    manifest = validate_manifest(read_json(run_dir / "manifest.json"))
    build = read_json(run_dir / "build.json")
    require(build.get("status") == "PASS" and build["apk"] == record["apk"] and record["installed_sha256"] == build["apk"]["sha256"], "run/build/install identity mismatch")
    require(sha(run_dir / "build.log") == build["log_sha256"], "build log changed after begin")
    if adb:
        require(adb.serial == record["serial"], "device serial differs from run")
        verify_installed(adb, manifest["package"], record["apk"]["sha256"])
    return run_dir, record, manifest


def case_by_id(manifest, case_id):
    cases = [case for case in manifest["cases"] if case["id"] == case_id]
    require(len(cases) == 1, "case is not in fixed manifest")
    return cases[0]


def tap(run_dir, adb, case_id, selector, expected, timeout=10):
    run_dir, record, manifest = load_run(run_dir, adb)
    case = case_by_id(manifest, case_id)
    package = case.get("window_package", manifest["package"])
    action_dir = run_dir / "actions" / f"{time.time_ns()}-{uuid.uuid4().hex[:8]}"
    action_dir.mkdir(parents=True)
    result = {"run_id": record["run_id"], "case_id": case_id, "apk_sha256": record["apk"]["sha256"], "selector": selector, "expected": expected, "status": "FAIL"}
    try:
        validate_expected(expected)
        before = adb.view(package, case.get("allowed_windows", []))
        (action_dir / "before.xml").write_bytes(before.xml)
        x, y = before.target(selector)
        adb.shell("input", "tap", str(x), str(y))
        after = wait_for(adb, package, expected, case.get("allowed_windows", []), timeout)
        (action_dir / "after.xml").write_bytes(after.xml)
        verify_installed(adb, manifest["package"], record["apk"]["sha256"])
        result.update(status="PASS", coordinates=[x, y])
    except GateError as exc:
        result["error"] = str(exc)
    result["artifacts"] = [artifact(path, run_dir) for path in sorted(action_dir.glob("*.xml"))]
    write_json(action_dir / "action.json", result)
    require(result["status"] == "PASS", result.get("error", "action failed"))
    return result


def capture_case(run_dir, adb, case_id, timeout=15):
    run_dir, record, manifest = load_run(run_dir, adb)
    case = case_by_id(manifest, case_id)
    case_dir = run_dir / "cases" / case_id
    require(not case_dir.exists(), "case was already attempted; use a new run")
    case_dir.mkdir(parents=True)
    result = {"run_id": record["run_id"], "case_id": case_id, "apk_sha256": record["apk"]["sha256"],
              "manifest_sha256": record["manifest_sha256"], "started_ns": time.time_ns(), "status": "FAIL",
              "declared_conditions": case["conditions"], "artifacts": {}, "visual_status": "NOT_REVIEWED"}
    try:
        facts = adb.facts(manifest["package"])
        result["observed"] = facts
        for key in REQUIRED_FACTS:
            require(known(facts.get(key)), f"required device fact UNKNOWN: {key}")
        for key, expected in case.get("observed_requirements", {}).items():
            require(known(facts.get(key)) and facts[key] == expected, f"observed condition mismatch: {key}")
        before, png, after = static_capture(adb, case.get("window_package", manifest["package"]), case["expected"], case.get("allowed_windows", []), timeout)
        for name, data in (("before.xml", before.xml), ("screen.png", png), ("after.xml", after.xml), ("windows.txt", after.windows.encode()), ("displays.txt", after.displays.encode())):
            path = case_dir / name
            path.write_bytes(data)
            result["artifacts"][name] = artifact(path, run_dir)
        verify_installed(adb, manifest["package"], record["apk"]["sha256"])
        result.update(status="PASS", accessibility_tree_sha256=digest(before.canonical), pixels=list(before.size))
    except GateError as exc:
        result["error"] = str(exc)
    result["finished_ns"] = time.time_ns()
    write_json(case_dir / "case.json", result)
    require(result["status"] == "PASS", result.get("error", "capture failed"))
    return result


def validate_artifact(item, run_dir, started_ns):
    path = (run_dir / item["path"]).resolve()
    require(path.is_relative_to(run_dir) and path.is_file() and not path.is_symlink(), "artifact missing/outside run")
    require(path.stat().st_size > 0 and path.stat().st_size == item["bytes"] and sha(path) == item["sha256"], "artifact empty/changed")
    require(path.stat().st_mtime_ns >= started_ns, "stale artifact predates run")


def aggregate(run_dir):
    run_dir, record, manifest = load_run(run_dir)
    results = list((run_dir / "cases").glob("*/case.json"))
    require(all((directory / "case.json").is_file() for directory in (run_dir / "cases").iterdir()) if (run_dir / "cases").exists() else True, "incomplete case attempt")
    required = {case["id"] for case in manifest["cases"]}
    require(len(results) == len(required), "required case missing or extra result")
    seen, reviews = set(), []
    for path in results:
        result = read_json(path)
        case_id = result.get("case_id")
        require(case_id in required and case_id not in seen and path.parent.name == case_id, "duplicate/unexpected case result")
        seen.add(case_id)
        case = case_by_id(manifest, case_id)
        require(result.get("status") == "PASS", f"required case did not pass: {case_id}")
        require(result.get("run_id") == record["run_id"] and result.get("apk_sha256") == record["apk"]["sha256"] and result.get("manifest_sha256") == record["manifest_sha256"], "case identity is stale or mismatched")
        require(record["started_ns"] <= result.get("started_ns", 0) <= result.get("finished_ns", 0), "invalid case timestamps")
        require(result.get("declared_conditions") == case["conditions"], "case required conditions changed")
        require(isinstance(result.get("observed"), dict), "case observations missing")
        for key in REQUIRED_FACTS:
            require(known(result["observed"].get(key)), f"required device fact UNKNOWN: {key}")
        for key, expected in case.get("observed_requirements", {}).items():
            require(result["observed"].get(key) == expected, "recorded observed condition mismatch")
        require(set(result.get("artifacts", {})) == {"before.xml", "after.xml", "screen.png", "windows.txt", "displays.txt"}, "required artifacts absent")
        for item in result["artifacts"].values():
            validate_artifact(item, run_dir, record["started_ns"])
        review_path = path.with_name("review.json")
        if case["human_review_required"]:
            require(review_path.is_file(), f"human visual review missing: {case_id}")
        if review_path.exists():
            review = read_json(review_path)
            require(review.get("run_id") == record["run_id"] and review.get("case_id") == case_id and review.get("screen_sha256") == result["artifacts"]["screen.png"]["sha256"], "visual review identity mismatch")
            require(review.get("decision") == "ACCEPT" and known(review.get("reviewer")) and known(review.get("observations")), "visual review not accepted")
            reviews.append(case_id)
    require(all((directory / "action.json").is_file() for directory in (run_dir / "actions").iterdir()) if (run_dir / "actions").exists() else True, "incomplete action attempt")
    for path in (run_dir / "actions").glob("*/action.json"):
        action = read_json(path)
        require(action.get("run_id") == record["run_id"] and action.get("apk_sha256") == record["apk"]["sha256"] and action.get("case_id") in required, "action identity mismatch")
        require(action.get("status") == "PASS", "run contains failed action")
        for item in action.get("artifacts", []):
            validate_artifact(item, run_dir, record["started_ns"])
    return {"schema": SCHEMA, "run_id": record["run_id"], "apk_sha256": record["apk"]["sha256"], "status": "PASS",
            "required_cases": len(required), "passed_cases": len(seen), "visual_reviews": reviews,
            "limits": "Observed postconditions and stable accessibility trees only. Visual reviews are separate; not an automated accessibility PASS."}


def review_case(run_dir, case_id, reviewer, decision, observations):
    run_dir, record, manifest = load_run(run_dir)
    case_by_id(manifest, case_id)
    result = read_json(run_dir / "cases" / case_id / "case.json")
    require(result["status"] == "PASS", "cannot visually accept failed capture")
    require(known(reviewer) and known(observations) and decision in ("ACCEPT", "REJECT"), "review details required")
    review = {"run_id": record["run_id"], "case_id": case_id, "screen_sha256": result["artifacts"]["screen.png"]["sha256"],
              "reviewer": reviewer, "decision": decision, "observations": observations, "reviewed_ns": time.time_ns()}
    write_json(run_dir / "cases" / case_id / "review.json", review)
    return review


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("build")
    for name in ("repo", "apk", "output", "aapt", "apksigner"):
        build.add_argument("--" + name, required=True)
    build.add_argument("--build-type", choices=("debug", "release"), required=True)
    build.add_argument("--extra-input", action="append", default=[])
    build.add_argument("gradle_args", nargs=argparse.REMAINDER)
    for name in ("begin", "tap", "capture"):
        item = sub.add_parser(name)
        item.add_argument("--adb", required=True)
        item.add_argument("--serial", required=True)
        item.add_argument("--run", required=True)
        if name == "begin":
            item.add_argument("--manifest", required=True)
            item.add_argument("--build", required=True)
        else:
            item.add_argument("--case", required=True)
            item.add_argument("--timeout", type=float, default=15)
        if name == "tap":
            item.add_argument("--selector", required=True, help="JSON selector")
            item.add_argument("--expect", required=True, help="JSON list of visible postconditions")
    finish = sub.add_parser("finish")
    finish.add_argument("--run", required=True)
    review = sub.add_parser("review")
    for name in ("run", "case", "reviewer", "decision", "observations"):
        review.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        if args.command == "build":
            gradle_args = args.gradle_args[1:] if args.gradle_args[:1] == ["--"] else args.gradle_args
            result = build_receipt(args.repo, args.apk, args.output, args.build_type, gradle_args, args.aapt, args.apksigner, args.extra_input)
        elif args.command == "begin":
            result = begin_run(args.manifest, args.build, args.run, Adb(args.adb, args.serial))
        elif args.command == "tap":
            result = tap(args.run, Adb(args.adb, args.serial), args.case, json.loads(args.selector), json.loads(args.expect), args.timeout)
        elif args.command == "capture":
            result = capture_case(args.run, Adb(args.adb, args.serial), args.case, args.timeout)
        elif args.command == "review":
            result = review_case(args.run, args.case, args.reviewer, args.decision, args.observations)
        else:
            result = aggregate(args.run)
            write_json(Path(args.run) / "gate.json", result)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (GateError, OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
