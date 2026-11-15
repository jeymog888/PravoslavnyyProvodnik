# Измерение обычного повторного обновления в 1.2

Исторический holdout harness не делал обычный второй refresh после первичного NO_WEBSITE/UNAVAILABLE. Он измерял первоначальную попытку, поиск метаданных и до двух подтверждений. Поэтому эти результаты не доказывали работу нового normal refresh. Старые B1-B3 остаются прежними, без пересчета и объединенного процента покрытия

normal-retry-v2 использует общий test-only coordinator для live workflow и hermetic проверок. После нормально завершенного первичного отказа с непустым checkedAt, без loading/customSource и без пригодной публикации выполняется ровно один refresh(force=true, sourceOverride=null) того же repo/cache/selected/now. Затем заново оценивается прежний metadata gate, остается максимум один такой поиск и два различных подтверждения. Третий refresh не появляется

Общий source deadline180s, case240s, book60s и transport15s не расширены. Нет очистки cache/history, снятия отрицательных flags, подстановки URL/города/часов или изменения usable. STARTED/attempted пишутся до await; finally сохраняет реальные счетчики, длительность и ошибку. Невозвращенное состояние остается null, cachedAfterRun не выдается за завершенный результат. SOURCE и последующий BOOK failure различаются

Новые report/summary содержат только выбранную партию. Копия всего sealed.json и данные невыбранных записей больше не экспортируются. Frozen100 и порядок/страты не менялись; B4/B5 до этой фиксации не открывались. READY по-прежнему требует независимой проверки первоисточника и не превращается в applicationSuccess автоматически

Проверки: actual Astra static review,27 hermetic методов на Android PASS; вместе с4 историческими Russian regression native69=31/31, protected68 неизменны. JVM1995/1995 и обе сборки/lint прошли. Проверены ошибки, отмена и deadline, запрет третьей попытки, изменение metadata permission, source/cached/null receipts, запрет последующих действий при ошибке записи STARTED, cleanup/protected files и безопасный summary

Это проверка измерительного workflow, не новый live batch, Main/20s/name/GPS сценарий или физический перезапуск. Новый заранее зафиксированный срез должен выполняться отдельно после окончательной фиксации production discovery

Доказательства: .local/release12/schedule/normal-retry-v2-plan/, normal-retry-v2-implementation/, integration/root060-normal-v2/ и native-sixty-ninth/
