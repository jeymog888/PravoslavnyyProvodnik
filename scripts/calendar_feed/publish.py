#!/usr/bin/env python3
"""Publish only validated day JSON via Git objects, never execute the data branch.

The workflow's checkout owns GitHub authentication. This program never reads or
accepts an Azbyka credential. A non-fast-forward push fails rather than forcing.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys

if __package__:
    from .generate import FeedError, MAX_PACKAGE_BYTES, valid_date, validate_package, write_package
    from .token_state import MAX_ENVELOPE_BYTES, validate_envelope
else:
    from generate import FeedError, MAX_PACKAGE_BYTES, valid_date, validate_package, write_package
    from token_state import MAX_ENVELOPE_BYTES, validate_envelope

BRANCH = "calendar-cache"
MAX_PACKAGES = 1000
KEEP_PAST_DAYS = 400
OID = re.compile(r"[0-9a-f]{40}")
DATE_FILE = re.compile(r"([0-9]{4}-[0-9]{2}-[0-9]{2})\.json")


class Git:
    def __init__(self, cwd: Path | None = None):
        self.cwd = cwd

    def run(self, *args, data=None, allowed=(0,)) -> bytes:
        try:
            result = subprocess.run(["git", *args], input=data, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, cwd=self.cwd, timeout=90, check=False,
                env={**os.environ, "GIT_AUTHOR_NAME": "Feoria calendar", "GIT_AUTHOR_EMAIL": "calendar@users.noreply.github.com",
                     "GIT_COMMITTER_NAME": "Feoria calendar", "GIT_COMMITTER_EMAIL": "calendar@users.noreply.github.com",
                     "GIT_TERMINAL_PROMPT": "0"})
        except (OSError, subprocess.TimeoutExpired):
            raise FeedError("git_unavailable") from None
        if result.returncode not in allowed:
            raise FeedError("git_operation_failed")
        return result.stdout


def object_id(data: bytes) -> str:
    result = data.decode("ascii").strip()
    if not OID.fullmatch(result):
        raise FeedError("invalid_git_object")
    return result


def entries(git: Git, tree: str) -> list[tuple[str, str, str]]:
    result = []
    for line in git.run("ls-tree", "-z", tree).split(b"\0"):
        if not line:
            continue
        metadata, name = line.decode("utf-8").split("\t", 1)
        mode, kind, oid = metadata.split(" ")
        if not OID.fullmatch(oid):
            raise FeedError("invalid_git_object")
        result.append((mode + " " + kind, oid, name))
    if len(result) > MAX_PACKAGES:
        raise FeedError("cache_count_bound")
    return result


def prepare(git: Git, root: Path, now=None) -> int:
    """Copy previously validated packages to staging; no remote mutation."""
    if root.exists() and (root.is_symlink() or any(root.iterdir())):
        raise FeedError("staging_not_empty")
    root.mkdir(parents=True, exist_ok=True)
    days = root / "days"
    days.mkdir()
    remote = git.run("ls-remote", "--exit-code", "--heads", "origin", f"refs/heads/{BRANCH}", allowed=(0, 2)).decode("ascii").strip()
    if not remote:
        (root / ".base").write_text("\n", encoding="ascii")
        return 0
    if len(remote.splitlines()) != 1 or not re.fullmatch(r"[0-9a-f]{40}\s+refs/heads/calendar-cache", remote):
        raise FeedError("invalid_remote_branch")
    git.run("fetch", "--no-tags", "--depth=1", "origin", f"refs/heads/{BRANCH}")
    base = object_id(git.run("rev-parse", "FETCH_HEAD"))
    roots = entries(git, base)
    day_roots = [entry for entry in roots if entry[2] == "days"]
    envelopes = [entry for entry in roots if entry[2] == "token.enc.json"]
    if len(day_roots) != 1 or day_roots[0][0] != "040000 tree" or len(envelopes) > 1 or len(roots) != 1 + len(envelopes):
        raise FeedError("unexpected_cache_tree")
    if envelopes:
        mode, oid, filename = envelopes[0]
        if mode != "100644 blob" or not 0 < int(git.run("cat-file", "-s", oid)) <= MAX_ENVELOPE_BYTES:
            raise FeedError("invalid_token_state_file")
        encrypted = git.run("cat-file", "blob", oid)
        validate_envelope(json.loads(encrypted.decode("ascii")))
        (root / filename).write_bytes(encrypted)
    copied = 0
    cutoff = (now or datetime.now(timezone.utc)).date() - timedelta(days=KEEP_PAST_DAYS)
    for mode, oid, filename in entries(git, day_roots[0][1]):
        matched = DATE_FILE.fullmatch(filename)
        if mode != "100644 blob" or matched is None:
            raise FeedError("unexpected_cache_file")
        requested_date = matched.group(1)
        parsed_date = valid_date(requested_date)
        if not 0 < int(git.run("cat-file", "-s", oid)) <= MAX_PACKAGE_BYTES:
            raise FeedError("cache_file_bound")
        try:
            package = json.loads(git.run("cat-file", "blob", oid).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise FeedError("invalid_cache_json") from None
        validate_package(package, requested_date)
        if parsed_date >= cutoff:
            write_package(days, package)
            copied += 1
    (root / ".base").write_text(base + "\n", encoding="ascii")
    return copied


def publish(git: Git, root: Path) -> bool:
    """One atomic branch update only after every generated package validates."""
    if root.is_symlink() or (root / "days").is_symlink() or (root / ".base").is_symlink():
        raise FeedError("staging_symlink_rejected")
    base_file = root / ".base"
    if base_file.stat().st_size > 41:
        raise FeedError("invalid_base")
    base = base_file.read_text(encoding="ascii").strip()
    if base and not OID.fullmatch(base):
        raise FeedError("invalid_base")
    files = sorted((root / "days").iterdir())
    if not 1 <= len(files) <= MAX_PACKAGES:
        raise FeedError("cache_count_bound")
    blobs = []
    for path in files:
        match = DATE_FILE.fullmatch(path.name)
        if path.is_symlink() or not path.is_file() or match is None or not 0 < path.stat().st_size <= MAX_PACKAGE_BYTES:
            raise FeedError("unexpected_cache_file")
        content = path.read_bytes()
        try:
            package = json.loads(content.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise FeedError("invalid_cache_json") from None
        validate_package(package, match.group(1))
        blobs.append((path.name, content))
    envelope = root / "token.enc.json"
    encrypted = None
    if envelope.is_symlink():
        raise FeedError("staging_symlink_rejected")
    if envelope.exists():
        if not 0 < envelope.stat().st_size <= MAX_ENVELOPE_BYTES:
            raise FeedError("invalid_token_state_size")
        encrypted = envelope.read_bytes()
        validate_envelope(json.loads(encrypted.decode("ascii")))
    # Validation completes before creating any Git objects or updating the remote.
    records = []
    for name, content in blobs:
        oid = object_id(git.run("hash-object", "-w", "--stdin", data=content))
        records.append(f"100644 blob {oid}\t{name}\0")
    days_tree = object_id(git.run("mktree", "-z", data="".join(records).encode("utf-8")))
    root_records = f"040000 tree {days_tree}\tdays\0"
    if encrypted is not None:
        envelope_oid = object_id(git.run("hash-object", "-w", "--stdin", data=encrypted))
        root_records += f"100644 blob {envelope_oid}\ttoken.enc.json\0"
    tree = object_id(git.run("mktree", "-z", data=root_records.encode("ascii")))
    if base and object_id(git.run("rev-parse", base + "^{tree}")) == tree:
        return False
    args = ["commit-tree", tree]
    if base:
        args += ["-p", base]
    commit = object_id(git.run(*args, "-m", "Update verified calendar supplements"))
    git.run("push", "origin", f"{commit}:refs/heads/{BRANCH}")
    return True


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "publish"))
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if os.environ.get("GITHUB_REPOSITORY") != "jeymog888/PravoslavnyyProvodnik" or os.environ.get("GITHUB_REF") != "refs/heads/main":
            raise FeedError("trusted_workflow_required")
        result = prepare(Git(), args.root) if args.operation == "prepare" else publish(Git(), args.root)
        print(f"Calendar cache {args.operation}: {result}")
        return 0
    except FeedError as error:
        print(f"Calendar publication failed: {error}", file=sys.stderr)
        return 1
    except Exception:
        print("Calendar publication failed: unexpected_error", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
