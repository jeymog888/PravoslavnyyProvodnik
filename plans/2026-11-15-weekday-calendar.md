# Календарь с колонками дня недели и числа

Начальное состояние a90757f, app/test bytes fe65ef1. Полный независимый B5 завершен, исходные0 полных успехов сохраняются. Это следующая порция1.2 по подтвержденной потере099, не исправление всех20 случаев

## Законченная ценность

Вернуть доказанные строки достигнутого ноябрьского календаря и явно показать, если часть опубликованных записей не прочитана. Пользователь сможет выбрать конкретную корректную службу. Ближайшая среди показанных не выдается за гарантированно следующую во всем расписании

## Ограниченный профиль

- Ближайший один article/.hentry со своим явным заголовком месяца/года расписания и одной подходящей таблицей. Actual099 имеет div.hentry и h2.entry-title. Без host/name/path исключений, года телефона, postdate или соседней статьи
- Пять доказанных logical columns: weekday | day | feast | clock | service. Изолированное число1..31, одно однозначное время, совпадающие origins/bounds rowspan weekday/day. Collision, colspan, nested table и неизвестное выравнивание не исправляются догадкой
- Source-derived дата принадлежит конкретной physical row/origin group. Общий free-text dateIn не расширяется. Неправильный weekday закрывает свою группу, следующая самостоятельная правильная дата может восстановиться. Таблица/статья закрывают новую authority
- Полный raw row, calendar cell и governing scope сохраняются для venue/cancellation. Чистое serviceText допустимо только при точном соответствии clocks. Никакого exemption календарной колонки от принадлежности здания

## Частичность до интерфейса

Один additive knownPartial в parse result/publication и scheduleKnownPartial в Parish. Events и flag выбираются, проецируются, сохраняются и удерживаются при отказе как одна publication. Не OR по чужим посещенным страницам. false означает отсутствие известного пропуска, а не доказанную полноту

Нужен transient учет действительно не представленных актуальных timed rows, включая unknown service и отказы других guards. Weekday error - лишь один случай. Row identity не теряется в pending/flush и не сводится к date/time двух разных строк. Посторонние отмены и неопределенность не становятся событиями или глобальным withdrawal. Книги/BookKey новых IDs не получают

Прежний import window today-1..today+45 сохраняется. Diagnostic relevance today..today+45: прошлые/out-of-window строки сами не создают knownPartial. Доказанная отмена не называется потерей разбора и сохраняет прежний приоритет

Cache читает absent/legacy null как false, present nonboolean отвергает. Не повышать parserRevision ради additive поля, не удалять старый кэш и не мигрировать принятые книги/курсор. Fresh refresh приносит новую диагностику

## Спокойная подача

Пометка внутри существующей карточки: "Часть расписания не удалось прочитать - проверьте сайт храма". Partial occurrence/next offer не обещает "Следующая" или "Ближайшая". При пустой выбранной дате формулировка не утверждает, что ошибочна именно она: "Не все записи удалось прочитать. Проверьте выбранную дату на сайте храма"

Пометка рядом с occurrence/offer, включая saved/offline и empty-day next offer. Только свернутых сведений о храме недостаточно. Обычные публикации без известной частичности не получают новый banner. Сохраняются quiet-primary-entry, stable keys, source-review index, anchors, callbacks и личный старт

## Приемка

До применения frozen draft - независимый Astra review. Root отдельно проверяет код, исходные bytes и runtime. Implementer делает один correction максимум, дальше root owns

Exact0040 SHA1e87ee032275e9654be3d4185dc284c28b1d332c3ac866e00214bd7f97bd9a38. Expected после остальных прежних guards:14/15/21/26/29 ноября09:00 Литургия.9/23/28 отвергаются,8/22 не изобретаются. Будущие пропуски молебна18 и Бдения21 должны дать knownPartial. Полный источник complete не объявляется

Проверить переименованный generic аналог, provenance/year/span/clock negatives, raw отмену/чужое здание в любой ячейке, boundaries, past-only и полностью представленный профиль. Repository/codec - winner coupling, legacy и строгий boolean, retention/zero-event, wrong owner/source. UI - featured, empty-day next offer, saved/offline, личный старт и обычные публикации, оба достижимых экрана

Один bounded native repository/extractor/offline/UI путь и компактный font200 в обеих темах. Полный JVM, обе APK и lint после frozen review. Не ослаблять прежние exact equality witnesses, source authority или withdrawal assertions

Не входят: bare Бдение alias, dual-style046, исправление ошибок источника, transport/discovery/feed, venue exemptions, непрерывный микрофон, новые переводы и миграция книг. Dormant diagnostics можно отделить первым безопасным шагом, но recovery пользователю не выпускать без честной partial-подачи

Подробный prereview: .local/release12/integration/weekday-calendar-plan/PLAN.md/json. Primary source и18 строк с origins: B5 run/b5-root/no-website-review/. Это рабочий план, не утверждение готовой реализации
