# Независимый preflight Феория 0.5

Проверен HEAD `57e48a5f9af879e196ac17ee517fcee0108d0800`, исходники приложения и androidTest, настоящий JUnit XML и per-test logcat из `/private/tmp/feoria-ci-v05-run42508996635-attempt1/artifact`

Вывод: доказанного дефекта production нет. Сильнее подтверждается гонка наблюдения готовности UI/IME, но причинная связь IME с исчезновением конкретной lazy node еще не доказана. Нельзя объявить приложение исправным по одному отсутствующему node и нельзя объявить этот CI зеленым. Текущий результат остается 119/120 UI, 1 FAIL

## Что установлено

- JUnit XML: `cancellingARequestIgnoresItsLateResultAndLeavesManualNavigationAvailable`, строка 88 теста, helper `scrollTo`, строка 237. Ошибка в fetch перед `performScrollTo`, когда tag Казани не найден
- Первый `performScrollToNode` на контейнере уже вернулся. Это означает, что action нашел цель в ходе своей работы, но не гарантирует неизменность viewport и composition после возврата
- `oldResult.complete` стоит на следующей строке 89. Старый запрос еще не отдал результат. Отказ не доказывает, что он перезаписал новый результат
- В production `begin` увеличивает generation через `cancel`, а ответ и finally проверяют `current(token)`. Старый запрос после cancel и нового поиска не может пройти этот guard. По прочитанному коду основания менять его нет
- Production `LaunchedEffect(state.resultVersion)` запускает `animateScrollToItem(1)` при начале и завершении поиска. `imePadding()` одновременно меняет доступный viewport. Это два возможных участника движения списка; один stack trace не позволяет приписать исчезновение конкретному участнику
- Проверка hidden получает единственный sample `!isVisible(ime) && bottom == 0`, затем вызывает OS idle и Compose idle, после чего проверяет отсутствие focus. Повторной проверки IME на выходе нет. Отсутствие Compose focus не равно отсутствию уже поставленного OS show

## Точная хронология IME

В per-test logcat:

- 18:23:08.616 и .640 - два `ATTACH_NEW_INPUT` show request, строки 354 и 360
- 18:23:09.050 - `19c30d46: onHidden`, строка 422
- 18:23:09.442 - `8e6320da: onShown`, строка 477, через 392 мс после onHidden
- 18:23:09.630 - PAUSED, строка 519, начинается cleanup Activity
- 18:23:09.899 - `InsetsController: show` и `27269751: onCancelled`, строки 551-552, через 849 мс после onHidden и уже в cleanup

Важно: последнее событие не является вторым успешным `onShown`. Время исходного AssertionError нельзя брать из строки TestRunner 18:23:11.321: JUnit печатает ее уже после teardown

## Ближайшая порция: сначала один узкий диагностический патч

Предпочтительный вариант при замороженном release APK: только два файла androidTest, без изменений production, assertions, input flow, scroll flow и timeout

1. В `ChurchDiscoveryScreenTest` обернуть тело только failing теста существующей диагностической оберткой внутри внешнего `try`, то есть сбор должен выполняться до `finally { oldResult.cancel() }`. Обертка должна включать обе операции scroll и следующий `assertIsDisplayed`, иначе при удалении/переносе второго scroll та же граница снова останется без диагностики
2. Добавить локальный `phase` с присваиваниями перед первым search, cancel, вторым search, первой проверкой Казани, complete старого ответа, второй проверкой Казани и manual navigation. В момент catch сохранить phase, target tag, `fake.queries.toList()`, `fake.centers.toList()`, `oldResult.isCompleted/isCancelled`, время wall/uptime. Это несколько строк в одном тесте, не новый framework
3. В `CiFailureDiagnostics` добавить необязательный callback дополнительных файлов с уже созданной директорией. Вызывать только при первом failure и включенном существующем флаге. Отдельно оградить каждую часть `runCatching`, чтобы ошибка diagnostics не заменила исходную ошибку и не прервала оставшийся сбор
4. До медленных dumpsys снять быстрый platform sample через `scenario.onActivity`: IME visible/bottom, decor width/height и window focus. Сохранить screen.png как можно раньше, затем merged и unmerged trees через `compose.onRoot(...).printToString()`, затем существующие OS dumps. Записать времена каждой части. Сбор не атомарен; tree API может синхронизировать Compose, поэтому поздний tree не объявлять точным снимком состояния до assertion
5. В catch всегда `throw failure` с тем же объектом Throwable. Не выполнять дополнительный scroll, hide, pressBack, tap или повтор assertion. Не вызывать complete старого ответа из diagnostics

