# 3.5 r4 - корректирующее code review

ACCEPT для целевого запуска43 UI и затем оставшихся84 на том же runtime. Статических блокеров в переданном delta не найдено. Runtime изменился, результаты старого r2 не принимаются за проверку r4. До запуска статус NOT RUN

Freeze: /private/tmp/feoria-v06-reader-layout-freeze-r4.json
Aggregate: d467d9f00c1293be5e9715f237c09ceec96c5dee49664be16eed4bbf56830ca6

Независимо проверены SHA/bytes всех17 файлов. По сравнению с r2 изменены только ServiceScreen, ReaderCompositionFlowTest и ReaderAccessibilityTest. Обратное применение ровно /private/tmp/feoria-v06-reader-composition-root-r4.diff восстанавливает r2 SHA каждого из этих3 файлов. Диагностический ReaderFeedFlowTest восстановлен до точного r2 SHA7e1964a2c466162ab29abf46098d082b4981ad08189edd5aefbf1dba602cde4e. Остальные файлы неизменны

## Соответствие доказанным причинам

В r3 language viewport сохранялся, но после смены языка наверх поднимался длинный Header, вытесняя ранее видимую Line. В r4 preserveLanguageAnchor перебирает фактически visibleItemsInfo и предпочитает первый key с зарегистрированными bounds самого Text и пересечением readingArea. Header fallback остается, если ни одной такой Line нет. Accepted state не используется вместо просматриваемой строки. Map не является snapshot state, заполняется UI positioning callback только при owner.matches, очищается при dispose Line и всего viewport. Старые cancellation/owner guards language job сохранены. Нет новых player/seek/write вызовов

В r3 boundary pause уменьшал status99px->43px и увеличивал viewport на56px, вызывая end clamp; primary/secondary были неподвижны. В r4 для TEXT/WAIT/BEFORE_START измеряются paused и playing варианты status и primary при той же ширине, включая approximate основание, плюс фактическая текущая строка. Samples создаются только копированием immutable state с anchor null/0; engine tick и writer не вызываются. StableReaderText измеряет все варианты в тех же max constraints и резервирует максимальную естественную высоту. Поэтому длинная playing подпись остается в бюджете и после Pause, в том числе у узкого landscape primary. Новых font caps, maxLines или ellipsis нет

Измерительные Text в StableReaderText и ReaderSecondaryMeasure имеют собственный clearAndSetSemantics. Скрытые samples не размещаются, не имеют action или testTag. Visible Text остается единственным носителем соответствующей подписи/тега. Это устраняет источник unmerged Text дубликатов, не маскируя их test selector

## Тесты и пределы

Обе существующие Return geometry проверки дополнены Play/Pause: сохраняются viewport, primary и secondary bounds; после101ms проверяются paused, прежний segment/context/speed, offset+404 и ровно2 writes. Прежние assertions не удалены

Accessibility header setup теперь использует точные канонические Header/Line индексы ReaderFeed через ScrollToIndex и idle. Исходные full-title/readability/font/120dp/48dp assertions сохраняются. Это убирает лишний ScrollBy-поиск из setup, а не ослабляет проверяемую читаемость. Окончательное исчезновение ранее наблюденных semantics exceptions подтвердит запуск

Нужно сохранить результаты r2/r3 FAIL. R4 пока принимается по коду, не по геометрии на устройстве. Требуются целевые43 UI, оставшиеся84 на r4 и последующий native просмотр объявленных кадров. Крупные fonts и окончательная pipeline приемка остаются3.5a. Source/tests не редактировал, Gradle/adb не запускал

## r5 Compact setup correction - ACCEPT, NOT RUN

Проверен точный root-r5.diff: только assertTrackHeader setup получает канонические Header/Line индексы из ReaderFeed, ScrollToIndex с assertTrue и idle. Сохраняются исходные performScrollTo на найденном потомке, полный текст названия, assertReadable с font/clipping, selected/displayed Line,120dp и равенство reading. В assertion message добавлены visible/full bounds без изменения условия или допуска. Viewport bounds в сообщение не добавлены, но это не меняет приемку корректировки

