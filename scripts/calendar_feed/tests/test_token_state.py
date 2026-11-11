import base64
import json
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.calendar_feed.generate import FeedError, build_package, main, write_package
from scripts.calendar_feed.token_state import load, save, seal, unseal, validate_envelope
from test_generate import FixtureClient, NOW


def example_jwt(subject="initial", expiry=1, signature=b"unit-test-signature-not-a-real-token" * 2):
    def encode(value):
        value = json.dumps(value).encode() if isinstance(value, dict) else value
        return base64.urlsafe_b64encode(value).decode().rstrip("=")
    return ".".join([encode({"alg": "HS256", "typ": "JWT"}), encode({"sub": subject, "exp": expiry}), encode(signature)])


class TokenStateTest(unittest.TestCase):
    def test_round_trip_uses_expired_master_only_as_key(self):
        master, latest = example_jwt(expiry=1), example_jwt("latest", expiry=4_000_000_000)
        envelope = seal(latest, master)
        validate_envelope(envelope)
        self.assertEqual(unseal(envelope, master), latest)
        serialized = json.dumps(envelope)
        self.assertNotIn(latest, serialized)
        self.assertNotIn(master, serialized)
        self.assertNotIn("latest", serialized)
        self.assertNotIn('"token"', serialized)

    def test_wrong_master_and_tampering_are_rejected_without_fallback(self):
        master = example_jwt()
        envelope = seal(example_jwt("new"), master)
        with self.assertRaisesRegex(FeedError, "authentication_failed"):
            unseal(envelope, example_jwt("different"))
        data = bytearray(base64.urlsafe_b64decode(envelope["ciphertext"]))
        data[-1] ^= 1
        envelope["ciphertext"] = base64.urlsafe_b64encode(data).decode()
        with self.assertRaisesRegex(FeedError, "authentication_failed"):
            unseal(envelope, master)

    def test_ciphertext_is_random_and_header_has_no_plaintext_payload(self):
        master, latest = example_jwt(), example_jwt("latest")
        a, b = seal(latest, master), seal(latest, master)
        self.assertNotEqual(a["salt"], b["salt"])
        self.assertNotEqual(a["ciphertext"], b["ciphertext"])
        self.assertEqual(set(a), {"schemaVersion", "algorithm", "salt", "ciphertext"})

    def test_weak_master_is_rejected(self):
        for weak in ["password", "a" * 500, "header.claims.signature", example_jwt(signature=b"short")]:
            with self.subTest(weak=weak[:8]), self.assertRaises(FeedError):
                seal(example_jwt("latest"), weak)

    def test_missing_state_bootstraps_but_bad_state_does_not(self):
        master, latest = example_jwt(), example_jwt("latest")
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "token.enc.json"
            self.assertEqual(load(path, master), master)
            save(path, latest, master)
            self.assertEqual(load(path, master), latest)
            raw = path.read_text()
            self.assertNotIn(latest, raw)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FeedError): save(path, "not-a-token", master)
            self.assertEqual(path.read_text(), raw)
            path.write_text('{"token":"not encrypted"}')
            with self.assertRaises(FeedError): load(path, master)

    def test_metadata_or_plaintext_fields_cannot_be_added(self):
        envelope = seal(example_jwt("new"), example_jwt())
        envelope["token"] = "private"
        with self.assertRaises(FeedError): validate_envelope(envelope)

    def test_envelope_bounds_and_symlink(self):
        master = example_jwt()
        envelope = seal(example_jwt("new"), master)
        envelope["ciphertext"] = "x" * 30_001
        with self.assertRaises(FeedError): validate_envelope(envelope)
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "target"
            target.write_text("unchanged")
            path = Path(folder) / "token.enc.json"
            path.symlink_to(target)
            with self.assertRaises(FeedError): save(path, master, master)
            with self.assertRaises(FeedError): load(path, master)
            self.assertEqual(target.read_text(), "unchanged")


class RotationFlowTest(unittest.TestCase):
    class Client:
        def __init__(self, token):
            self.initial = token
            self._token = token
            self.requests = 0
            self.events = []
        def refresh(self):
            self.events.append("refresh")
            self.requests += 1
            self._token = example_jwt("refreshed")
        def get(self, route, day):
            self.events.append("verify")
            self.requests += 1
            return {"round_week": "Проверенный день"}

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state = self.root / "token.enc.json"
        self.master = example_jwt("initial", expiry=1)
        self.latest = example_jwt("latest", expiry=4_000_000_000)
        save(self.state, self.latest, self.master)
        self.before = self.state.read_bytes()
        self.book = build_package(FixtureClient("2026-11-11"), "2026-11-11", NOW)
        self.args = ["--date", "2026-11-11", "--out", str(self.root / "days"), "--token-state", str(self.state)]

    def tearDown(self): self.temporary.cleanup()

    def run_with(self, client, builder):
        with patch.dict(os.environ, {"AZBYKA_API_TOKEN": self.master}), patch("scripts.calendar_feed.generate.ApiClient", return_value=client) as factory, patch("scripts.calendar_feed.generate.build_package", side_effect=builder), patch("sys.stdout", io.StringIO()), patch("sys.stderr", io.StringIO()):
            result = main(self.args)
            factory.assert_called_once_with(self.latest)
            return result

    def test_refresh_verify_then_save_new_envelope(self):
        client = self.Client(self.latest)
        def build(*args):
            self.assertEqual(client.events, ["refresh", "verify"])
            return self.book
        self.assertEqual(self.run_with(client, build), 0)
        self.assertEqual(load(self.state, self.master), example_jwt("refreshed"))
        self.assertNotIn(example_jwt("refreshed").encode(), self.state.read_bytes())
        self.assertTrue((self.root / "days/2026-11-11.json").exists())

    def test_failed_fetch_keeps_old_encrypted_state(self):
        def failed(*args): raise FeedError("network_unavailable")
        self.assertEqual(self.run_with(self.Client(self.latest), failed), 1)
        self.assertEqual(self.state.read_bytes(), self.before)

    def test_cancelled_fetch_keeps_old_encrypted_state(self):
        def cancelled(*args): raise KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            self.run_with(self.Client(self.latest), cancelled)
        self.assertEqual(self.state.read_bytes(), self.before)

    def test_invalid_refresh_does_not_replace_state_or_existing_data(self):
        write_package(self.root / "days", self.book)
        before_day = (self.root / "days/2026-11-11.json").read_bytes()
        client = self.Client(self.latest)
        def failed(): raise FeedError("http_401")
        client.refresh = failed
        self.assertEqual(self.run_with(client, lambda *args: self.book), 1)
        self.assertEqual(self.state.read_bytes(), self.before)
        self.assertEqual((self.root / "days/2026-11-11.json").read_bytes(), before_day)

    def test_all_reusable_days_still_refresh_and_verify(self):
        write_package(self.root / "days", self.book)
        client = self.Client(self.latest)
        with patch("scripts.calendar_feed.generate.reusable", return_value=True):
            self.assertEqual(self.run_with(client, lambda *args: self.fail("reused data must not be fetched")), 0)
        self.assertEqual(client.events, ["refresh", "verify"])
        self.assertEqual(load(self.state, self.master), example_jwt("refreshed"))


if __name__ == "__main__": unittest.main()
