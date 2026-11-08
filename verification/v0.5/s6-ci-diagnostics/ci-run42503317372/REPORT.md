# GitHub CI 0.5: attempt 1 завершен с ошибкой

Run: https://github.com/jeymog888/PravoslavnyyProvodnik/actions/runs/42503317372
Head: `03554e613ad141b3473a5ed5cde1972f4e5b23f4`, attempt 1, workflow_dispatch
Job 128800543789: 17:07:04Z - 17:29:30Z, conclusion=failure. Это не общий timeout job

## Фактические результаты

| Проверка | Результат |
|---|---|
| Unit | 335 tests, 0 failures, 0 ignored, 100% successful по HTML report |
| UI | 119 tests: 59 PASS, 60 FAIL, 0 errors, 0 skipped по настоящему JUnit XML |
| Lint | 0 errors/fatal, 14 warnings по lint XML |
| Build | test/lint/build step success; unit task реально выполнялась, не FROM-CACHE |
| APK upload | Skipped вследствие UI failure |

Workflow не выгружает unit JUnit XML, поэтому unit результат подтвержден HTML, а не выдуманным XML. UI XML timestamp17:29:16Z попадает в этот job, artifact создан17:29:26Z после выполнения тестов

Artifact verification-reports: ID13626787820, zip1711232bytes. SHA256 `f638d596811f5f668d257011f394bcabbdd2c88a5b534472bc9ee7cfa7af9ffc` совпал с GitHub API digest. Скачан конкретный artifact данного run/head, старые отчеты не переиспользованы

## Разбивка 60 UI failures

- 45 RootViewWithoutFocusException: видимый DecorView 1080x2400 имеет has-window-focus=false
- 6 таймаутов ChurchDiscoveryScreenTest.waitForVisibleKeyboard:210
- 1 таймаут клавиатуры GuideNavigationTest:93
- 7 таймаутов ReaderAccessibilityTest.awaitPlatformReady:338
- 1 таймаут CompactReaderMatrixTest.orient:293

Все ошибки относятся к input/window/platform readiness, но это не доказывает единственную общую первопричину. В сохраненных 119 per-test logcat не найдено FATAL EXCEPTION/ANR целевого приложения; полного независимого системного crash отчета нет

## Проверка гипотезы screen timeout / keyguard

Гипотеза не подтверждена. Первый явный отказ IME происходит17:14:10.321, примерно через33сек после начала UI-suite и79сек после runner keyevent82. Это не только поздняя деградация длинного прогона

В первом failed случае Activity RESUMED17:14:09.471, Displayed17:14:09.810, IME onRequestShow17:14:10.321 сразу заканчивается onFailed PHASE_CLIENT_REQUEST_IME_SHOW. PAUSED появляется только в teardown17:14:20.514. Пример позднего RootViewWithoutFocus также RESUMED/Displayed до отказа. RESUMED сам по себе не доказывает window focus

Job log содержит boot property `androidboot.qemu.settings.system.screen_off_timeout=2147483647` на строке572. Фактическое runtime значение этой настройки и stay-on состояние не сняты. Сохраненных window/power/keyguard/input-method dumps и PNG при первом отказе нет; в per-test logs нет перехода PowerManager/DisplayPower/Keyguard/Dream, который подтвердил бы сон или блокировку. Не считать отсутствие таких логов доказательством постоянно включенного экрана

Проверен именно pinned код action a421e43855164a8197daf9d8d40fe71c6996bb0d: после sys.boot_completed он отправляет keyevent82 и настройки анимаций, но не проверяет фактические keyguard/focus/power состояния и не задает stay-awake. [Первичный источник runner](https://raw.githubusercontent.com/ReactiveCircus/android-emulator-runner/a421e43855164a8197daf9d8d40fe71c6996bb0d/src/emulator-manager.ts)

## Минимальное следующее изменение для root

Причина отсутствия window focus по имеющимся артефактам не установлена, поэтому нет доказанного runtime fix приложения. Не убирать assertions focus/IME, не увеличивать их timeout и не принимать простой rerun за диагностику

1. Добавить ограниченный preflight среды CI: записать фактические screen_off_timeout, stay_on_while_plugged_in, show_ime_with_hard_keyboard, dumpsys power/window/input_method/activity и PNG. Для собственного AVD явно вызвать KEYCODE_WAKEUP и wm dismiss-keyguard, затем проверить наблюдаемые Awake/interactive/отсутствие keyguard. Если требуется удержание экрана, явно настроить svc power stayon true и проверить resulting state. Это нормализация условия, пока не доказанное исправление причины этого run
2. У первого реального отказа window/IME readiness снять те же dumps и screenshot ДО закрытия Activity. Съемка только после завершения Gradle будет поздней: Activity уже уничтожена. Диагностику разместить в ближайшем wait/error path или заведомо внутреннем по отношению к Activity cleanup test wrapper
3. По captured focused-owner/token, keyguard и power состоянию выбрать точное исправление: sleep/keyguard - удержание и разблокировка тестового AVD; чужое окно - установить его происхождение и закрыть конкретный setup/OS overlay. Если окно приложения сфокусировано, разбирать отдельно IME/rotation condition. Ничего из этих ветвей пока не объявлено причиной
4. После минимальной проверяемой поправки повторить полный suite с теми же assertions; новый результат хранить отдельно, сохранив failure attempt1

Пробел выгрузки unit XML можно закрыть отдельным path app/build/test-results/testDebugUnitTest/, но он не является причиной этих60 UI failures

## Сохранено локально

- ci-verification.json: итог, происхождение artifact, counts, границы диагноза
- ui-failures.json: все60 имен тестов и конкретные project stack frames
- job.log, run.json, jobs.json, artifacts.json: исходные GitHub данные
- verification-reports-13626787820.zip и artifact/: оригинал и безопасно распакованные reports/logcat
- sha256-manifest.json: хеши доказательств

Только read-only GitHub и файлы /private/tmp. Repo/CI не менялись; rerun/cancel/adb/Gradle не выполнялись. Работа0.6 не начиналась
