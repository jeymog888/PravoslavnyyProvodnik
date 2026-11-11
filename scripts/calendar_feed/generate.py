#!/usr/bin/env python3
"""Produce attributed public calendar supplements without distributing API credentials.

Only translations independently published as "Перевод" on the same dated calendar
page leave this adapter. The resulting list is a lexical concordance, NOT an order
of service: Android must additionally match the complete original prayer.
"""
from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zoneinfo import ZoneInfo

from lxml import etree, html

if __package__:
    from .errors import FeedError
else:
    from errors import FeedError

BASE = "https://azbyka.ru/days/"
SOURCE_TITLE = "Азбука веры: календарь и API"
MAX_RESPONSE_BYTES = 2_000_000
MAX_PACKAGE_BYTES = 512_000
MAX_DATES = 14
MAX_ENTITIES = 100
MAX_TRANSLATIONS = 200
MAX_REQUESTS = 500
MONTHS = ("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
          "августа", "сентября", "октября", "ноября", "декабря")
DAY_ROUTES = {"day", "day-direct", "daytype"}
ENTITY_ROUTES = {"saints", "saints-groups", "holidays", "icons-of-our-ladies",
                 "icons-of-saviors", "troparia-or-kontakias"}
ENTITY_FIELDS = (("saintsGroup", "saintsGroups", "saints-groups"),
                 ("holiday", "holidays", "holidays"),
                 ("iconOfOurLady", "iconsOfOurLady", "icons-of-our-ladies"),
                 ("iconOfSavior", "iconsOfSavior", "icons-of-saviors"))


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise FeedError("redirect_rejected")


def valid_date(value: str) -> date:
    try:
        parsed = date.fromisoformat(value)
        if parsed.isoformat() != value or not 1900 <= parsed.year <= 2200:
            raise ValueError()
        return parsed
    except (TypeError, ValueError):
        raise FeedError("invalid_date") from None


def identifier(value) -> int:
    if type(value) is not int or not 1 <= value <= 100_000_000:
        raise FeedError("invalid_id")
    return value


def clean(value: str) -> str:
    return re.sub(r"[\s\u00a0]+", " ", value.replace("\u00ad", "")).strip()


def normalize(value: str) -> str:
    # Match BookText, preserving й. Removing every NFD combining mark would change words.
    value = re.sub("[\u0300\u0301\u0340\u0341]", "", unicodedata.normalize("NFC", value))
    value = value.lower().translate(str.maketrans({"ё": "е", "ѣ": "е", "і": "и", "i": "и", "ѳ": "ф", "ѵ": "и"}))
    return clean("".join(char if char.isalnum() else " " for char in value))


def text_of(value, maximum: int, required: bool = False) -> str | None:
    if value is None and not required:
        return None
    if not isinstance(value, str) or len(value) > MAX_RESPONSE_BYTES:
        raise FeedError("invalid_text")
    try:
        root = html.fragment_fromstring(value, create_parent="div")
        for element in root.xpath(".//script | .//style | .//iframe | .//object | .//template | .//noscript"):
            element.drop_tree()
        # Preserve both sides of block boundaries; inline markup can divide a single word.
        blocks = {"p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6",
                  "blockquote", "hr", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
                  "dl", "dt", "dd", "section", "article", "ul", "ol", "pre", "figure", "figcaption"}
        def fragments(element):
            block = isinstance(element.tag, str) and element.tag.lower() in blocks
            if block:
                yield "\n"
            if element.text:
                yield element.text
            for child in element:
                if isinstance(child.tag, str):
                    yield from fragments(child)
                if child.tail:
                    yield child.tail
            if block:
                yield "\n"
        text = "\n\n".join(clean(line) for line in "".join(fragments(root)).splitlines() if clean(line))
        text = "".join(c for c in text if c in "\n\t" or unicodedata.category(c) != "Cc")
    except (etree.ParserError, ValueError, TypeError):
        raise FeedError("invalid_html_text") from None
    if not text or len(text) > maximum:
        if required:
            raise FeedError("text_bound")
        return None
    return text


