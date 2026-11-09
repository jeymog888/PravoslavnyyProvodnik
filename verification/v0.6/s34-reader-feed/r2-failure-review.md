# 3.4 r2 - узкий разбор отказов

Вердикт: ACCEPT предложенного направления test-only исправления, с повторной проверкой точного patch и целевым запуском. Runtime менять по этим свидетельствам оснований нет. r2 остается FAIL, исправление еще не проверено запуском

## NonText: наблюденная причина

В ReaderFeedFlowTest.kt:302 вызов scroll(feedLineTag(oldLine)) входит в helper performScrollToNode. Logcat фиксирует IllegalArgumentException "Detected multithreaded access to SnapshotStateObserver": previousThreadId=2, currentThreadId=47, поток instrumentation. Стек идет через observeSemanticsReads, LayoutNode.getCollapsedSemantics, обход children, OutputKt.printToStringInner, SemanticsNodeInteraction.fetchOneOrThrow:189 и ActionsKt.scrollToMatchingDescendantOrReturnScrollable:285

Это исключение при тестовом чтении semantics одновременно с наблюдением на другом потоке. Оно не доказывает отсутствие искомой строки, ошибку seek, изменение сохраненного чтения или падение production UI

В exact ui-test 1.7.3 fetchOneOrThrow ветка selectedNodes.size == 1 безусловно вызывает printToString для lastSeenSemantics. Bytecode offsets 161-179 соответствуют успешной ветке; строка 189 начинается с offset 161. Поэтому наличие printToString в стеке нельзя трактовать как форматирование missing-node failure. SnapshotStateObserver проверяет текущий активный поток наблюдения и восстанавливает прежний currentMapThreadId в finally; это не запрет после любого прошлого чтения на main

Минимальный вариант root корректен при подтвержденной видимости retained last Line после уже существующего swipeDown: убрать лишний scroll helper, оставить assertIsDisplayed и assertIsNotSelected на точной строке, прежнюю проверку отсутствия Return, неизменности snapshot/writes после большого tick и явный click. Настоящий жест остается в тесте. Если строка после жеста не видна, предположение не подходит и потребуется точное позиционирование. Само исключение не доказывает, что удаление поиска устранит все возможные гонки semantics

## Два setup с играющим чтением

Exact Foundation 1.7.3 подтверждает: semantic ScrollBy вызывает semanticsScrollBy, затем NestedScrollScope.scrollBy с NestedScrollSource.UserInput. performScrollToNode использует ScrollBy для поиска за пределами текущего viewport. Поэтому считать этот helper программной прокруткой без пользовательского ввода было ошибкой предварительного review: новый onPreScroll вправе синхронно приостановить чтение уже на setup

Для aRejectedLineChoiceCannotTurnBoundaryPauseIntoSuccessfulFocus и oldLineReturnAndAdjacentCallbacksCannotTouchTheReplacementStateOrViewport допустимо заменить только setup на ScrollToIndex с точным индексом канонического feed item. LazyLayoutSemanticsModifierNode$updateCachedSemanticsValues$3$2 вызывает LazyLayoutSemanticState.scrollToItem, обходя цепочку semantic ScrollBy/UserInput. Сохранить прежние playing/writes/offset assertions и добавить явное свидетельство playing перед вызовом старых callback. Реальные gesture-тесты не заменять этим setup

Это подтверждает механическую ошибку предпосылки теста. Исходные два assertion failure изучал root; мой независимый вывод здесь ограничен проверкой точного пути Compose и соответствием предложенного исправления тестов их цели

## Первичные локальные свидетельства

- Logcat: app/build/outputs/androidTest-results/connected/debug/Liturgy_API_35(AVD) - 15/logcat-ru.liturgyguide.ui.ReaderFeedFlowTest-nonTextPositionsStayOnTheirContinuationUntilAnExplicitOldLineIsChosen.txt, исключение от строки 144
- Exact cached Foundation 1.7.3: /private/tmp/liturgy-android-toolchain/gradle-home/caches/transforms-4/57091831924c5ba8a5216e782a279c14/transformed/foundation-release/jars/classes.jar, SHA256 30ad4cfbb0cd76c75210223803d6d290eb80ee1183aa2608561be0614bab73e9
- Exact cached ui-test 1.7.3: /private/tmp/liturgy-android-toolchain/gradle-home/caches/transforms-4/ba79328783190d27dfcff8bfeddd6af9/transformed/ui-test-release/jars/classes.jar
- Exact cached runtime 1.7.3: /private/tmp/liturgy-android-toolchain/gradle-home/caches/transforms-4/0ef57ffb08c0d0e97c065fd34938fba1/transformed/runtime-release/jars/classes.jar
- Дизассемблирование: /private/tmp/feoria-v06-feed-failure-SemanticsNodeInteraction.txt, /private/tmp/feoria-v06-feed-failure-Actions.txt, /private/tmp/feoria-v06-feed-failure-SnapshotStateObserver.txt
- Цепочка ScrollBy: /private/tmp/feoria-v06-feed-failure-ScrollableNode$setScrollSemanticsActions$1$1.txt и /private/tmp/feoria-v06-feed-failure-ScrollableKt$semanticsScrollBy$2$1.txt
- ScrollToIndex: /private/tmp/feoria-v06-feed-failure-ScrollToIndex.txt

Версии проверены по META-INF *.version. Анализ read-only, без Gradle/adb, изменений runtime/tests или заявления PASS. Новые language и geometry сценарии отмечены root как PASS текущего прогона; это не заменяет исправление и повтор трех отказов

## r3 test-only correction - ACCEPT, NOT RUN

Проверен полный /private/tmp/feoria-v06-reader-feed-r3-correction.diff. Новый positionAtLine выбирает единственный track с заданной PlayerLine, получает точный индекс через ReaderFeed.lineIndex, вызывает ScrollToIndex, проверяет true, дожидается idle и требует displayed точной строки. Это устраняет пользовательский ScrollBy из двух setup с playing, не заменяет проверяемый click или старые callback

В NonText настоящий swipeDown сохранен и теперь сразу проверяется unchanged и отсутствие Return. Затем точный индекс только подготавливает доступ к старой строке. Прежние notSelected, большой tick без изменения snapshot/writes, явный выбор строки, paused Text и недоступность next_line сохранены. Нет заявления, что один жест обязательно показывает старую строку или что причина гонки уже устранена

В stale-callback сценарии добавлен явный assertTrue(isPlaying) непосредственно перед сохранением состояния и вызовом старых действий. Все прежние итоговые assertions остаются

Независимо проверены SHA и размеры всех 13 файлов, aggregate 89256cedf577cff14c20f886246e4669d35aab89b3ae602793597e5d81d2f85c. ReaderFeedFlowTest SHA 6f2b026084b99e6087efa75ff65eba9ea7f55edaebfb84236e5a79e50bf4292e. Остальные 12 файлов побайтно соответствуют r2 freeze. Обратное применение ровно переданного diff к текущему тесту дает прежний r2 SHA a0390708e0900989ce90ac5eb24686c6870a7fe21495ad86747bf7e5cabba2d7

Блокеров в delta нет. Достаточен целевой повтор трех измененных методов; это приемка изменения тестов, не результат запуска. r2 116/119 PASS и три отказа остаются в истории
