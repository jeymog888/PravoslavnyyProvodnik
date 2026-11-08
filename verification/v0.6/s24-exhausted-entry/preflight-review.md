# Preflight 2.4 - исчерпанный расчетный порядок

Решение: ACCEPT_WITH_CONDITIONS предложенного контракта до реализации

Проверены раздел 2.4 в /private/tmp/feoria-v06-estimate-source-exploration.md, NIGHT_PLAN.md:64 и реальные openScheduledService, acceptArrival, searchReadingDay, ArrivalDraft, ServiceEntryResolver, candidate/beginReading, ServicePlayerState.accept. Это логическое review будущего изменения, не приемка реализации или выполненных тестов. Репозиторий не менялся, Gradle/adb/git mutations не запускались

## Что можно реализовывать

Точный result.context после ServiceEntryResolver подходит как непринятый контекст выбранной службы. Обычный NEW_SERVICE с дополнительным pendingServiceContext и source=MANUAL корректно разделяет способ поиска и происхождение начала службы PUBLISHED/USER. Дата, зона и startEpochMillis должны переноситься без пересборки из отображаемых строк

MainActivity.kt:276-291 сейчас сразу принимает последний расчетный этап; новую ветку нужно выбирать по свежему result.suggestion, не по флагу ранее показанной карточки. ReadingContext.kt:184-198 уже сверяет ровно выбранные parish/start/source/timezone/date и не подменяет службу следующей. Эту проверку и окно 150 минут менять не требуется

Контекст можно фиксировать в момент нажатия карточки. Пересечение полуночи или завершение окна доступности во время ручного выбора не обязано отзывать уже выбранную службу: это не новый автоматический прогноз. Дополнительный повторный Resolver при подтверждении для минимального контракта не нужен. Если его все же добавят, допустим только явный отказ по тому же occurrence, без замены на today/next

## Обязательные условия реализации

1. Восстановление должно закрывать потерю draft во всех трех местах, включая SEARCH. MainActivity.kt:477-498 сейчас не рисует MANUAL/ARRIVAL при null draft, а :521-530 при SEARCH с origin MANUAL/ARRIVAL и null draft переходит в обычный selectStage. Последний использует старый player.reading.context (:295-300). Поэтому недостаточно вернуть пустые MANUAL/ARRIVAL на главную: scoped SEARCH без валидного draft тоже должен безопасно завершиться с понятным сообщением и без приема чтения. До такого перехода его onSelect не должен иметь fallback к selectStage

2. Saver должен различать отсутствие optional pending и присутствующее поврежденное значение. ArrivalScreen.kt:31-42 сейчас использует прямые индексы, casts и valueOf: короткий список, иной тип, неизвестный enum могут выбросить исключение. Для затронутого draft нужен ограниченный безопасный decode всей структуры, без исключения и без превращения испорченного pending в обычный manual(now). Старую поддерживаемую форму без pending можно трактовать как прежний manual; нельзя использовать decode(json) ?: null как достаточную проверку нового поля

3. ReadingContextCodec.validated проверяет структуру, но допускает также MANUAL context. Для pending из карточки нужны дополнительные инварианты: draft.mode=NEW_SERVICE, draft.source=MANUAL, context.source в PUBLISHED/USER, context.parishId=draft.churchId=текущий church.id, context.timezone равен текущей валидной зоне храма, startEpochMillis присутствует. Несовместимый pending в CORRECTION нельзя молча игнорировать. Эти проверки должны работать и после restoration, и при приеме из поиска, а не только в enabled кнопки ARRIVAL

4. searchReadingDay (MainActivity.kt:202) использует serviceDate валидного pending раньше currentDate/readingDay. При недействительном pending нельзя подставлять сегодняшние чтения. candidate (:260-274) уже строит план по переданному accepted.serviceDate; использовать его с pending, не с context прежнего чтения. В интерфейсе MANUAL и ARRIVAL должны быть выбраны дата и начало из pending, с сохранением значимых секунд опубликованного начала

5. acceptArrival (:302-315) принимает pending только через candidate -> beginReading(resetBookmarks=true). Не использовать прежний fromSchedule bypass, который сейчас возвращает MAIN без приема. Не изменять preferences, context игрока и bookmarks при создании draft. Очистить draft и открыть SERVICE только после успешного beginReading; при отказе writer оставить возможность повторить выбор и прежнее принятое чтение. ServicePlayerState.accept:55-64 уже обновляет reading после успешной записи, новое чтение принимается на паузе. Сохранение паузы прежнего проигрывания остается допустимым прежним поведением; оно не равно приему нового контекста

6. Обычный NEW_SERVICE без pending продолжает принимать local today на явном выборе, CORRECTION сохраняет прежние context и темп. Новая ветка NEW_SERVICE начинает NORMAL и без approximate. Не сбрасывать общую языковую настройку. При Back из MANUAL draft очищается, ARRIVAL -> MANUAL и SEARCH -> origin сохраняют его, как в текущем MainActivity.kt:393-407

7. Generic find скрывается только при реально доступном scoped primary: currentHypothesis.estimatedGuideExhausted и ненулевой callback onOpenService. При information-only карточке ручной путь остается. Next остается видимым с собственным объектом/callback; не использовать suggest(), который может выбрать следующую вместо текущей. Тексты о пределе расчета не должны утверждать окончание реальной службы

## Минимальные приемочные проверки будущего diff

- Exhausted current и next одновременно: разные точные callbacks, один scoped поиск, информация-only без callback сохраняет общий поиск; неисчерпанная и следующая службы сохраняют прямой вход
- После Resolver истекшая карточка не создает draft/новое чтение. Если карточка успела стать exhausted после отрисовки, маршрут выбирается по свежему результату
- Полночь: USER 2026-11-08 23:00 Europe/Moscow, now 2026-11-09 00:50, startEpochMillis соответствует 2026-11-08T20:00:00Z. Pending и поиск используют 8 ноября. Дополнительно явная смена now через полночь уже после открытия draft не меняет дату
- Старое принятое чтение другой даты остается прежним до выбора. Ориентир и поиск после restoration принимают весь точный pending context, не только совпадающий stageId. Writer failure сохраняет старые данные и draft; успешный retry атомарно заменяет контекст/позицию и очищает прежние bookmarks
- Поврежденное pending, неизвестный enum/короткий SavedState, mismatch храма/зоны и отсутствие всего draft при восстановленном SEARCH с origin MANUAL/ARRIVAL дают понятный безопасный возврат без старого selectStage или today fallback
- Прежние manual NEW_SERVICE/CORRECTION и Back остаются регрессиями. Нативный вид нужен для двух карточек с датами и читаемого пояснения, но не доказывает длительность богослужения

Контракт в разведке по существу верен. Основное уточнение до реализации - защита SEARCH при утраченном draft и проверка согласованности всей связки draft/context, а не только JSON самого ReadingContext
