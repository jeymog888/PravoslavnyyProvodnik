# Независимое review 3.1a

Вердикт: ACCEPT для замороженного ядра, до запуска проверок root

Блокирующих дефектов в проверенных изменениях не найдено. Это приемка кода ограниченного этапа 3.1a, не приемка миграции в интерфейсе или обновления установленного APK

## Что проверено

Все 20 файлов совпали по SHA-256 с `/private/tmp/feoria-v06-migration-core-freeze.json`. Прочитаны три production файла, fixture и три класса тестов, README/self-review и producer. Репозиторий не изменялся, Gradle/adb и воспроизведение producer не запускались

- `app/src/main/java/ru/liturgyguide/service/Historical05Content.kt:13`: имена historical assets и ожидаемые SHA закрыты внутри loader. Оба текста проверяются до parsing, ошибки чтения и несовпадение байтов закрывают путь миграции. Дата берется из запроса сохраненного плана, не из текущего дня. Вызов общего builder допустим при нынешней неизменной семантике, которую закрепляют полные golden tables
- `app/src/main/java/ru/liturgyguide/service/ServicePlaybackStore.kt:25`: прежний строгий codec, полное равенство ReadingContext и дата проверяются перед historical I/O. Текущий Valid возвращается первым. Необязательный historicalContent=null сохраняет прежнее поведение существующих callers
- `app/src/main/java/ru/liturgyguide/service/Published05PlaybackMigration.kt:17`: совпадение даты/version и восстановление старым engine проверяют именно старые fingerprint/index/quarter offset. Нет переноса неизвестного индекса по числовому совпадению, clamp или пересчета старого таймера
- `Published05PlaybackMigration.kt:46`: target Text находится по track/line, затем проверяются stage/IDs/duration и canonical content. Проверка исходного текста использует тот же приоритет original/russian, что fingerprint
- `Published05PlaybackMigration.kt:25`: точный Wait допускается только при совпадении всей последовательности сегментов этапа и canonical текста, включая порядок и длительности. Одной равной длительности Wait недостаточно. При изменении/удалении ожидания retainedTextAt старого плана ограничен тем же этапом; отсутствие надежного старого адреса дает отказ. Возврат к старой фразе получает offset=0 и отдельную note
- `Published05PlaybackMigration.kt:38`: MissingReading сохраняет stage/date, исчезновение преграды не превращается в автоматический переход к новому чтению. End должен оставаться концом того же последнего этапа. Строка 41 повторно валидирует новое состояние новым engine; результат paused, скорость сохраняется
- `ServicePlaybackStore.kt:15`: originalStored явно остается старым снимком, state принадлежит новому плану. Ни store, ни migrator не пишут preferences, не удаляют ключ и не вызывают legacy fallback. Наличие старого/невалидного snapshot продолжает блокировать прежний legacy import

## Тесты и происхождение данных

Статически проверены 24 новых test methods. Матрица охватывает 2088 Text, 306 точных Wait, 576 возвратов из измененных/удаленных Wait и 36 отказов без старой строки, а также Missing/End. Проверяются реальные сдвиги индекса, все скорости, четвертные offset внутри границ, отказы на границе/за ней, неизвестные hash/version/index, чужая дата и контекст. Fault injection отдельно проверяет одинаковые IDs при измененном canonical/duration/stage. Store tests проверяют отсутствие любых edit/commit и сохранность всей карты preferences; исправленные marker keys являются реальными константами приложения

Golden test сравнивает полные таблицы и отдельный фиксированный SHA ожидаемого JSON, а не только результат другого вызова того же builder. MissingReading правильно сверяется с датой уровня таблицы producer. Сравнение исторических assets с `git show 98302858e341d8de621f7a1d87fce6d24b23db6b` независимо выполнено при этом review: оба файла побайтно равны. Все восемь source/assets из producer/inputs.json также совпали с указанными SHA и release bytes. SHA golden равен `304a414fd98232948de0d88000b4077c9316dc62b2a0d9df792632047637d369`

Probe вызывает реальные compiled StageMatcher/ServicePlaybackPlan; producer.json содержит конкретные команды, classpath, SHA зависимостей и результаты повторного запуска. Документация корректно называет это контрольными данными compiled debug кода с релизными зависимыми исходниками, а не исполнением установленного релизного APK. Повторный Java запуск в этом review не выполнялся

## Граница решения

Тесты еще не исполнены в рамках данного review: числа выше описывают проверенную структуру тестов, не PASS. Перед приемкой этапа нужны запланированные root full unit, compile androidTest/assembleDebug и проверка байтов historical assets внутри APK

AppContainer/UI не подключают historical loader на этом этапе, поэтому новый candidate пока недостижим в обычном production пути. Atomic acceptance, отказ writer, заметка без ложного обещания восстановления, UI restoration/Back и сохранение остальных bookmarks при принятии должны быть проверены в 3.1b. Это следующая согласованная граница, а не дефект или скрытая приемка текущей реализации