Проверены все17 SHA/bytes freeze-r5, aggregate770285611a76f88e497237ed35ec4d65adebef451487a8d166f6e38527dce5db. Единственное изменение против r4 - CompactReaderMatrixTest; обратный exact diff дает точный r4 SHA. Runtime и остальные16 файлов неизменны

Допустим предложенный запуск93 методов: Compact9 и остальные84. С учетом34 независимых PASS ReaderFeed/ReaderAccessibility/ReaderComposition на том же r4 runtime это даст127 только если новый запуск пройдет. Старые7 Compact PASS отдельно не суммируются. R4 остается41/43 с2FAIL; причина исходного clipping пока не доказана, r5 не объявляется исправлением product defect до результата

## r6 Compact title alignment - ACCEPT, NOT RUN

Проверен exact root-r6.diff: вместо вложенного performScrollTo после канонического Header тест берет title/viewport top из одинаковых semantics root-px координат, требует titleTop>=viewportTop, выполняет ровно один ScrollBy(0,gap), проверяет true и ждет idle. Далее прежний assertReadable с font/clipping/full bounds и тем же pixel tolerance. Возврат к канонической принятой Line, selected/displayed,120dp и равенство reading остаются

Это реальная пользовательская прокрутка в setup уже paused чтения. Цель - поместить весь заголовок внутри viewport, не менять production padding/шрифт и не обходить проверку читаемости. Нет retries, first(), новой погрешности или ветки, пропускающей assertion. Слишком высокий или недоступный целиком заголовок по-прежнему провалит исходную проверку

Все17 SHA/bytes freeze-r6 сверены; aggregate595a666dd5b2dfc64c1d18ca3cb9f90fbd6a28cb23f808f2663248793b211427. Изменился только CompactReaderMatrixTest. Runtime SHA отдельно сопоставлены с r4 и совпали

ACCEPT для Compact9. Финальное покрытие127 допустимо учитывать как34 r4 +84 r5 +9 r6 только после успешного нового результата, поскольку runtime одинаков. R5 остается91/93PASS с двумя исходными Compact FAIL; его не обозначаем полным PASS

## После r6 - оценка контракта scrollable title

R6 снова7/9PASS, те же2FAIL. Прежнее объяснение через недостаточный вложенный scroll не доказано и не объявляется исправленным. Из имеющихся bounds нельзя доказать, что полный title250,286dp помещается в фактический viewport. Дальше менять production padding или подбирать setup для требования всего title в кадре оснований нет

ACCEPT_WITH_CONDITIONS предложенного assertReadableFeedTitle. Полное имя перенесено из фиксированного header в прокручиваемый текст, поэтому правильный контракт - отсутствие внутреннего clipping/ellipsis и доступность каждой строки при последовательной реальной прокрутке. Весь длинный title одновременно в кадре не является обязательным, как не является им вся длинная молитва. Fixed stage header сохраняет прежний assertReadable целиком

Обязательные условия helper:

- Сохранить все проверки TextLayoutResult: отсутствие ellipsis, допустимые lineLeft/Right, lastBottom внутри размера layout и прочие прежние font/overflow условия. Нельзя принять текст, обрезанный внутри собственного layout, только потому что его видимый остаток помещается
- Проверить первую строку, затем каждую строку0..lineCount-1. Это конечный обход содержимого, не повтор одной неудачной попытки до PASS
- Позицию текста брать без clipping, координаты строки TextLayoutResult и viewport перевести в одинаковые root-px единицы. Фактически видимый viewport пересчитывать; не использовать только высоту контейнера без проверки перекрытий
- Только если bottom очередной строки ниже viewport, один ScrollBy на измеренную разницу и idle. Затем заново вычислить origin и требовать всю строку внутри viewport по обеим осям с прежним pixel tolerance. Boolean результата action не заменяет видимость. Если строка сама выше viewport или прокрутка не довела ее до видимости, fail без retry
- Добавить геометрию title/viewport/строк и before/after в log. После helper сохранить возврат к принятой Line, selected/displayed,120dp и равенство reading

Это принятие уточненного критерия доступности scrollable содержимого, не установление причины старого clipping и не разрешение ослабить внутреннюю читаемость. Runtime менять ради этого не требуется; точный новый delta подлежит review

