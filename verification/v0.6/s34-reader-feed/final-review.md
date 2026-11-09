# Финальное независимое review 3.4

Runtime verdict: ACCEPT. Финальная сверка manifest ожидается после общего freeze. Execution: NOT RUN reviewer; сборку, unit/UI и native выполняет root

Прочитаны ReaderFeed.kt, весь runtime diff ServiceScreen.kt относительно 23c555e, отдельный language delta относительно reader-feed-ServiceScreen-before-language.kt, 8 unit ReaderFeedTest, новые 17 UI ReaderFeedFlowTest, FeedTestNodes и полный diff восьми прежних UI suites. Новая разведка и расширение scope не выполнялись

## Runtime

- Чистый Header/Line/Continuation строится из всех tracks ровно текущего stage, сохраняет IDs и порядок. Selected и focus target используют actual Text; retained Text в Wait/Missing/End больше не выглядит принятым читаемым сегментом. Adjacent Line не пересекает этап/преграду. Актуальная anaphora имеет 7 tracks и 22 Line; прежнее число8 было ошибкой proposal и исправлено в oracle
- Owner generation+fingerprint+stage ограничивает viewport. Feed, Return и новые prev/next phrase controls имеют key(owner), основной action сохраняет key(generation). Guard находится также в callbacks. После replacement semantic wrapper не переиспользуется для новой позиции
- Настоящий UserInput в onPreScroll вызывает browse до pause и до потребления delta. Повторный delta отменяет pending language restore, но pause/write повторно не вызывает. Track boundary сохраняет feed, stage boundary получает новый owner. После кадра follow снова проверяет owner/manual и читает актуальный segment
- Успешные Play/seek/selectTrack вызывают явный local focus; rejected writer не превращается в успешный выбор. Same-track select фокусирует адрес даже без роста player.focusRequest. Return меняет только локальный viewport, без любых player/writer методов
- Отмена первоначального focus после layout разрешает Return; до размещения ложный Return запрещен. Видимость проверяется по clickable Text, не speaker/Header. Частичная длинная Line считается видимой. Измерения прежнего owner/selected Line очищаются
- Normal и compact резервируют максимальную естественную высоту Return/заменяемых controls. Пробы noninteractive, без скрытых OnClick/duplicate tags. Это логически устраняет show/hide feedback по высоте; действительное fit и >=120dp все равно требуют исполнения проверок
- Header показывает название, тип/фрагмент вместе с языком, context note; speaker остается вне кликабельного Text. Mixed Original считается по всем tracks, каждая Line имеет собственный fallback; accepted Scripture получает видимый префикс "По-русски". PreferredOriginal не переписывается. Meaning относится к принятому track, не viewport

## Узкая language delta

ReaderLanguageAnchor захватывает стабильный key первого просматриваемого элемента до изменения preference только при manual=true. После изменения языка viewport возвращается к началу того же элемента, без обещания сохранить внутристочный pixel offset. Header anchor допустим, если пользователь просматривал заметку

Запрос включает уникальный номер и нужный режим; после кадра проверяются manual, owner и тождество запроса. Новый drag, seek/selectTrack, Return, Play, новый language request и dispose отменяют прежнее восстановление. Оно не снимает manual, не вызывает player и не создает playback write. Coroutine cleanup проверяет identity своего job, не очищает новый job. Return Meaning по-прежнему явно возвращает к принятому адресу, а не language anchor

## Проверки кода тестов

Новые 17 UI покрывают browsing без принятия, настоящие drag на track/stage boundary с materialization и writer counts, Return при ненулевом offset, частично видимую длинную Line, Play издалека, rejected seek/Play/selectTrack, same-track selection, mixed Scripture/Scripture-only, non-Text/huge tick/явный seek назад, captured Line/Return/обе adjacent кнопки после replacement и recomposition, interrupted initial focus, стабильную normal/compact geometry

Оба language теста используют явно синтетическую длинную/короткую пару в реальной runtime-ленте. До смены языка реально измеряют shortHeight/shortExtent, настоящим drag получают offset больше нового item+spacing и проверяют, что длинная Line еще видна. После смены сохраняют ту же просматриваемую Line, полный accepted state и writer count. Второй тест отправляет настоящий новый delta до следующего кадра и отдельно заменяет owner с теми же canonical IDs, проверяя отмену pending restore. Нет подмены viewport-вопроса вызовом seek или одной лишь проверкой текста

Старые восемь UI suites переводят selectors на canonical IDs. Сохранены содержательные assertions date/offset/speed/bookmarks/lifecycle/recreation, 120dp/48dp, clipping и запрошенного fontScale. Mixed-language ожидания изменены в соответствии с новым контрактом. Metadata selectors ограничены своим track/Line; Meaning сохраняет прежние source/day-note теги. Нет runtime compatibility tags по локальному индексу, retries или пропусков geometry

## Границы

Пока нет результата компиляции/выполнения и native приемки новой ленты. Прежние font caps/compact20sp/оболочка 3.3 в этой порции сохраняются как известная обязанность 3.5a, не как финальная приемка крупного reader. Engine/store/assets/context/тайминг и persisted IDs не менялись в проверенном diff

Gradle/adb и изменения репозитория reviewer не выполнял

## Окончательная сверка SOURCE FREEZE

ACCEPT. Независимо сверены все 13 SHA и размеры файлов с финальным reader-feed-freeze.json. Aggregate 3e8e44877ed2b6e0e491d9d3abde708aa7b2bf4332405192506aff2bb57a610f подтвержден

ServiceScreen.kt SHA 5ca49ca5bafc6388a0602396b39c45e302013b1745bf2f2f3a0f135171e76951; ReaderFeedFlowTest.kt SHA c8d2a6c683c7f796f8bd2ad3c3ba49de2b88491d7b00ce50687ae94118dd1223. Предыдущее ожидание финального manifest закрыто. Статический ACCEPT относится к этой версии; выполнение остается NOT RUN reviewer до результата root

## r2 - исправление компиляции теста

ACCEPT узкой delta. В r1 compiler не разрешил DpRect.height; reviewer также пропустил этот отсутствующий import при исходном чтении. R1 остается FAIL, 429 unit PASS по сообщению root, UI NOT RUN

Независимая сверка подтверждает ровно одну добавленную строку import androidx.compose.ui.unit.height в ReaderFeedFlowTest.kt. Удаление этой строки дает точные прежние r1 SHA/bytes. Остальные 12 файлов неизменны, runtime и assertions не менялись. Все 13 текущих SHA/bytes и aggregate bac2fa113a52f66380d39b6873a2de614d84721b18f6c8941f44aaa1b662fa9f сверены. Test SHA a0390708e0900989ce90ac5eb24686c6870a7fe21495ad86747bf7e5cabba2d7

Можно выполнять corrective build/UI; повторный runtime результат этим review не объявлен. Gradle/adb reviewer не запускал
