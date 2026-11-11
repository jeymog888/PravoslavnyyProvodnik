import copy
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from lxml import html

from scripts.calendar_feed.generate import (ApiClient, FeedError, MAX_RESPONSE_BYTES,
    NoRedirect, build_package, main, normalize, public_pairs, reusable, text_of,
    validate_package, write_package)

FIXTURES = Path(__file__).parent / "fixtures"
NOW = datetime(2026, 11, 11, 10, tzinfo=timezone.utc)


class FixtureClient:
    def __init__(self, day):
        self.fixture = json.loads((FIXTURES / f"{day}.json").read_text())
        self.requests = []

    def get(self, route, key):
        self.requests.append(f"{route}/{key}")
        return copy.deepcopy(self.fixture["api"][f"{route}/{key}"])

    def calendar(self, day):
        return self.fixture["calendarHtml"].encode()


class FakeResponse:
    def __init__(self, value, url, media="application/json", status=200):
        self.data = value if isinstance(value, bytes) else json.dumps(value).encode()
        self.url, self.status, self.headers = url, status, {"Content-Type": media}

    def __enter__(self): return self
    def __exit__(self, *_): pass
    def geturl(self): return self.url
    def read(self, size): return self.data[:size]


class FakeOpener:
    def __init__(self, actions): self.actions, self.calls = list(actions), []
    def open(self, request, timeout):
        self.calls.append(request)
        action = self.actions.pop(0)
        if isinstance(action, Exception): raise action
        return action(request)


class CalendarFeedTest(unittest.TestCase):
    def test_actual_sample_graphs_across_five_dates(self):
        for day in ["2026-11-11", "2026-11-12", "2026-11-15", "2026-01-07", "2026-04-12"]:
            with self.subTest(day=day):
                client = FixtureClient(day)
                result = build_package(client, day, NOW)
                validate_package(result, day)
                self.assertTrue(result["translations"])
                self.assertTrue(result["commemorations"])
                for translation in result["translations"]:
                    self.assertEqual(translation["sourceUrl"], f"https://azbyka.ru/days/{day}")
                self.assertNotIn("audioSource", json.dumps(result))
                self.assertNotIn("tropariaOrKontakia", json.dumps(result))

    def test_weekday_chants_are_not_added_to_nativity(self):
        result = build_package(FixtureClient("2026-01-07"), "2026-01-07", NOW)
        ids = {item["apiId"] for item in result["translations"]}
        self.assertEqual(ids, {2229, 2230})
        self.assertNotIn(4459, ids)
        self.assertNotIn(4460, ids)
        self.assertIn("Рождество", result["commemorations"][0]["title"])

    def test_integer_string_and_null_voice(self):
        for voice, expected in [(6, 6), ("6", 6), (None, None)]:
            client = FixtureClient("2026-11-11")
            client.fixture["api"]["daytype/2026-11-11"]["voice"] = voice
            self.assertEqual(build_package(client, "2026-11-11", NOW)["voice"], expected)
        client.fixture["api"]["daytype/2026-11-11"]["voice"] = True
        with self.assertRaisesRegex(FeedError, "invalid_voice"):
            build_package(client, "2026-11-11", NOW)

    def test_mismatch_is_not_a_translation(self):
        client = FixtureClient("2026-11-11")
        client.fixture["api"]["troparia-or-kontakias/2815"]["explanation"] = "Совсем другой смысл"
        result = build_package(client, "2026-11-11", NOW)
        self.assertNotIn(2815, [item["apiId"] for item in result["translations"]])

    def test_explanation_label_is_not_a_translation_label(self):
        client = FixtureClient("2026-11-11")
        client.fixture["calendarHtml"] = client.fixture["calendarHtml"].replace("Перевод:", "Толкование:")
        result = build_package(client, "2026-11-11", NOW)
        self.assertEqual(result["translations"], [])

    def test_wrong_page_date_and_heading_reject_whole_package(self):
        client = FixtureClient("2026-11-11")
        with self.assertRaisesRegex(FeedError, "calendar_date_mismatch"):
            public_pairs(client.calendar("2026-11-11"), "2026-11-12")
        root = html.fromstring(client.calendar("2026-11-11").decode("utf-8"))
        heading = root.xpath("//h1")[0]
        heading.clear()
        heading.text = "12 ноября 2026"
        bad = html.tostring(root, encoding="utf-8")
        with self.assertRaisesRegex(FeedError, "calendar_date_mismatch"):
            public_pairs(bad, "2026-11-11")

    def test_html_is_plain_text_and_normalization_preserves_iy(self):
        self.assertEqual(text_of('<p>Слово <a href="https://secret">ссылки</a></p><p>Ниже</p><script>bad()</script><style>bad</style>', 500), "Слово ссылки\n\nНиже")
        self.assertEqual(normalize("И́Й, ё / ѣ і ѳ ѵ"), "ий е е и ф и")
        self.assertNotEqual(normalize("мой"), normalize("мои"))

    def test_block_boundaries_separate_words_but_inline_does_not(self):
        self.assertEqual(text_of("один<div>два</div>три", 200), "один\n\nдва\n\nтри")
        self.assertEqual(text_of("свя<b>той</b>", 200), "святой")
        for tag in ["blockquote", "dt", "dd", "section", "article", "h5", "h6", "pre", "figcaption"]:
            with self.subTest(tag=tag):
                self.assertEqual(text_of(f"До<{tag}>цитата</{tag}>после", 200), "До\n\nцитата\n\nпосле")
        self.assertEqual(text_of("До<hr>после", 200), "До\n\nпосле")
        self.assertEqual(text_of("До<table><tr><th>Заголовок</th><td>ячейка</td></tr></table>после", 200), "До\n\nЗаголовок\n\nячейка\n\nпосле")

    def test_entity_response_must_have_requested_id(self):
        client = FixtureClient("2026-11-11")
        client.fixture["api"]["saints-groups/438"]["id"] = 439
        with self.assertRaisesRegex(FeedError, "entity_id_mismatch"):
            build_package(client, "2026-11-11", NOW)

    def test_invalid_package_never_overwrites_saved_one(self):
        package = build_package(FixtureClient("2026-11-11"), "2026-11-11", NOW)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            write_package(path, package)
            before = (path / "2026-11-11.json").read_bytes()
            package["translations"][0]["sourceUrl"] = "https://wrong.example"
            with self.assertRaises(FeedError): write_package(path, package)
            self.assertEqual((path / "2026-11-11.json").read_bytes(), before)
            self.assertTrue(reusable(path / "2026-11-11.json", "2026-11-11", NOW + timedelta(hours=23)))
            self.assertFalse(reusable(path / "2026-11-11.json", "2026-11-11", NOW + timedelta(hours=24)))
            self.assertFalse(reusable(path / "2026-11-11.json", "2026-11-12", NOW))

    def test_unexpected_secret_field_and_markup_fail_validation(self):
        package = build_package(FixtureClient("2026-11-11"), "2026-11-11", NOW)
        package["token"] = "sensitive"
        with self.assertRaises(FeedError): validate_package(package, "2026-11-11")
        del package["token"]
        package["commemorations"][0]["title"] = "<script>bad</script>"
        with self.assertRaises(FeedError): validate_package(package, "2026-11-11")

    def test_hundred_commemorations_are_accepted_without_truncation(self):
        package = build_package(FixtureClient("2026-11-11"), "2026-11-11", NOW)
        package["commemorations"] = [dict(package["commemorations"][0], title=f"Память {i}") for i in range(100)]
        validate_package(package, "2026-11-11")
        package["commemorations"].append(dict(package["commemorations"][0]))
        with self.assertRaisesRegex(FeedError, "invalid_list"):
            validate_package(package, "2026-11-11")