def list_of(value, limit: int = MAX_ENTITIES) -> list:
    if not isinstance(value, list) or len(value) > limit:
        raise FeedError("invalid_list")
    return value


def dict_of(value) -> dict:
    if not isinstance(value, dict):
        raise FeedError("invalid_object")
    return value


def has_class(element, name: str) -> bool:
    return name in element.get("class", "").split()


def public_pairs(page: bytes, requested_date: str) -> set[tuple[str, str]]:
    parsed_date = valid_date(requested_date)
    if len(page) > MAX_RESPONSE_BYTES:
        raise FeedError("response_too_large")
    try:
        root = html.fromstring(page.decode("utf-8", errors="strict"))
    except (UnicodeDecodeError, etree.ParserError, ValueError):
        raise FeedError("invalid_calendar_html") from None
    canonical = root.xpath('//link[@rel="canonical"]/@href')
    headings = root.xpath("//h1")
    expected = f"{parsed_date.day} {MONTHS[parsed_date.month - 1]} {parsed_date.year}"
    if canonical not in ([BASE + requested_date], [BASE + requested_date + "/"]) or len(headings) != 1 or clean(headings[0].text_content()) != expected:
        raise FeedError("calendar_date_mismatch")
    result = set()
    parts = root.xpath('//*[contains(concat(" ",normalize-space(@class)," ")," taks_content ")]')
    if len(parts) > MAX_TRANSLATIONS:
        raise FeedError("calendar_count_bound")
    for part in parts:
        translations = part.xpath('.//*[contains(concat(" ",normalize-space(@class)," ")," taks-explanation-info ")]')
        if len(translations) != 1:
            continue
        translated = translations[0]
        container = translated.getparent()
        label = translated.getprevious()
        if container is None or container.getparent() is not part or label is None:
            continue
        if not has_class(label, "taks-explanation") or normalize(label.text_content()) != "перевод":
            continue
        originals = []
        for child in container:
            if child is label:
                break
            originals.append(child)
        if not originals or any(child.tag != "p" for child in originals):
            continue
        original = text_of(" ".join(html.tostring(child, encoding="unicode") for child in originals), 12_000)
        russian = text_of(html.tostring(translated, encoding="unicode"), 16_000)
        if original and russian:
            result.add((normalize(original), normalize(russian)))
    return result


