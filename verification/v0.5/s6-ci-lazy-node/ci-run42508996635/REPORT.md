# CI 0.5: один отказ UI в полном прогоне после Recents fix

Итог этого прогона FAIL. Не подменять его предыдущим успешным CI или локальным PASS

Run: https://github.com/jeymog888/PravoslavnyyProvodnik/actions/runs/42508996635

Head: `57e48a5f9af879e196ac17ee517fcee0108d0800`, attempt 1. Job `128817759206`, conclusion `failure`

## Фактические результаты

| Проверка | Результат |
|---|---|
| Unit | 336 уникальных testcase, failures/errors/skips = 0 |
| UI | 120 уникальных testcase: 119 PASS, 1 FAIL, errors/skips = 0 |
| Lint | 0 errors/fatal, 14 warnings |
| Build | Unit/lint/build step успешен; отказ на полном UI-suite |
| APK upload | Не выполнен после UI failure |

Counts сверены по настоящим JUnit XML и их testcase/failure/error/skipped элементам. В частности, новый `losingTopResumedPausesSynchronouslyEvenWhenLifecycleStaysResumed` прошел. В 120 per-test logcat нет FATAL EXCEPTION или ANR in ru.liturgyguide; это не полный системный crash audit

## Происхождение доказательств

Скачан точный `verification-reports`, ID `13629117251`, размер 4023297 байт, создан 18:30:31Z для указанного run/head

SHA256 zip `2705da93a7212df39a706a5ab5b581ecf26f75a273a4827cf2aa68a72fefa6c8` совпал с GitHub API digest. Перед распаковкой проверены пути и отсутствие symlink entries. Сохранены run.json, jobs.json, artifacts.json, job.log, zip, artifact/ и ui-failures.json

## Точный отказ

`ChurchDiscoveryScreenTest.cancellingARequestIgnoresItsLateResultAndLeavesManualNavigationAvailable`

`Action performScrollTo() failed. Expected exactly 1 node but could not find any node that satisfies TestTag = discovery_city_osm:relation:1`

Stack: `ChurchDiscoveryScreenTest.scrollTo:237`, вызов из теста на строке 88. Это первая проверка Казани после второго поиска, ДО `oldResult.complete` на строке 89. Следовательно, отказ не доказывает применение устаревшего ответа: эта часть сценария еще не наступила

В helper первая `performScrollToNode` вернулась; последующая `onNodeWithTag(...).performScrollTo()` не смогла получить composed node. Это другой класс ошибки, чем 45 RootViewWithoutFocus и readiness timeouts исходного run42503317372. В текущем прогоне их нет

## Журнал первого и единственного failed теста

Файл: `artifact/outputs/androidTest-results/connected/debug/emulator-5554 - 15/logcat-ru.liturgyguide.ui.ChurchDiscoveryScreenTest-cancellingARequestIgnoresItsLateResultAndLeavesManualNavigationAvailable.txt`

- Activity RESUMED 18:23:06.182, Displayed 18:23:06.746
- Второй ввод: серверные ATTACH_NEW_INPUT show requests 18:23:08.616 и 18:23:08.640, строки 354/360
- Запрос hide `19c30d46` завершился onHidden 18:23:09.050, строка 422
- Ранее поставленный show `8e6320da` затем завершился onShown 18:23:09.442, строка 477
- Activity PAUSED в cleanup 18:23:09.630, строка 519
- Еще один show/cancel зафиксирован 18:23:09.899, уже после начала cleanup
- JUnit выводит исходный AssertionError 18:23:11.321, после teardown

Наблюдается поздний показ IME после скрытия. Он согласуется с изменением viewport и исчезновением lazy node между двумя scroll-операциями, но это пока гипотеза причинной связи: нет semantics tree/PNG непосредственно перед AssertionError. Данные не устанавливают потерю результатов в product state или конкретное состояние клавиатуры в момент fetch

## Почему нет first-failure packet

Существующая диагностика охватывает доказанные пути IME/platform waits и Espresso closeSoftKeyboard/pressBack. Здесь эти проверки завершились, а ошибка возникла в `performScrollTo()` вне обертки. Поэтому packet не появился; pull сообщает отсутствие каталога. Это известная граница диагностики, не доказательство нормального состояния в момент assertion

Preflight и after-suite dumps не заменяют отсутствующий first-failure packet

## Сравнение с успешным run42507137388

Оба прогона показали одинаковые существенные preflight facts: timeout2147483647, stay-on0, IME1, Awake, Changing=false, interactive=true, keyguard showing=false, focus Settings/FallbackHome

После обоих suite: stay-on1, Awake/interactive, keyguard showing=false, focus NexusLauncher. Причина изменения stay-on не установлена. По этим измерениям нет основания объявлять sleep, keyguard или screen timeout причиной единственного нового отказа

В успешном прогоне 335/119 PASS, в текущем 336 unit PASS и119/120 UI PASS. Новый Recents regression прошел; нельзя автоматически приписать этот отказ runtime-изменению Recents

## Минимальный следующий диагностический шаг

Если потребуется дополнительный запуск, обернуть именно `scrollTo` catch -> сохранить semantics tree/PNG и существующие OS diagnostics -> rethrow исходный Throwable ДО внешнего finally. Отдельно фиксировать состояние IME/viewport в этом месте. Не выполнять повторный scroll/tap, не ослаблять assertIsDisplayed и late-result assertions, не увеличивать timeout и не менять runtime по недоказанной гипотезе

Этот отчет не санкционирует повторный запуск. Reviewer не запускал adb/Gradle, не отменял/не перезапускал CI и не менял репозиторий. Только read-only GitHub и файлы /private/tmp