class TransportTest(unittest.TestCase):
    def client(self, actions, **kwargs):
        opener = FakeOpener(actions)
        return ApiClient("private.test.token", opener=opener, interval=0, **kwargs), opener

    def test_bounded_budget_and_entity_cache(self):
        client, opener = self.client([lambda r: FakeResponse({"id": 12}, r.full_url)], max_requests=1)
        self.assertEqual(client.get("saints", 12), client.get("saints", 12))
        self.assertEqual(len(opener.calls), 1)
        with self.assertRaisesRegex(FeedError, "request_budget_exhausted"):
            client.get("saints", 13)

    def test_public_calendar_never_receives_authorization(self):
        client, opener = self.client([lambda r: FakeResponse(b"<html></html>", r.full_url, "text/html")])
        client.calendar("2026-11-11")
        self.assertIsNone(opener.calls[0].get_header("Authorization"))

    def test_allowlist_rejects_route_injection(self):
        client, opener = self.client([])
        for route, key in [("https://elsewhere.example", 1), ("saints", "../login"), ("day", "2026-1-7")]:
            with self.assertRaises(FeedError): client.get(route, key)
        self.assertEqual(opener.calls, [])

    def test_redirect_is_rejected_without_following(self):
        with self.assertRaisesRegex(FeedError, "redirect_rejected"):
            NoRedirect().redirect_request(None, None, 302, "", {}, "https://example.test/token")

    def test_refresh_once_in_memory_and_no_password(self):
        client, opener = self.client([
            HTTPError("", 401, "", {}, None),
            lambda r: FakeResponse({"token": "new.private.token"}, r.full_url),
            lambda r: FakeResponse({"voice": 6}, r.full_url),
            HTTPError("", 401, "", {}, None),
        ])
        self.assertEqual(client.get("daytype", "2026-11-11"), {"voice": 6})
        self.assertEqual(opener.calls[1].full_url, "https://azbyka.ru/days/api-v2/token/refresh")
        self.assertEqual(opener.calls[1].get_header("Authorization"), "Bearer private.test.token")
        self.assertEqual(json.loads(opener.calls[1].data), {"token": "private.test.token"})
        self.assertEqual(opener.calls[2].get_header("Authorization"), "Bearer new.private.token")
        with self.assertRaisesRegex(FeedError, "http_401"): client.get("daytype", "2026-11-12")
        self.assertEqual(len(opener.calls), 4)

    def test_response_bounds_and_errors_never_echo_credentials(self):
        for action, reason in [
            (lambda r: FakeResponse(b"x" * (MAX_RESPONSE_BYTES + 1), r.full_url), "response_too_large"),
            (lambda r: FakeResponse({}, r.full_url, "text/html"), "invalid_content_type"),
            (URLError("private.test.token"), "network_unavailable"),
            (HTTPError("private.test.token", 403, "private.test.token", {}, None), "http_403"),
        ]:
            client, _ = self.client([action])
            with self.assertRaisesRegex(FeedError, reason) as raised:
                client.get("daytype", "2026-11-11")
            self.assertNotIn("private.test.token", str(raised.exception))

    def test_cli_unexpected_exception_is_redacted(self):
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as temporary, patch("scripts.calendar_feed.generate.ApiClient", side_effect=ValueError("PRIVATE_SECRET")), patch("sys.stderr", output):
            self.assertEqual(main(["--out", temporary, "--date", "2026-11-11"]), 1)
        self.assertEqual(output.getvalue(), "Calendar update failed: unexpected_error\n")


if __name__ == "__main__":
    unittest.main()
