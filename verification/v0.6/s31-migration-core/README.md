# 3.1a - ядро совместимости сохраненного чтения 0.5

Зафиксирована база опубликованного `v0.5.0-guide`, commit `98302858e341d8de621f7a1d87fce6d24b23db6b`

## Происхождение контрольных данных

- `app/src/main/assets/published_0_5/liturgy_stages.json` - точные release bytes, SHA-256 `efe2788edc898d489721fda1665b9cd455c031cb2ee8a8994c361177500da101`
- `app/src/main/assets/published_0_5/daily_readings.json` - точные release bytes, SHA-256 `8086bdc710dc080d3a77b75eb90b031d8a586d446aad4d7a379b778229a022ae`
- `app/src/test/resources/published_0_5/published-plans.json` - полные опубликованные таблицы двух планов, SHA-256 `304a414fd98232948de0d88000b4077c9316dc62b2a0d9df792632047637d369`
- 2026-11-08: fingerprint `aeee10e94ab05b297121bd86b885600b113f941e7ffc115af8c6fb8a98880434`, 150 Text, 18 Wait, 1 End
- 2026-11-09: fingerprint `3d69b0bf7c8e566560f8ab7651fa373f5c00304c2a609c73df4b8c115bf6497a`, 82 Text, 16 Wait, 2 MissingReading, 1 End

Файлы перенесены без преобразований из `/private/tmp/feoria-v06-golden-preflight`. `producer/inputs.json` фиксирует совпадение восьми source/assets с release и SHA debug classes.jar. `producer/Probe.java` вызывает реальные compiled `StageMatcher` и `ServicePlaybackPlan`, не повторяет формулу fingerprint вручную. `producer/producer.json` сохраняет точные команды, classpath, SHA зависимостей и результат независимого повторного запуска. Оба запуска дали одинаковую контрольную таблицу

Это producer контрольных данных на compiled коде, идентичном release по указанным source files. Это не запуск установленного опубликованного APK и не проверка обновления APK. Большие compiled артефакты не добавлены в репозиторий; для повторения нужны classes.jar с перечисленными source SHA и библиотеки с SHA из producer.json. Исходные абсолютные пути команд сохранены как запись выполненного запуска, при новом запуске их следует заменить путями к тем же проверенным входам

## Граница этого этапа

`Historical05Content` проверяет SHA точного UTF-8 содержимого обоих ресурсов до parsing и строит план для сохраненной даты. Ошибка чтения или SHA закрывает только путь миграции. Исторический builder использует прежние `StageMatcher`, `PrayerTrackFactory` и `ServicePlaybackPlan`; их семантика закреплена полными golden tables. При изменении алгоритма нужно сохранить отдельную реализацию опубликованной семантики, а не обновить expected goldens

`Published05PlaybackMigration` проверяет исходный snapshot старым engine. Text переносится по stage/track/line ID, каноническому тексту и длительности с точным offset и speed. Wait переносится точно только при равенстве всей канонической последовательности этапа; иначе разрешен возврат к последней надежной старой строке того же этапа с offset=0 и отдельной причиной заметки. MissingReading остается преградой stage/date, End остается концом прежнего последнего этапа. Все позиции paused

`ServicePlaybackStore` сначала выполняет прежние strict decode, полное context equality, date equality и current restore. Дополнительный `Historical05Content` необязателен: существующий constructor без него сохраняет прежнее поведение. Кандидат хранит `originalStored` с исходным persisted snapshot и отдельно `state` нового плана. Восстановление ничего не записывает и не удаляет ключ ради legacy migration

В 3.1a не изменены AppContainer, AppNavigation/MainActivity, ServiceScreen, ServicePlayerState, текущие assets, алгоритмы длительности, IDs и snapshot schema. Принятие кандидата через atomic writer, preview, маршруты UI, заметка пользователю и нативное обновление APK относятся к последующим этапам. Доказательства этих свойств нельзя приписывать unit tests ядра

## Проверки

`Historical05ContentTest` сравнивает SHA и полные golden tables обоих планов, проверяет отсутствие и повреждение ресурсов. `Published05PlaybackMigrationTest` использует реальные опубликованные планы и synthetic соседние tracks для проверки каждой старой позиции, всех трех скоростей и точных quarter offsets. Test-only fault injection сохраняет IDs при порче canonical content или duration, чтобы отдельно проверить защитные условия мигратора

`Published05PlaybackStoreTest` проверяет opt-in, приоритет обычного Valid без historical I/O, strict decode/context/date до loader, неизвестные hash/version/index/offset, отсутствие записей, сохранение полного набора preferences, блокировку legacy при present invalid key, разделение старого snapshot и нового state, измененный Wait и дату без чтений

Фактические результаты запуска и независимого ревью добавляет root после source freeze. На момент подготовки этих файлов implementer и автор matrix tests не запускали Gradle, adb или git mutations
