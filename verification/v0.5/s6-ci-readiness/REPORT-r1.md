# CI-only patch готов к review

Patch: `/private/tmp/feoria-ci-health-v05/ci-health.patch`
SHA256: `787bb928012192a2348b680f3b21753a1edab6ccdd9d2df824810480c99520e3`
Основа: HEAD `7900494`, git apply --check PASS, repo не менял

## Что изменено

- Wrapper сохраняет исходные PNG/raw observations до подготовки и выносит fail-closed readiness: boot/qemu, фактический HOME resolver, Awake + interactive + не переходящее power state, keyguard=false, точная идентичность focused visible window и инвентарь owners/visibility
- Формат намеренно ограничен фактической CI image API35. UNKNOWN, неоднозначный focus/owner/visibility, другой overlay/ANR, неготовый экран - suite не запускается
- Workflow явно разрешает одну подготовительную попытку только при доказанном Android-owned NexusLauncher ANR, original PNG, emulator-5554, qemu=1, правильном HOME component. Один force-stop этого launcher и один start наблюденного component, затем новые наблюдения. Ни pm clear, ни reboot, ни screen-timeout/wake/unlock, ни повтор тестов
- После оригинального snapshot на подготовку выделено 20 секунд. Плохое состояние после restart запрещает suite. READY требуется в двух свежих независимых чтениях; команды запуска сами по себе не подтверждают готовность
- Shell-owned run directory создается без перезаписи своего или чужого run. Коллизия запрещает suite
- Collector сохраняет packet по прежнему пути и копирует завершенный packet через shell-owned mirror до rethrow. Экспорт резервирует 3 секунды внутри прежнего 30-секундного diagnostic budget. `timeout 2 sh ...` ограничивает сам процесс экспорта, имеющий completion marker только после успешного cp. stdout/stderr/status экспорта сохраняются также рядом с mirror вне app storage
- Host finally выгружает mirror и прежний app-scoped путь отдельно. Ненулевой exit suite сохраняется; подготовительный отказ имеет exit=3, tests_started=false, test_exit_code=null
- Production, сценарии, assertions и timeout тестов не меняются

## Проверено без устройства

- 15 host unit/fake-command checks PASS, включая nonzero suite exit, ровно один restart/run, неизвестный ANR, повторный ANR, отсутствие opt-in/нужного serial/PNG, сбой restart, коллизию mirror, потерю readiness между независимыми чтениями, неоднозначные окна и отсутствующие поля
- Полные реальные windows/displays/power/policy preflight и after-suite run42511850558 классифицируются как KNOWN_LAUNCHER_ANR, suite готовность не получает. Старый wrapper не записывал boot/qemu/HOME resolver; эти 3 значения в fixture-контроле явно синтетические и не названы наблюдениями того CI. Удаление каждого из них дает UNKNOWN (6 контролей)
- Фактический embedded shell-script выполнен на host после подстановки временных путей: успешная копия переживает удаление source; коллизия сохраняет существующий packet; ошибка cp не создает completion marker. Во всех 3 случаях export log/status сохранен
- Self-review исправил реальный дефект первого draft: допустимая owner/visibility строка могла скрыть вторую строку неизвестного формата. Теперь сначала считаются все строки поля, затем проверяется единственная полная строка. Добавлены отрицательные контроли
- git apply --check PASS, git status clean

## Ограничения и следующий шаг

Ни adb, ни Gradle, ни CI я не запускал. Kotlin compilation и работа Android `timeout`/shell transport пока не проверены на устройстве. Host shell controls проверяют протокол копирования, не Android permissions/SELinux. Нужны root + Astra review, compile androidTest, затем один новый exact-commit CI по preflight. Старые CI failures остаются failures; успешная подготовка не доказывает устранение системной причины launcher ANR

Файлы `host-tests.log`, `host-evidence-results.json`, `result.json` находятся рядом с patch. Материализация final files - `after/`
