# 3.2 - два отказа выбора текста и точечная правка теста

Исходный `s32-content-ui-r1` остается FAIL: 28 из 30 сценариев прошли, два ExpandedContentFlowTest остановились в selectTrack -> scroll -> assertIsDisplayed. Цель находилась в semantics, но не была видна. Другие проверки содержимого не падали

Первый запуск получил feoriaCiDiagnosticsRun без feoriaCiDiagnostics=true, поэтому диагностический guard не сработал и экспорт 0 packets ожидаем. Это ошибка вызова диагностики, не доказательство отсутствия UI проблемы. Root сохранил r1 и выполнил отдельный неизмененный диагностический запуск с обоими аргументами

## Установленная причина

Root, implementer и QA просмотрели `s32-content-ui-diagnostic/ui-diagnostics/first-failure-1794183249684/screen.png`. В анафоре панель выбора открыта частично, ее верх примерно на половине изображения 1080x2400. Выбран "Свят Господь"; ниже у границы видны "Слова Тайной вечери", а следующая цель теста "Приношение даров" находится за экраном. Точных Compose bounds в packet нет

Helper умел прокручивать список, но не устанавливал необходимое для нижних пунктов состояние раскрытия панели. Для новых 7/8-track списков программный scroll LazyColumn недостаточен. Локальный Material3 1.3.0 подтверждает реальное действие Expand именно в PartiallyExpanded, которое вызывает sheetState.expand(). Это проверено по runtime classes.jar SHA `0bbf0998c5e99e28090a43c15372357fe83d67a8baa217e3d7e8c410c4ce56b7`

Доказательств необратимого продуктового clipping после пользовательского раскрытия нет. Production менять только ради теста не требуется. У второго localOmissions отказа тот же helper/stack trace, но отдельный PNG этого отказа не сохранен; окончательно переносить вывод на него можно только после нового прогона

## Одобренный и выполненный delta

Root разрешил единственную вставку в private selectTrack файла ExpandedContentFlowTest.kt после choose_track:

1. Найти узлы с существующим SemanticsActions.Expand и строго потребовать один
2. Проверить его видимость, вызвать реальное действие и проверить возврат true
3. Дождаться Compose idle и выполнить прежние scroll/assertIsDisplayed/click

Никаких sleeps, retry, fallback, ослабления assertion или прямого изменения sheetState/player. Production, остальные тесты и репозиторные документы не менялись

Предыдущий полный freeze сохранен как `/private/tmp/feoria-v06-content-freeze-before-ui-fix.json`. В текущем `/private/tmp/feoria-v06-content-freeze.json` изменен только SHA ExpandedContentFlowTest.kt: `572fbd9779d268945e970777311a9b7dc653a8a3dd5e27e6fe698f3eca54b02e` -> `b6ab63c402bf293481f824cd4030f7ad811fde85c39523815f73dd16634eb443`. Остальные 13 файлов сверены с прежними SHA

Diff check чистый. Gradle/adb implementer и QA не запускали. Правка еще не выполнена тестом; источник остается SOURCE FREEZE до root/Astra delta review и одного целевого повторного прогона

Подробный read-only отчет QA: `/private/tmp/feoria-v06-content-ui-qa-failure.md`. Bytecode чтения: `/private/tmp/feoria-v06-modal-sheet-semantics-javap.txt`, `/private/tmp/feoria-v06-modal-sheet-expand-javap.txt`
