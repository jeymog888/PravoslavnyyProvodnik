# 3.5 r2 - предварительный read-only разбор двух отказов

Оба отказа реальные. Причинность пока установлена не полностью: текущий прогон еще идет, PNG и semantics export этих точек пока не доступны локально. Не предлагаю снижать assertions или выдавать повтор за исправление

## browsingAnotherHeaderDoesNotAcceptItOrChangeTheMeaning

Logcat фиксирует assertIsDisplayed на ReaderFeedFlowTest.kt:45 после первого выбора "По-русски". Перед этим строка other.lines[0] была displayed, затем нажат tab. Это не stack exception и не доказательство записи player

Код preserveLanguageAnchor сохраняет key firstVisibleItemIndex. Он не выбирает произвольную другую Line, которая также видна ниже. После изменения языка LaunchedEffect выравнивает именно сохраненный key к offset0. Тест до смены языка делает произвольный swipe на10% viewport, но не проверяет, что firstVisibleItem является other.lines[0]. После уменьшения шапки геометрия viewport другая, поэтому это прежнее предположение setup больше не гарантируется

Конкретная гипотеза для диагностики: первым видимым item стал Header "Приношение даров", содержащий длинную contextNote. Восстановление начала этого Header может увести нижележащую строку за экран. Это соответствует принятому контракту3.4 (Header допустим как semantic anchor), но пока не доказано для данного отказа. PNG/semantics первого отказа должны показать, восстановился ли этот Header и где осталась Line. Для окончательного различения нужно before first visible key/bounds; одного исчезновения другой видимой Line недостаточно

Если гипотеза подтвердится, минимальное исправление - сделать предпосылку теста явной: выбрать/проверить нужный anchor, оставить language/state/write assertions. Если anchor был самой Line и она исчезла, это runtime regression и test-only правка неприемлема

## aRejectedLineChoiceCannotTurnBoundaryPauseIntoSuccessfulFocus

Logcat на ReaderFeedFlowTest.kt:185: before rect [52,95238;229,33333;280;301,33334]dp, after [52,95238;241,14285;280;313,14285]dp. Ширина и высота прежние, всеY сдвинуты на31px при density2,625 (около11,81dp). До сравнения прошли "На паузе · примерное место", displayed и notSelected requested Line

Runtime seekLine сначала вызывает viewport.browse(), поэтому manual=true до mandatory pause. При отказе writer нет requestFocus. Follow LaunchedEffect при manual выходит. Это подтверждает наличие защиты от ложного успешного focus, но не доказывает сохранение абсолютного положения при remeasure

В новой композиции footer status имеет естественную высоту. Playing и paused используют разные строки ("Движение по времени · примерно" и "На паузе · примерное место"). Высота status и всего footer не зарезервирована; зарезервирован лишь Return/Clarify slot. Requested Line находится в последнем track близко к концу ленты. Изменение viewport может вызвать штатный end clamp LazyColumn и переместить ее без seek. Это проверяемая гипотеза, не доказанная точная причина31px. Возможны также изменения распределения строки footer при другой длине primary

Для причинности нужны before/after bounds status, primary, footer, lyrics_list и firstVisible key/offset в этом же действии. Подтвержденное изменение footer/list height при неизменном manual и отсутствии requestFocus укажет на runtime geometry regression. Не заменять точное bounds сравнение более слабым displayed: оно защищает сохранность просматриваемого места после отклоненного выбора

Источники: текущие frozen ServiceScreen.kt (preserveLanguageAnchor, language/follow effects, ReaderFooter), ServicePlayerState.seek/pause и два logcat-ru.liturgyguide.ui.ReaderFeedFlowTest-*.txt в app/build/outputs/androidTest-results/connected/debug/Liturgy_API_35(AVD) - 15. Gradle/adb не запускались, source/tests не менялись

## Оценка предложенных root направлений до диагностики

ACCEPT для узкого диагностического повтора двух исходных ReaderFeed методов: before/after bounds viewport/footer/Line плюс видимый anchor key/offset, исходные assertions остаются. Runtime не меняется до получения наблюдений

Три дополнительных CompactReaderMatrix отказа "найдено2 Text Уточнить" root связал с unmerged semantics измерительного probe. По структуре кода объяснение обосновано: clearAndSetSemantics на внешнем Box скрывает/замещает его merged semantics, но не является удалением Text semantics собственного дочернего узла для unmerged поиска. Минимальная runtime правка - очистить semantics самого измерительного Text, сохранив измерение и реальные подписи. Нельзя маскировать дубликат через first(), ослабление count или смену теста на merged-only

Предварительное продуктовое направление language anchor разумно: только при manual явной смене языка предпочесть первую реально видимую Line, fallback к Header, если Line нет. Это делает сохранение читаемых слов явным. Нужен фактический viewport intersection, а не присутствие любого composed/prefetched item. Не терять Header-only сценарий длинной заметки, owner/generation проверки, отмену по новому drag/намерению и отсутствие seek/pause/play/writer вызовов. Пока это оценка направления, не приемка кода или доказательство причины четырех отказов. Дополнительный CompactReaderMatrix отказ с исчезнувшей принятой строкой нельзя автоматически приписать той же причине без диагностики

## Диагностический r3 и личный просмотр r2 PNG

ACCEPT diagnostic-r3.diff для запуска двух исходных ReaderFeed методов. Сверены все17 SHA/bytes и aggregate1a8eecd60565466fd9708555c306a38382db58b2468d07122f39fa1bb6abbaf2. Изменен только ReaderFeedFlowTest: semantics wildcard import, четыре вызова recordCompositionGeometry и сам read-only helper. Actions/assertions прежние; runtime и остальные16 файлов точныеr2. Helper ждет idle и пишет bounds/text релевантных узлов; он не меняет player или scroll. Как любая диагностика, может менять timing, поэтому возможный PASS не будет доказательством исправления

Лично открыт r2 PNG app/build/verification/v0.6/s35-reader-composition-r2/ui-diagnostics/first-failure-1794191489286/screen.png. Видно выбранное "По-русски", Header "Приношение даров", подпись типа/языка и длинную contextNote, занимающие почти весь viewport. Самой строки молитвы не видно. Видны пауза, primary "Двигать текст" и Return. Это подтверждает наблюденный результат гипотезы Header anchor: после смены языка экран показывает начало длинного Header вместо ранее видимой Line. Какой именно item был firstVisible до переключения, сам after PNG не доказывает; before/after geometry r3 даст недостающее сопоставление

Это компонентный UI fixture320x640 внутри большого экрана эмулятора; пустая область за пределами тестового Box не является дефектом production full-screen layout. Кадр отказа не является visual приемкой3.5. Итог r2 сообщен root:115/127PASS,12FAIL; все отказы сохраняются
