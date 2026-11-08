# Минимальная диагностическая порция CI 0.5

База: `03554e613ad141b3473a5ed5cde1972f4e5b23f4`

Patch: `/private/tmp/feoria-ci-diagnostics-v05/ci-diagnostics.patch`

SHA-256: `262d87c0b83037264449c619d41330b119b0defd6209acff8894cea4734848c8`

Рабочий репозиторий не изменен. adb, Gradle и CI не запускались. Это следующая диагностическая попытка, а не исправление установленной причины

## Почему именно эти места

Исходные доказательства: `/private/tmp/feoria-ci-v05-run42503317372-attempt1/REPORT.md`, `ui-failures.json`, настоящий JUnit XML внутри `artifact/outputs/androidTest-results/connected/debug/emulator-5554 - 15/`

В 119 тестах было 60 failures: 45 RootViewWithoutFocus, 7 IME waits, 7 platform readiness, 1 orientation. Независимая дополнительная выборка всех 45 RootViewWithoutFocus stack traces установила точные вызовы: 38 `Espresso.closeSoftKeyboard`, 7 `Espresso.pressBack`

Первый failure был `ChurchDiscoveryScreenTest.timeoutEmptyResultsAndOldCachedResultsRemainDistinct`: IME show failed при Activity RESUMED/Displayed, затем wait timeout. Это примерно 33 секунды от начала suite. Sleep/keyguard/foreign window не установлены: соответствующих dumps и снимка не было

## Размещение и lifecycle

`app/src/androidTest/java/ru/liturgyguide/ui/CiFailureDiagnostics.kt` добавляет общий catch -> capture -> throw того же Throwable. Для closeSoftKeyboard/pressBack изменены только imports на aliases локальных wrappers. Вызовы остаются настоящими Espresso, его default FailureHandler не заменяется

Пять существующих helper declarations обернуты тем же catch: discovery visible/hidden IME, GuideNavigation waitForKeyboard, ReaderAccessibility awaitPlatformReady, CompactReaderMatrix orient. Ни assertion, ни duration, ни исходный action не изменен

Capture происходит непосредственно при возврате ошибки из вызова, пока стек теста еще внутри helper/action. Он идет до выхода из `ActivityScenario.use`, до Compose Rule/Activity cleanup и до внешнего test finally. Это существенное отличие от JUnit RunListener или внешнего TestWatcher, которые получили бы ошибку после очистки

Не добавлены custom runner, TestRule и app/build.gradle изменения. Проверка локального bytecode Compose 1.7.3 подтвердила, что AndroidComposeTestRule.apply оборачивает body своим activityRule; outer watcher действительно был бы поздним. Для текущего решения это не зависимость исполнения, а причина выбрать точные catch-site

Точное имя @Test ищется в исходном stack trace по runtime org.junit.Test annotation. Полный исходный trace сохраняется независимо от разрешения имени. Если имя не найдено, явно записывается UNRESOLVED, а не выдуманное имя

## Что собирается

Только при instrumentation аргументе `feoriaCiDiagnostics=true` и только у первого перехваченного failure за процесс:

- исходная ошибка и test/source/run/timestamps
- PowerManager.isInteractive, KeyguardManager.isKeyguardLocked и isDeviceLocked
- windows/displays с фактическим focused token/owner/visibility в первую очередь
- PNG, power, window policy, resumed Activity, input_method
- фактические screen_off_timeout, stay_on_while_plugged_in и show_ime_with_hard_keyboard

Общий budget 30 секунд, один shell read не более 3 секунд; при исчерпании пишется BUDGET_EXHAUSTED, а не бесконечное ожидание. Failed/empty reads записываются отдельно. Диагностическая ошибка логируется и не заменяет исходный Throwable. Повторного действия, assertion или test retry нет

Использованы API, доступные на API26: isInteractive (20), isDeviceLocked (23), executeShellCommand (21). Capture не обращается к Compose idle и не ждет window focus, которого как раз может не быть

Standalone `uiautomator dump` намеренно исключен по review root: он мог бы создать отдельную UiAutomation connection внутри работающей instrumentation и изменить окружение. Для выяснения focus owner достаточно raw dumps, платформенных фактов и PNG. XML при первом отказе эта порция не обещает

## CI preflight и доставка артефактов

`scripts/ci/run_android_ui_tests.py` получает явные serial/run-id/out и исходную Gradle command. До suite он только наблюдает настройки, power/windows/displays/policy/activity/IME, SDK/fingerprint и PNG. Ничего не будит, не разблокирует, не включает stay-awake и не меняет timeout. Это измерение, а не readiness PASS или доказанный fix

После команды в finally выполняются отдельный after-suite snapshot и pull first-failure packet, пока emulator-runner еще держит AVD живым. Исходный exit code Gradle сохраняется, даже если after-suite/pull/file write диагностики не удался

В workflow закреплен уже используемый порт5554, все adb diagnostics адресуются emulator-5554. Существующая настройка IME=1 сохранена. Вызов suite один, с двумя диагностическими instrumentation args, без фильтра по классам и без повторов

Артефакты на устройстве:

`/sdcard/Android/data/ru.liturgyguide/files/ci-diagnostics/<run-id>/first-failure-<timestamp>/`

Путь строится через targetContext.getExternalFilesDir("ci-diagnostics"), без записи в private preferences и без разрешения внешнего хранения. На обычном CI AVD это указанный shell-visible app external-files путь. Если он недоступен, причина останется в диагностическом logcat/pull stderr

В artifact `verification-reports` добавлен весь `app/build/ci-diagnostics/`: preflight и after-suite snapshots, commands.json с exit codes/errors, suite-result.json и pulled first-failure. Отдельно добавлен известный недостающий `app/build/test-results/testDebugUnitTest/`, чтобы unit count подтверждался XML. Остальные upload paths сохранены

## Ограничения и следующий анализ

- Снимается первое перехваченное падение в доказанных readiness/Espresso путях. Это не обработчик произвольных product assertions и не перехват падения процесса
- Снимки последовательные, с timestamps; они не объявляются атомарными. Сначала windows/displays, затем остальные признаки. Отсутствующий файл или diagnostic error не превращается в подтверждение нормального состояния
- Без фактического нового CI output нельзя назвать причиной keyguard, sleep, overlay или IME. Сначала сопоставить focused owner/token, on-screen visibility, interactive/keyguard и IME client с @Test и исходным trace
- Не добавлен новый test framework или dependency. Изменены только workflow, scripts/ci и androidTest, поэтому production APK source/build inputs прежние

## Проверка подготовки

- `git apply --check` успешен
- После обратной замены aliases и пяти helper declarations все измененные test-файлы точно совпадают с оригиналами. Это проверяет отсутствие случайного изменения assertions/actions/timeouts
- Python AST проходит
- Четыре host-only control checks с полностью подставленным subprocess прошли: сохранение failure exit, pull после suite, diagnostic timeout/IO error не подменяет результат, отсутствие нормализации экрана и повторного Gradle вызова
- Файл этих проверок: `/private/tmp/feoria-ci-diagnostics-v05/test_host_diagnostics.py`
- Kotlin/Android build и нативный прогон остаются за root, они здесь не объявлены выполненными

Самопроверка нашла реальный дефект первой подготовки host wrapper: ошибка записи after-suite diagnostics могла заменить исходный exit. Исправлено отдельным best_effort и отрицательным host test. Root review также убрал потенциально вмешивающийся uiautomator subprocess до применения patch
