# Самопроверка 3.1a

Статическая самопроверка ядра, Store tests и независимого пакета golden/matrix tests выполнена до source freeze. Gradle, adb и git mutations implementer и автор тестов не запускали. Это не отчет об успешном выполнении тестов

## Исправленные реальные дефекты

1. В первой версии Store fixture устаревшая метка хранилась под вымышленными ключами `session_state` и `current_stage`. Такая проверка доказывала сохранение посторонних preferences, но не настоящих метаданных приложения. По замечанию root fixture исправлена на `ReadingAcceptanceStore.SESSION_KEY` и `ReadingAcceptanceStore.STAGE_KEY`; старые метаданные остаются нетронутыми, а target stage проверяется по мигрированному семантическому адресу
2. Автор golden tests при самопроверке обнаружил обращение к отсутствующему `serviceDate` внутри строк MissingReading. Producer хранит эту дату один раз на уровне таблицы. Исправлено сравнение с независимо зафиксированным `table.serviceDate`; оба опубликованных барьера теперь проверяются без ложного NPE

Первоначально обсуждавшееся имя candidate `stored` заменено до фиксации API на `originalStored`, чтобы caller не принял старый persisted index за target state. Комментарий и Store test явно фиксируют разницу old/new fingerprint и индексов

## Сверенные условия

- SHA обеих frozen assets совпали с producer manifest; SHA golden JSON совпал с независимым producer
- Восемь действующих source/assets из inputs.json остались побайтно равны release, включая текущие liturgy_stages.json, daily_readings.json, parser, Factory и Plan
- Store не вызывает historical reader при current Valid, absent, malformed, unreadable, context mismatch или plan date mismatch
- Old engine проверяет реальный old hash/version/index/quarter offset до семантического сопоставления; new engine валидирует итоговый paused state
- Text проверяет stage/track/line ID, canonical и duration. Whole-stage comparison для Wait включает содержимое и последовательность, не только длительность ожидания
- Changed Wait берет последнюю старую строку того же этапа. Если надежного адреса нет, возвращается отказ, а не строка нового дополнения или предыдущего этапа
- Отсутствующая или испорченная historical база не удаляет snapshot и не включает legacy fallback
- Все изменения ограничены ядром, новыми frozen ресурсами, тестами и provenance; UI и подключение AppContainer остаются следующим этапом

## Подготовленные проверки и пределы

- 2088 переносов Text: 232 старых адреса, три quarter offsets, три скорости, реальные сдвиги новых индексов и неизменность внутренних line IDs
- 306 точных Wait: 34 ожидания, три offsets и три скорости при неизмененной последовательности этапа
- 576 консервативных возвратов из измененных/удаленных Wait и 36 отказов у этапов без старой фразы
- Оба MissingReading и оба End с тремя скоростями, отказ при исчезнувшей преграде/другой дате/другом terminal stage
- Полные golden tables, отказ loader при missing/SHA/BOM/CRLF, неизвестных version/hash, неверных index/offset, missing semantic address, same-ID altered canonical/duration/stage
- 10 Store tests для default opt-in, приоритета current Valid, строгого допуска, read-only, сохранения preferences и отдельного состояния кандидата

Независимое ревью и запуск проверок выполняет root после source freeze. Приемка candidate через writer, заметка и маршруты UI, сохранение после commit и нативное обновление опубликованного APK этим этапом не подтверждаются