Форма размещения:

```kotlin
try {
    withCiFailureDiagnostics(
        source = "discovery.cancelLateResult",
        // Необязательный callback записи коротких test-specific данных
        additionalEvidence = { directory -> /* phase, insets, trees */ },
    ) {
        // Нынешнее тело без изменения действий и проверок
    }
} finally {
    oldResult.cancel()
}
```

В существующем helper callback должен быть перед `block`, чтобы сохранить trailing-lambda call sites. Изменять Unit-return helper на generic ради одного теста не требуется

## Почему не начинать с удаления второго scroll

Контракт `performScrollToNode` уже предусматривает приведение цели в viewport. Второй scroll обычно лишний, и его можно отдельно убрать как упрощение после установления готовности UI. Но текущая ошибка возникает еще до его прокрутки, в повторном поиске node. Следующий `assertIsDisplayed` тоже ищет node заново. Удаление action может только изменить timing или перенести failure в assertion; оно не доказывает устранение причины

Нельзя заменять `assertIsDisplayed` на `assertExists`, искать текст вместо исходного tag, разрешать отсутствие Казани или повторять scroll до успеха

Для дополнительной проверки контракта прочитан официальный AndroidX [Actions.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui-test/src/commonMain/kotlin/androidx/compose/ui/test/Actions.kt). Это текущая ветка AndroidX, а не точный source artifact используемой Compose версии; она не служит доказательством конкретной реализации бинарника CI

## Если следующий packet подтвердит готовность как причину

Тогда отдельная маленькая правка `waitForHiddenKeyboard` должна наблюдать состояние, а не выполнять восстановление:

- Сохранить нынешний общий timeout 10 секунд
- После внешнего idle еще раз читать IME visible/bottom и layout geometry; окончательная hidden-проверка должна происходить после последней синхронизации, а не до нее
- Если нужен период устойчивости, принимать только последовательные наблюдения hidden + нулевого inset + неизменной геометрии, сбрасывая последовательность при каждом изменении. Не добавлять безусловный sleep, retry действия или повторный hide
- Сам по себе фиксированный quiet period, даже 1 секунда, не доказывает, что в другой системе нет более позднего queued show. Не объявлять его гарантированным решением по двум временным меткам этого лога
- Если packet показывает возвращение IME после завершения осмысленного наблюдения, потребуется уточнение input/focus handoff. `performTextReplacement` и `performImeAction` участвуют в получении focus, а не только в присваивании строки/вызове callback. Не обходить production search через прямой вызов callback ради зеленого теста
- Если IME скрыт и viewport стабилен, а цель отсутствует при данных нового поиска, изучать автоматический scroll/resultVersion и текущий tree. Runtime менять лишь после конкретного воспроизведения поведения пользователя или доказанного ошибочного state transition

Официальный текущий [TextActions.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui-test/src/commonMain/kotlin/androidx/compose/ui/test/TextActions.kt) подтверждает участие focus в text/IME actions; точное количество OS show requests этим не объясняется

## Предел следующей проверки

Один локальный запуск класса для проверки компиляции и неизменности сценариев, затем один полный CI на точном commit с включенной диагностикой, если root решит запускать. Полный suite важен: отдельный тест не воспроизводит предшествующую нагрузку и порядок

Если полный CI PASS, сохранить результат как новый PASS на новом test-only commit, но не писать, что первопричина исправлена. Предыдущий FAIL и границу доказательств оставить в отчете

Если FAIL, работать только по первому packet. Не запускать второй раз без нового объясняющего факта и отдельного конкретного исправления. Если packet не собран, исправить ровно эту границу сбора, не разворачивать большой диагностический framework

Этот preflight не запускал adb, Gradle, CI и не менял репозиторий. Создан только данный отчет в `/private/tmp`
