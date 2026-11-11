import unittest
from scripts.calendar_feed.generate import build_package


class AmbiguousTranslationsTest(unittest.TestCase):
    def test_conflicting_original_is_fully_omitted_even_when_a_third_variant_repeats_first(self):
        pairs = [("Первый оригинал", "Первый перевод"), ("Первый оригинал", "Другой перевод"),
                 ("Первый оригинал", "Первый перевод"), ("Второй оригинал", "Верный перевод")]

        class Client:
            def calendar(self, day):
                parts = "".join(f'<div class="taks_content"><div><p>{original}</p>'
                    f'<span class="taks-explanation">Перевод:</span>'
                    f'<div class="taks-explanation-info">{russian}</div></div></div>' for original, russian in pairs)
                return (f'<html><head><link rel="canonical" href="https://azbyka.ru/days/{day}"></head>'
                        f'<body><h1>12 ноября 2026</h1>{parts}</body></html>').encode()

            def get(self, route, key):
                if route == "daytype": return {"round_week": "Седмица 24-я", "voice": 6}
                if route == "day": return []
                if route == "day-direct": return {"tropariaOrKontakia": [{"id": i + 1} for i in range(4)]}
                original, russian = pairs[key - 1]
                return {"id": key, "text": original, "explanation": russian}

        package = build_package(Client(), "2026-11-12")
        self.assertEqual([4], [pair["apiId"] for pair in package["translations"]])
        self.assertEqual("Верный перевод", package["translations"][0]["russian"])
        self.assertEqual("2026-11-12", package["date"])


if __name__ == "__main__":
    unittest.main()