class ApiClient:
    def __init__(self, token: str, max_requests: int = MAX_REQUESTS, interval: float = 0.7,
                 opener=None, sleep=time.sleep, monotonic=time.monotonic):
        self._token = self._validate_token(token)
        if not 1 <= max_requests <= MAX_REQUESTS or not 0 <= interval <= 10:
            raise FeedError("invalid_request_budget")
        self.max_requests, self.interval = max_requests, interval
        self.requests = 0
        self._opener = opener or build_opener(NoRedirect())
        self._sleep, self._clock, self._last = sleep, monotonic, None
        self._cache = {}
        self._refreshed = False

    @staticmethod
    def _validate_token(token: str) -> str:
        if not isinstance(token, str) or not 10 <= len(token) <= 20_000 or not re.fullmatch(r"[A-Za-z0-9_.-]+", token):
            raise FeedError("invalid_token")
        return token

    def _request(self, relative: str, authenticated: bool, body: bytes | None = None) -> bytes:
        if self.requests >= self.max_requests:
            raise FeedError("request_budget_exhausted")
        if self._last is not None:
            self._sleep(max(0, self.interval - (self._clock() - self._last)))
        self.requests += 1
        self._last = self._clock()
        headers = {"Accept": "application/json" if relative.startswith("api-v2/") else "text/html",
                   "Accept-Encoding": "identity", "User-Agent": "FeoriaCalendar/0.9 (+https://github.com/jeymog888/PravoslavnyyProvodnik)"}
        if authenticated:
            headers["Authorization"] = "Bearer " + self._token
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = Request(BASE + relative, data=body, headers=headers, method="POST" if body is not None else "GET")
        try:
            with self._opener.open(request, timeout=20) as response:
                if response.status != 200 or response.geturl() != BASE + relative:
                    raise FeedError("unexpected_response")
                media = response.headers.get("Content-Type", "").split(";")[0].strip().lower()
                permitted = {"application/json", "application/ld+json"} if relative.startswith("api-v2/") else {"text/html", "application/xhtml+xml"}
                if media not in permitted:
                    raise FeedError("invalid_content_type")
                data = response.read(MAX_RESPONSE_BYTES + 1)
                if len(data) > MAX_RESPONSE_BYTES:
                    raise FeedError("response_too_large")
                return data
        except HTTPError as error:
            # Do not read the response body: error pages may repeat request details.
            raise FeedError(f"http_{error.code}") from None
        except (URLError, OSError, TimeoutError, ValueError):
            raise FeedError("network_unavailable") from None

    def _json(self, data: bytes):
        try:
            return json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, ValueError, RecursionError):
            raise FeedError("invalid_json") from None

    def refresh(self) -> None:
        if self._refreshed:
            raise FeedError("refresh_already_attempted")
        self._refreshed = True
        # The live server additionally requires Bearer authentication although the
        # published OpenAPI example only shows the JSON body. Never log either token.
        fresh = self._json(self._request("api-v2/token/refresh", True,
                            json.dumps({"token": self._token}).encode("utf-8")))
        self._token = self._validate_token(dict_of(fresh).get("token"))

    def get(self, route: str, key: str | int):
        if route in DAY_ROUTES:
            valid_date(key)
        elif route in ENTITY_ROUTES:
            identifier(key)
        else:
            raise FeedError("route_rejected")
        relative = f"api-v2/{route}/{key}"
        if relative in self._cache:
            return self._cache[relative]
        try:
            data = self._request(relative, True)
        except FeedError as error:
            if str(error) != "http_401" or self._refreshed:
                raise
            self.refresh()
            data = self._request(relative, True)
        result = self._json(data)
        if route in ENTITY_ROUTES and dict_of(result).get("id") != key:
            raise FeedError("entity_id_mismatch")
        self._cache[relative] = result
        return result

    def calendar(self, requested_date: str) -> bytes:
        valid_date(requested_date)
        return self._request(requested_date, False)