## r7 scrollable title contract - ACCEPT, NOT RUN

Прочитан exact root-r7.diff. assertCompleteTextLayout является extraction прежних проверок: displayed, TextLayoutResult, lineCount/overflow/ellipsis и lineLeft/Right/Top/Bottom внутри собственного layout сохранены. Fixed assertReadable вызывает их и оставляет прежнее сравнение full/visible bounds с1px допуском

Только feed title проходит каждую строку в порядке0..lineCount-1. Origin берется из unclipped root bounds, DP переводится в root px через density того же layout; в этом harness изменяется fontScale, но density остается device density, единицы согласованы. Viewport берется из фактических semantics bounds. При выходе bottom за viewport более1px выполняется максимум один ScrollBy на измеренную разницу, затем idle и новое чтение origin. Проверяются top/bottom/left/right строки и неизменность viewport. Неудачное размещение одной строки сразу провалит assertion, повторов одной строки до PASS нет

Лог содержит полный title rect, viewport, lineCount и before/after каждого scroll. Это даст отсутствующие фактические размеры. После helper остаются канонический accepted Line, selected/displayed,120dp и state equality. Измерения не подменяются успешным Boolean ScrollBy

Сверены17 SHA/bytes и aggregate92194b5da149b744e32578f5e918bf5714b41ad59e36ec7a73d3ae8aedad1902. Меняется только Compact test; оба runtime файла отдельно сверены с r4. ACCEPT для Compact9. R4/r5/r6 FAIL сохранены; причина прежних2,7dp не объявляется ни product bug, ни доказанной ошибкой setup до нового geometry log

## Итоговая проверка результатов текущего runtime

ACCEPT code/runtime verification3.5 по прочитанному коду и выполненным root UI. Visual приемка еще NOT REVIEWED, font caps/pipeline остаются3.5a

Лично прочитаны result XML:34 уникальных PASS ReaderFeed17/ReaderAccessibility9/ReaderComposition8 из r4,84 уникальных PASS остальных8 suites из r5,9 PASS CompactReaderMatrix из r7. В выбранных127 testcases нет failure/error/skipped, пересечений методов нет. Это объединение результатов на одинаковом runtime, не объявление старых запусков целиком успешными: r4 остается41/43, r5 остается91/93, r6 остается7/9; r7 отдельно9/9PASS

Сопоставлены inputs_before/after каждого r4/r5/r7: внутри запуска точные совпадения. Между запусками единственное отличие среди всех записанных inputs - external/71/CompactReaderMatrixTest.kt (имя в receipt); main/config/resources/assets одинаковы. Текущие17 файлов дополнительно совпали с r7 freeze SHA/bytes. Build logs подтверждают запуск connectedDebugAndroidTest, а не подмену одним старым summary

Локальный APK независимо пересчитан: SHA2560f0758817fc0f82f49ef790ea810fb5b0017e68098ce94d200b45df6f9053eaf,17209573bytes. Совпадает с успешным r7 build receipt и сохраненным r5-local-apk-observation.json. Наблюдение r5 не является успешным build receipt. Начатый native run95bd1889b9434e688e9dea4a9839ebea содержит тот же installed SHA; SHA его build.json проверен. Это проверка файлов/записей, нового обращения к устройству reviewer не делал

FeoriaTitle log r7 независимо прочитан: для обоих длинных "Херувимская..." viewport[21,514,819,1164] имеет высоту650px, full title[63,546,777,1203] имеет657px и4 строки. Последняя строка потребовала ровно ScrollBy39px, после него full[63,507,777,1164]. Предыдущие строки были проверены до прокрутки, последняя - после. Полный title объективно выше viewport на7px, поэтому критерий whole-node одновременно в кадре здесь невыполним. Это объясняет прежние2,6667dp при density2,625 и подтверждает корректность построчного контракта, не перекрашивая старыеFAIL вPASS

434 unit PASS относятся к r1; последующие runtime изменения r4 прошли перечисленные127 UI. Этот отчет не утверждает повторного запуска unit после r4. PNG будущих13native случаев еще не принимались. Gradle/adb не запускал, repo не изменял
