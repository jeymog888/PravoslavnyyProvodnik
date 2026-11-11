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
