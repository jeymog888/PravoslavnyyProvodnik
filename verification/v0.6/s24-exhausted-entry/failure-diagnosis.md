# Диагностика 2.4 r1 - первый вход в ручной выбор

Статус: причина отсутствия MANUAL не установлена по имеющимся артефактам. Runtime regression от navigation guard не доказана

Независимо прочитаны XML результата и logcat метода choosingAnotherLandmarkShowsItsNewCueBeforeConfirmation в app/build/verification/v0.6/s24-exhausted-r1. Стек указывает ManualMomentFlowTest.kt:103 -> landmark:328, запрос manual_little_entrance. Это первый landmark сразу после findFromMain:102, а не повторный landmark после Back:106-107. Поэтому объяснение "сломался ARRIVAL -> MANUAL" не соответствует наблюдаемому отказу

Доказано только: performScrollToNode(find_stage) и performClick(find_stage) завершились без исключения; следующий поиск manual_content не нашел узел. Это не доказывает доставку onClick нужной кнопке, начало MANUAL или последующий recoverArrival. В run нет PNG/полных semantics этого отказа. Logcat не содержит пользовательского состояния screen/draft. PAUSED/STOPPED/DESTROYED перед строкой ошибки относятся к teardown test rule и не доказывают самопроизвольный уход Activity до клика

По коду seed:301-312 дает допустимое legacy чтение и bundled default parish через ChurchSelection.resolve. openManual:351-355 создает непустое имя, совпадающий churchId, NEW_SERVICE/MANUAL без pending. ArrivalDraft.isValidFor для этого обычного draft должен пройти. Начальный player не принимает legacyCandidate автоматически на MAIN, поэтому pause не меняет church/context. Новые identity guards относятся к callbacks уже открытых MANUAL/ARRIVAL/scoped SEARCH и до первого открытия не вызываются. Удалять их или ослаблять восстановление по текущим данным не обосновано

Отличие конкретного метода - ограниченный Box 320x320 (строки97-100). findFromMain:320-323 прокручивает к кнопке и сразу выполняет координатный performClick. После 2.3 кнопка находится внутри большой unknown-schedule карточки, рядом с нижней фиксированной навигацией. Возможен недоставленный/перекрытый тап из-за геометрии или незавершенного размещения, но без кадра/bounds это только рабочая гипотеза, не установленная причина. Успех остальных 8 ManualMomentFlowTest в r1 поддерживает узкую проверку этого viewport, но сам по себе не исключает runtime ошибку

Минимальный безопасный следующий шаг - diagnostic-only wrapper данного метода: снимок и полные semantics roots при failure, зафиксировать bounds/displayed find_stage и main_content непосредственно перед первым кликом, сразу после требовать manual_content и сравнить preferences. Выполнить один целевой метод с неизменными действиями. Если подтвердится геометрия, исправление выбирать по наблюдению: доступность реальной кнопки в маленьком viewport либо тестовое точное доведение нужного действия в видимую область. Не заменять внешний click на прямой callback ради PASS и не ослаблять последующую проверку нового cue

Три исходных FAIL r1 сохраняются. Здесь другие два отказа не анализировались по поручению root. Ранее ACCEPT code review не объявляет приемку 2.4 после отрицательного прогона. Для этого нужны разрешение failures и последующий целевой результат

Source/tests не менял, Gradle/adb не запускал


## Узкий test-only diff после r1

ACCEPT диагностического и harness исправления: MainScheduleOverviewTest теперь явно проверяет substring длинного пояснения. ExhaustedServiceEntryTest.corruptOneDraft сохраняет исходный MutableState, когда преобразование не меняет его value, поэтому посторонний MutableIntState не заменяется обычным mutableStateOf; изменение ровно одного draft по-прежнему проверяется. ManualMomentFlowTest сохраняет прежний scroll/click, добавляет before-find-click полные roots и failure roots/PNG, не заменяет действие на прямой вызов callback и не ослабляет проверки cue. Runtime SHA всех пяти файлов совпали с freeze

Можно выполнять три целевых метода. Диагностическое чтение semantics может изменить тайминг, поэтому один PASS не докажет исправление исходной причины отсутствия MANUAL


## Независимая сверка сохраненной диагностики и точечной правки

ACCEPT test-only исправления ManualMomentFlowTest.kt:114: performScrollTo().assertIsDisplayed() перед прежним performClick

Просмотрен настоящий screen.png из s24-exhausted-diagnostic/diagnostics/first-failure-1794178417952, прочитаны before-find-click.txt, compose-roots.txt и capture.json. MAIN остается показанным; кнопки поиска на видимой части карточки нет. Деревья до и после клика побайтно равны. main_content занимает y136..567, find_stage - y716..853, то есть кнопка полностью ниже viewport. Большой containing item занимает y136..1064. Это подтверждает недостаточную прокрутку до дочерней кнопки в конкретном тесте: performScrollToNode материализовал большой lazy item, но целевое действие не стало видимым

Новый performScrollTo доводит саму кнопку до видимой области, assertIsDisplayed проверяет предпосылку реального тапа. Прямого вызова callback нет, размеры320x320, исходный click и последующая проверка нового cue сохранены. Это исправляет подтвержденное условие теста и не ослабляет navigation guard. Все пять runtime SHA вновь совпали с freeze

Прочитаны inputs.json/events.json: диагностика отдельно запускала один метод с повторно установленными APK/testAPK из отрицательного r2, parent экспорта предварительно создан, результат помечен DIAGNOSTIC_ONLY. Это не новый PASS build и не потерянный screenshot из r2. Сама эта проверка не объявляет исправленный метод прошедшим: нужен r3 ровно этого метода
