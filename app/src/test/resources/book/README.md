# Service book fixtures

Public Azbyka worship pages downloaded on 2026-11-11 for parser regression tests

Source pattern: `https://azbyka.ru/worships/?date=YYYY-MM-DD&worship=KIND&size=full&church=30`

The fixtures retain the selected date, service kind, full-size selector, church and feast selectors, title and complete service content DOM. Unrelated site navigation is omitted

- `liturgy-2026-11-11`, weekday `vespers`, `matins`, `hour1` for 2026-11-12 were the initial full-book fixtures
- `chas3`, `chas6`, `chas9` for 2026-11-12 exercise each hour's distinct closing prayer
- `liturgy`, `vespers`, `matins` for 2026-11-15 exercise Sunday orders, including Vespers ending with the blessing before Matins
- `liturgy`, `matins` for 2026-04-12 exercise Paschal orders, including Matins without the ordinary Six Psalms
- `vespers-2026-04-12` is an intentional negative fixture: the endpoint returns a Paschal Midnight Office instead of Vespers, with `midnight` as the first selector option and no selected option. It must be rejected for a Vespers request. A separate regression also forces the selector to match and checks the actual service title

Tests also cut these real DOMs at several positions and remove their last content paragraph. Presence of an earlier "Amen" must not make a truncated service pass validation

- `evening-psalm-originals.json` preserves 10 complete Church Slavonic Psalms that match the dated books after matching Psalm-number headings are separated on both sides of a source row
- `evening-prayer-originals.json` preserves a complete matching original from each of the six added evening/hour sources
- `calendar-hymns-2026-11-11.html` preserves the calendar page's canonical date, heading, paired DOM structure and eight complete Church Slavonic originals. Modern Russian translations are replaced with explicit synthetic markers; six pairs match the actual liturgy fixture. Source: `https://azbyka.ru/days/2026-11-11`

- `translation-paired-structure-full.html` and `translation-paired-structure-short.html` preserve eleven actual Church Slavonic rows and their paired DOM boundaries from the published liturgy editions. Russian prayer bodies are replaced by explicit synthetic markers; choir labels, threefold-repeat markers, matching h3 headings and alternating 1./2. edition markers remain. These markers are not biblical verse numbers. `translation-paired-provenance.json` records source URLs, row indexes and source/fixture SHA-256 hashes. The fixtures exercise thirteen repeated visible blocks in the 2026-11-12 audit; they do not ship modern translated prayers in the APK
