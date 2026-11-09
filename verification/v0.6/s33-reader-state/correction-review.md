# Независимое delta review 3.3 после r1

ACCEPT для повторной проверки неизмененных primary-сценариев. Реальный r1 FAIL остается отрицательным свидетельством; исправление пока не объявляется runtime PASS

Лично прочитаны logcat отказавшего ReaderStateFlowTest, его захват/вызов SemanticsActions.OnClick, /private/tmp/feoria-v06-reader-state-failure.md, QA diagnosis и соответствующие инструкции в /private/tmp/feoria-v06-reader-clickable-javap.txt

Подтвержденный эффект: после приема replacement reading и Play вызов ранее захваченного semantic action приводит к "Двигать текст" / "На паузе" вместо ожидаемой "Пауза". Assertion падает на ReaderStateFlowTest.kt:204 -> :250. Часы в harness не продвигаются и Meaning не включается

Bytecode AbstractClickableNode$applySemantics$1:15-20 читает this$0.getOnClick() на каждом invoke; updateCommon:256-258 заменяет поле onClick. Это объясняет, почему новый callback с новой generation законно выполняет PAUSE, несмотря на правильный guard прежнего callback. Речь о воспроизведенном late semantic action, без утверждения о наблюдавшемся физическом tap/TalkBack событии или полной трассе identity узлов

ServiceScreen.kt:378-387 теперь оборачивает ровно primary Button в key(generation). Ключ охватывает clickable node, а прежние guards сохраняются. Replacement получает отдельный узел; старый action остается связан со старым callback, который отклоняет новую generation. Обычная смена Play/Pause в той же generation ключ не меняет. Не добавлены key(action), новый UI-контейнер, таймер, запись или перестроение всего reader

Обратное удаление единственного key-wrapper с соответствующим отступом дает побайтно прежний ServiceScreen SHA из reader-state-freeze-r1.json. Все остальные 13 файлов, включая все тесты, идентичны r1. Все 14 новых SHA/размеры сверены с диском, aggregate b6ab6370713d67713cbeb55948342403c49681ed1dfbda96e374ffffa87b2e1d

Тест не ослаблен: прежний action вызывается после replacement/recomposition, проверяются accepted state, playing, writer count и отсутствие Finish для Begin/Continue/Finish. Повтор этого метода и связанных ReaderStateFlowTest должен подтвердить исправление; reviewer не запускал Gradle/adb и не менял source/tests
