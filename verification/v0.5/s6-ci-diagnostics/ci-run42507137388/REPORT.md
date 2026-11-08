# CI 0.5: независимая проверка успешного диагностического прогона

ACCEPT результата этого CI-прогона. Причина отказов предыдущего прогона не установлена

Run: https://github.com/jeymog888/PravoslavnyyProvodnik/actions/runs/42507137388

Head: `9bcbf44b00fc07c76229236501ddb0f637fcb5c1`, attempt 1. Job `128812094493`, conclusion `success`

## Проверенные результаты

| Проверка | Фактический результат |
|---|---|
| Unit | 335 уникальных testcase в 24 JUnit XML, failures/errors/skips = 0 |
| UI | 119 уникальных testcase в настоящем JUnit XML, failures/errors/skips = 0 |
| Lint | 0 errors/fatal, 14 warnings по lint XML |
| Build | test/lint/build завершен успешно; testDebugUnitTest реально выполнялся, не FROM-CACHE |
| UI command | Один полный connectedDebugAndroidTest с диагностическими аргументами, exit 0 |
| Crash markers | В 119 per-test logcat нет FATAL EXCEPTION или ANR in ru.liturgyguide; это не полный системный crash audit |

Unit XML timestamps 17:56:39-17:56:43Z; UI XML 18:09:58Z. Журнал подтверждает BUILD SUCCESSFUL обоих этапов и Finished 119 tests

## Происхождение артефакта

Скачан именно `verification-reports`, ID `13628153514`, из run `42507137388`, с тем же head. Размер zip 4254024 байта

SHA256 `6ffbf0cd8cc8ad2c11cb460139744b715df68c401491cb9d4a0dc8039dce24a0` совпал с GitHub API digest. Архив распакован после проверки путей и отсутствия symlink entries. Сверены aggregate counts с реальными testcase и отсутствием failure/error/skipped элементов, а не только с текстовым статусом job

Сохранены исходные run.json, jobs.json, artifacts.json, job.log, zip и artifact/. app-debug опубликован как CI artifact, но не выдавался за подписанный release APK и здесь не проверялся

## Что показала новая диагностика

Preflight: screen_off_timeout=2147483647, stay_on_while_plugged_in=0, show_ime_with_hard_keyboard=1. Power dump: Awake, mWakefulnessChanging=false, mHalInteractiveModeEnabled=true, mStayOn=false. Policy: showing=false. Focus в displays: com.android.settings/.FallbackHome. Preflight PNG черный; по нему нельзя объявлять сон, так как наблюдаемый power state был Awake/interactive

After-suite: timeout и IME setting прежние, stay_on_while_plugged_in=1, mStayOn=true, Awake/interactive, keyguard showing=false. Focus принадлежит NexusLauncher, PNG показывает launcher. Причина изменения stay-on в рамках suite независимо не установлена

В commands.json 25 записей. Единственный ненулевой код - pull-first-failure: путь отсутствует. При 119 PASS без ошибок это ожидаемое отсутствие first-failure packet, а не скрытая ошибка теста. suite-result.json сохраняет исходный Gradle exit 0. Все остальные диагностические команды завершились с exit 0

Новый patch только измеряет окружение и перехватывает исходные ошибки, не будит/не разблокирует устройство и не меняет assertions/actions/timeouts. Дополнительное время на измерения могло изменить условия запуска, но причинность не установлена. Нельзя утверждать, что был исправлен screen timeout, keyguard или конкретный overlay предыдущего прогона

## Граница приемки

Подтвержден один полный успешный CI-прогон на Android 15/API35 с текущими 335 unit и 119 UI тестами, без исключений и пропусков. Предыдущий failed run остается отдельным доказательством неизвестной нестабильности окружения; успех не объясняет его причину

Этот результат не заменяет native QA подписанной сборки, проверку обновления 0.4->0.5, offline/lifecycle evidence, совместимость других API или акустические измерения

Reviewer выполнял только авторизованные read-only GitHub запросы и обработку файлов в /private/tmp. Не отменял и не перезапускал CI, не запускал adb/Gradle и не менял репозиторий
