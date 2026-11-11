from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.calendar_feed.generate import FeedError, build_package, write_package
from scripts.calendar_feed.publish import Git, prepare, publish
from scripts.calendar_feed.token_state import load, save
from test_generate import FixtureClient
from test_token_state import example_jwt

NOW = datetime(2026, 11, 11, 10, tzinfo=timezone.utc)


class PublicationTest(unittest.TestCase):
    """All Git objects and refs here are inside fresh temporary directories, never the project."""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.remote, self.repo = self.root / "origin.git", self.root / "work"
        subprocess.run(["git", "init", "--bare", str(self.remote)], check=True, capture_output=True)
        subprocess.run(["git", "init", str(self.repo)], check=True, capture_output=True)
        self.git = Git(self.repo)
        self.git.run("remote", "add", "origin", str(self.remote))
        self.first = build_package(FixtureClient("2026-11-11"), "2026-11-11", NOW)
        self.second = build_package(FixtureClient("2026-11-12"), "2026-11-12", NOW)

    def tearDown(self): self.temporary.cleanup()

    def head(self):
        return self.git.run("ls-remote", "--heads", "origin", "calendar-cache")

    def seed(self):
        staging = self.root / "seed"
        self.assertEqual(prepare(self.git, staging, NOW), 0)
        write_package(staging / "days", self.first)
        self.assertTrue(publish(self.git, staging))
        return staging

    def test_round_trip_publishes_only_days_and_preserves_existing(self):
        self.seed()
        second_stage = self.root / "next"
        self.assertEqual(prepare(self.git, second_stage, NOW), 1)
        write_package(second_stage / "days", self.second)
        self.assertTrue(publish(self.git, second_stage))
        final = self.root / "readback"
        self.assertEqual(prepare(self.git, final, NOW), 2)
        self.assertEqual(json.loads((final / "days/2026-11-11.json").read_text()), self.first)
        self.assertEqual(json.loads((final / "days/2026-11-12.json").read_text()), self.second)
        self.assertFalse(publish(self.git, final))
        self.assertEqual(self.git.run("ls-tree", "--name-only", "FETCH_HEAD").decode().strip(), "days")

    def test_invalid_new_package_cannot_change_remote(self):
        self.seed()
        previous = self.head()
        staging = self.root / "broken"
        prepare(self.git, staging, NOW)
        (staging / "days/2026-11-12.json").write_text('{"token":"secret"}')
        with self.assertRaises(FeedError): publish(self.git, staging)
        self.assertEqual(self.head(), previous)

    def test_unexpected_executable_or_symlink_is_never_published(self):
        staging = self.seed()
        previous = self.head()
        (staging / "days/attack.py").write_text("raise Exception('not executed')")
        with self.assertRaises(FeedError): publish(self.git, staging)
        (staging / "days/attack.py").unlink()
        (staging / "days/2026-11-12.json").symlink_to(staging / "days/2026-11-11.json")
        with self.assertRaises(FeedError): publish(self.git, staging)
        self.assertEqual(self.head(), previous)

    def test_concurrent_update_is_not_force_overwritten(self):
        self.seed()
        stale, current = self.root / "stale", self.root / "current"
        prepare(self.git, stale, NOW)
        prepare(self.git, current, NOW)
        write_package(current / "days", self.second)
        publish(self.git, current)
        previous = self.head()
        changed = dict(self.first, generatedAt="2026-11-11T12:00:00Z")
        write_package(stale / "days", changed)
        with self.assertRaisesRegex(FeedError, "git_operation_failed"):
            publish(self.git, stale)
        self.assertEqual(self.head(), previous)

    def test_encrypted_rotation_publishes_with_data_and_round_trips(self):
        staging = self.seed()
        current = self.root / "with-state"
        prepare(self.git, current, NOW)
        master, latest = example_jwt(), example_jwt("refreshed")
        save(current / "token.enc.json", latest, master)
        write_package(current / "days", self.second)
        self.assertTrue(publish(self.git, current))
        readback = self.root / "readback-state"
        self.assertEqual(prepare(self.git, readback, NOW), 2)
        self.assertEqual(load(readback / "token.enc.json", master), latest)
        self.assertNotIn(latest, (readback / "token.enc.json").read_text())
        self.assertEqual(set(self.git.run("ls-tree", "--name-only", "FETCH_HEAD").decode().splitlines()), {"days", "token.enc.json"})

    def test_plaintext_state_does_not_change_remote(self):
        staging = self.seed()
        previous = self.head()
        (staging / "token.enc.json").write_text(json.dumps({"token": example_jwt()}))
        with self.assertRaises(FeedError): publish(self.git, staging)
        self.assertEqual(self.head(), previous)


if __name__ == "__main__": unittest.main()
