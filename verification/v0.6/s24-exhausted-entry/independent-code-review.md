# Code review 2.4 - исчерпанный расчетный порядок

Решение: ACCEPT замороженного runtime/test diff относительно 36c07c4

После общего SOURCE FREEZE повторно сверены восемь SHA в /private/tmp/feoria-v06-exhausted-review-inputs.json: все совпали с полностью прочитанными файлами. Подтвержденных дефектов, требующих изменения перед целевым прогоном, не нашел. Это code review, не PASS еще не исполненных unit/UI и не визуальная приемка

## Реализация и условия preflight

- MainActivity.kt:302-323 - прежний ServiceEntryResolver остается перед выбором маршрута. Ветка exhausted использует свежий result.suggestion, а pending получает точный result.context. До ручного выбора beginReading не вызывается, устаревшая карточка не превращается в следующую службу
- ArrivalDraft.kt:31-46 - pending разрешен только для NEW_SERVICE/MANUAL, PUBLISHED/USER, с точным start и совпадающим parish. Текущий church и его валидная зона сверяются при использовании. MANUAL context, pending в CORRECTION/SCHEDULE и несовместимый храм/зона не проходят
- ArrivalDraft.kt:80-95 - безопасное восстановление всей структуры с типами/размерами. Поддержана прежняя форма из 11 полей. Непустое испорченное 12-е поле, неизвестный enum, неверные типы и короткая структура отклоняют весь draft без исключения и без today fallback. Пустое поле - нормальное отсутствие optional pending
- MainActivity.kt:200-215 и :446-450 - MANUAL, ARRIVAL и оба scoped SEARCH origin без действительного draft сразу показывают MAIN и понятное сообщение. Непринятый поиск не падает в selectStage прежнего чтения. searchReadingDay сначала берет serviceDate pending, а старые manual/CORRECTION правила остаются без pending
- MainActivity.kt:295-296, :515-548 и :576-582 - запоздавшие landmark/search/accept callbacks проверяют текущий экран, тождество экземпляра draft и его валидность. Scoped callback остается scoped и не становится обычным выбором после ухода с экрана
- MainActivity.kt:335-350 - acceptArrival использует pending до manual(now)/старого context. Путь приема прежний: candidate -> beginReading с resetBookmarks=true для NEW_SERVICE. Ошибка writer оставляет draft и прежнее принятое чтение, успех очищает draft и открывает SERVICE. Новый ручной момент не approximate, скорость NORMAL, язык не перезаписывается
- ArrivalDraft.kt:49-58, ManualMomentScreen.kt:39 и ArrivalScreen.kt:24 - выбранная дата и начало показаны на обоих ручных экранах, время вычислено из start/zone, значимые секунды и дробь не округлены. Полночь не пересобирает pending context
- MainScreen.kt:29, :105, :129, :149-170 - exhausted карточка говорит о пределе расчета и возможности продолжения службы, получает одно основное действие. Следующая служба и ее точный callback сохранены. Generic find скрыт только при доступном scoped callback; information-only вариант сохраняет ручной поиск

Back MANUAL удаляет draft без приема, ARRIVAL -> MANUAL и SEARCH -> origin сохраняют его. Прежний fromSchedule bypass не используется новым путем. Численные границы, выбор occurrence, assets, playback engine и хранилище не менялись

## Содержательность тестов

ArrivalDraftTest содержит 5 проверок: точный round-trip, legacy форма, malformed типы/размеры/enum, согласованность pending и храма/зоны, точность опубликованных секунд. Это самостоятельные контракты восстановления, а не только повторение успешного encode

ExhaustedServiceEntryTest содержит 7 сценариев:

- После полуночи точный USER occurrence сохраняется через MANUAL/ARRIVAL restoration; запись с возвратом false откатывается, повторный прием успешен. Fake сначала реально меняет backing preferences и только затем возвращает false, поэтому проверяет rollback, а не только отсутствие попытки записи
- Поиск "Лазарь" использует чтения 8 ноября после recreation в SEARCH; принятую строку дополнительно сверяет по плану и названию чтения. Старое чтение было 7 ноября, текущая местная дата 9 ноября
- Back отдельно проверен без записи. Затем второй draft открывается еще в 23:50, часы переходят на 00:10, и именно этот draft принимается после restoration, без открытия новой карточки после полуночи. Повторно прочитано после freeze
- Переход неисчерпанной карточки в exhausted до тапа использует свежий Resolver
- Сохраненные реальные semantics callbacks старых MANUAL/ARRIVAL/SEARCH вызываются после принятия следующей службы и открытия нового CORRECTION; не меняют экран и preferences
- Потерянный/испорченный настоящий draft в SavedState проверяется на MANUAL, ARRIVAL и обоих SEARCH origin. Тест требует замены ровно одного draft, сохраняет экран/origin и сравнивает весь map preferences
- Значимые опубликованные секунды видны на обоих ручных экранах

Общий assertAccepted проверяет полный ReadingContext в двух записях, NORMAL, offset=0, stage, paused, не approximate, отсутствие draft экранов. До приема сравнивается полный map preferences, после успешного NEW_SERVICE проверена очистка старой закладки и сохранение языка

MainScheduleOverviewTest различает оба объекта callbacks через assertSame, проверяет honest exhausted статус и information-only fallback. Нижнюю lazy-группу материализует до assert отсутствия дубля

Прежние ArrivalFlowTest/ManualMomentFlowTest не изменены: прямой вход, expired click, next->current, CORRECTION с датой/темпом, явный новый today остаются регрессиями

## Границы решения

Source/tests не редактировал, Gradle/adb не запускал. git diff --check чистый. Фактические unit/UI результаты и нативные кадры exhausted/next остаются за прогоном root; заранее число успешных тестов не утверждается. ACCEPT не относится к будущей миграции 3.1, масштабированию 3.5a, физическому устройству, пользовательской сети или акустике


## После первого прогона r1

Прогон сообщил 374 unit PASS и 31/34 UI PASS. Code review выше не переопределяет три отрицательных UI результата. Независимая узкая диагностика ManualMomentFlowTest сохранена в /private/tmp/feoria-v06-exhausted-r1-diagnosis.md: отказ на первом landmark до Back; по имеющемуся XML/logcat причина отсутствия MANUAL и вина нового guard не установлены. Требуется диагностический целевой повтор, runtime пока не признан исправленным


Последующее диагностическое воспроизведение установило условие отказа ManualMomentFlowTest: кнопка find_stage была полностью вне viewport после прокрутки большого lazy item; до/после дерева совпали, PNG остается MAIN. Test-only performScrollTo().assertIsDisplayed() принят отдельным review в /private/tmp/feoria-v06-exhausted-r1-diagnosis.md. Guard не менялся, r3 еще не оценен