def build_package(client, requested_date: str, now: datetime | None = None) -> dict:
    valid_date(requested_date)
    source = BASE + requested_date
    # Date validation is mandatory even if there are no usable translations.
    pairs = public_pairs(client.calendar(requested_date), requested_date)
    day_type = dict_of(client.get("daytype", requested_date))
    rows = list_of(client.get("day", requested_date))
    direct = dict_of(client.get("day-direct", requested_date))
    week = text_of(day_type.get("round_week") or day_type.get("feed"), 300, required=True)
    voice = day_type.get("voice")
    if isinstance(voice, str) and re.fullmatch(r"[1-8]", voice):
        voice = int(voice)
    if voice is not None and (type(voice) is not int or not 1 <= voice <= 8):
        raise FeedError("invalid_voice")
    index = {}
    for field in ["saints"] + [spec[1] for spec in ENTITY_FIELDS]:
        index[field] = {identifier(dict_of(item).get("id")): item for item in list_of(direct.get(field, []))}
    chants = {identifier(dict_of(item).get("id")) for item in list_of(direct.get("tropariaOrKontakia", []), MAX_TRANSLATIONS)}
    commemorations, visited = [], set()

    def add_entity(route, field, entry):
        entity_id = identifier(dict_of(entry).get("id"))
        identity = (route, entity_id)
        if identity in visited:
            return
        visited.add(identity)
        if len(visited) > MAX_ENTITIES:
            raise FeedError("entity_count_bound")
        entity = dict_of(client.get(route, entity_id))
        if entity.get("id") != entity_id:
            raise FeedError("entity_id_mismatch")
        listed = index[field].get(entity_id, {})
        title = text_of(listed.get("title") or entity.get("title") or entity.get("cacheTitle"), 500, required=True)
        # Short descriptions belong to this memorial-day entry, not an arbitrary biography.
        description = text_of(entry.get("shortDescription"), 1800)
        commemorations.append({"title": title, "description": description, "sourceUrl": source})
        chants.update(identifier(dict_of(item).get("id")) for item in list_of(entity.get("tropariaOrKontakia", []), MAX_TRANSLATIONS))
        if len(chants) > MAX_TRANSLATIONS:
            raise FeedError("chant_count_bound")

    def order(row):
        row = dict_of(row)
        values = row.get("paragraph"), row.get("number")
        if any(type(value) is not int or not 0 <= value <= 1000 for value in values):
            raise FeedError("invalid_day_order")
        return values

    for row in sorted(rows, key=order):
        memorial = dict_of(row.get("memorialDay"))
        for field, direct_field, route in ENTITY_FIELDS:
            if memorial.get(field) is not None:
                add_entity(route, direct_field, memorial[field])
        for saint in list_of(memorial.get("saints", [])):
            add_entity("saints", "saints", saint)
        # holidayAdditionalDay has no stable documented entity endpoint. Do not guess its
        # parent holiday or silently treat it as an independent primary feast.

    translations, seen, ambiguous = [], {}, set()
    for chant_id in sorted(chants):
        chant = dict_of(client.get("troparia-or-kontakias", chant_id))
        if chant.get("id") != chant_id:
            raise FeedError("entity_id_mismatch")
        original, russian = text_of(chant.get("text"), 12_000), text_of(chant.get("explanation"), 16_000)
        if not original or not russian or (normalize(original), normalize(russian)) not in pairs:
            continue
        key = normalize(original)
        if key in ambiguous:
            continue
        if key in seen:
            if seen[key] != normalize(russian):
                # Both editions are published, but this concordance cannot choose between
                # them. Exclude this original completely without losing the rest of the day.
                ambiguous.add(key)
                translations = [pair for pair in translations if normalize(pair["original"]) != key]
            continue
        seen[key] = normalize(russian)
        translations.append({"original": original, "russian": russian, "sourceTitle": SOURCE_TITLE,
                             "sourceUrl": source, "apiId": chant_id})
    generated = now or datetime.now(timezone.utc)
    package = {"schemaVersion": 1, "date": requested_date, "sourceUrl": source, "weekTitle": week,
               "voice": voice, "commemorations": commemorations, "translations": translations,
               "generatedAt": generated.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")}
    validate_package(package, requested_date)
    return package


def validate_package(package, expected_date: str) -> None:
    valid_date(expected_date)
    required = {"schemaVersion", "date", "sourceUrl", "weekTitle", "voice", "commemorations", "translations", "generatedAt"}
    if not isinstance(package, dict) or set(package) != required or type(package["schemaVersion"]) is not int or package["schemaVersion"] != 1:
        raise FeedError("invalid_package")
    source = BASE + expected_date
    if package["date"] != expected_date or package["sourceUrl"] != source:
        raise FeedError("package_date_mismatch")

    def plain(value, limit, nullable=False):
        if nullable and value is None:
            return
        if not isinstance(value, str) or not 1 <= len(value) <= limit or "<" in value or ">" in value or any(unicodedata.category(c) == "Cc" and c != "\n" for c in value):
            raise FeedError("invalid_package_text")

    plain(package["weekTitle"], 300)
    if package["voice"] is not None and (type(package["voice"]) is not int or not 1 <= package["voice"] <= 8):
        raise FeedError("invalid_voice")
    for item in list_of(package["commemorations"]):
        if not isinstance(item, dict) or set(item) != {"title", "description", "sourceUrl"} or item["sourceUrl"] != source:
            raise FeedError("invalid_commemoration")
        plain(item["title"], 500)
        plain(item["description"], 1800, True)
    originals = set()
    for item in list_of(package["translations"], MAX_TRANSLATIONS):
        if not isinstance(item, dict) or set(item) != {"original", "russian", "sourceTitle", "sourceUrl", "apiId"} or item["sourceUrl"] != source or item["sourceTitle"] != SOURCE_TITLE:
            raise FeedError("invalid_translation")
        identifier(item["apiId"])
        plain(item["original"], 12_000)
        plain(item["russian"], 16_000)
        key = normalize(item["original"])
        if not key or key in originals:
            raise FeedError("duplicate_translation")
        originals.add(key)
    try:
        if not isinstance(package["generatedAt"], str) or not package["generatedAt"].endswith("Z"):
            raise ValueError()
        datetime.fromisoformat(package["generatedAt"].replace("Z", "+00:00"))
    except (TypeError, ValueError):
        raise FeedError("invalid_generated_at") from None
    if len(json.dumps(package, ensure_ascii=False).encode("utf-8")) > MAX_PACKAGE_BYTES:
        raise FeedError("package_size_bound")


def write_package(directory: Path, package: dict) -> None:
    requested_date = package.get("date") if isinstance(package, dict) else None
    validate_package(package, requested_date)
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"{requested_date}.json"
    if directory.is_symlink() or target.is_symlink():
        raise FeedError("output_symlink_rejected")
    data = (json.dumps(package, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(data) > MAX_PACKAGE_BYTES:
        raise FeedError("package_size_bound")
    descriptor, temporary = tempfile.mkstemp(prefix=".calendar-", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def reusable(path: Path, requested_date: str, now: datetime, max_age_hours: int = 24) -> bool:
    try:
        if path.is_symlink() or path.stat().st_size > MAX_PACKAGE_BYTES:
            return False
        package = json.loads(path.read_text(encoding="utf-8"))
        validate_package(package, requested_date)
        stamp = datetime.fromisoformat(package["generatedAt"].replace("Z", "+00:00"))
        age = now - stamp
        return timedelta(0) <= age < timedelta(hours=max_age_hours)
    except (FeedError, OSError, ValueError, TypeError):
        return False


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", action="append", default=[], help="ISO date, repeat up to 14 times")
    parser.add_argument("--days", type=int, default=8, help="Moscow today plus following dates (default 8, maximum 14)")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, help="Private JSON with token; prefer protected environment in CI")
    parser.add_argument("--token-state", type=Path, help="Encrypted token.enc.json outside the public days directory")
    parser.add_argument("--force", action="store_true", help="Refresh even a valid package younger than 24 hours")
    args = parser.parse_args(argv)
    try:
        if not 1 <= args.days <= MAX_DATES or len(args.date) > MAX_DATES:
            raise FeedError("date_count_bound")
        today = datetime.now(ZoneInfo("Europe/Moscow")).date()
        dates = sorted({valid_date(value).isoformat() for value in args.date}) if args.date else [(today + timedelta(days=offset)).isoformat() for offset in range(args.days)]
        token = os.environ.get("AZBYKA_API_TOKEN")
        if args.token_file:
            if args.token_file.stat().st_size > 24_000:
                raise FeedError("invalid_token_file")
            token = dict_of(json.loads(args.token_file.read_text(encoding="utf-8"))).get("token")
        master = token
        if args.token_state:
            if args.token_state.name != "token.enc.json" or args.token_state.parent.resolve() == args.out.resolve():
                raise FeedError("invalid_token_state_location")
            if __package__:
                from . import token_state
            else:
                import token_state
            token = token_state.load(args.token_state, master)
        client = ApiClient(token)
        if args.token_state:
            client.refresh()
            # Authenticate the refreshed credential even when all day packages are
            # reusable. Never persist a malformed or unusable refresh response.
            verified = dict_of(client.get("daytype", dates[0]))
            text_of(verified.get("round_week") or verified.get("feed"), 300, required=True)
        now, updated, reused = datetime.now(timezone.utc), 0, 0
        for requested_date in dates:
            if not args.force and reusable(args.out / f"{requested_date}.json", requested_date, now):
                reused += 1
                continue
            package = build_package(client, requested_date, now)
            write_package(args.out, package)
            updated += 1
            print(f"{requested_date}: verified supplement, {len(package['translations'])} exact translations, {len(package['commemorations'])} commemorations")
        if args.token_state:
            # This staging envelope and all verified day files are published in one
            # commit. A failed/cancelled run leaves the previous remote state intact.
            token_state.save(args.token_state, client._token, master)
        print(f"Complete: updated={updated}, reused={reused}, requests={client.requests}")
        return 0
    except FeedError as error:
        print(f"Calendar update failed: {error}", file=sys.stderr)
        return 1
    except Exception:
        # Tracebacks and HTTP exception reprs can leak request data in CI logs.
        print("Calendar update failed: unexpected_error", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
