# Независимый review Home/Sleep, final boundary35-r2

ACCEPT в рамках сохраненной позиции A=B=C, фактического состояния Android в сохраненных наблюдениях и чтения на паузе после возврата. Это не проверка скрытого состояния плеера в памяти и не общий допуск релиза

Run: `app/build/verification/v0.5/s6-final-boundary35-r2`, id `ac990e0359654adea92632c3fc5fb76b`

Evidence: `native-evidence/1794163263444768000-player-measure-ff03615b/record.json`

APK SHA256: `06001192794dc7ddfaf7bcf950265e761e8a9d06758eb2f280d0b1a70be3b592`

Helper SHA256: `a8135b579a5bca21aa4993c9c8ae5c91113dde3d41a924f65ac08ed3c25c9f2c`

| Проверка | Home | Sleep |
| --- | --- | --- |
| A = B = C | Полный snapshot и весь исходный XML preferences совпадают побайтно | Полный snapshot и весь исходный XML preferences совпадают побайтно |
| Позиция | segmentIndex 112, elapsedQuarterMillis 745, FAST | segmentIndex 112, elapsedQuarterMillis 16755, FAST |
| Интервал A -> B | 8.031774208 с после окончания чтения A | 8.341258625 с после окончания чтения A |
| Промежуточные снимки | 20, все совпадают с A | 21, все совпадают с A |
| OS на всем измеренном интервале | Awake, Changing=false, HAL interactive=true, focus owner Launcher, app task visible=false | Asleep, Changing=false, HAL interactive=false, ScreenOnEarly=false, ScreenOnFully=false, focus=null, app task isSleeping=true |
| Фактическая Activity | STOPPED в каждой связанной записи MainActivity | STOPPED в каждой связанной записи MainActivity |
| UI после возврата | Original, checked фраза 9, Play, 1,25× | Original, checked фраза 9, Play, 1,25× |

A в обоих случаях прочитан после наблюдения ready OS и конкретной записи MainActivity со state=STOPPED. Фактический state извлечен независимо из единственного соответствующего Hist record, без использования общих ResumedActivity/Resumed summary

В Sleep глобальные ссылки ResumedActivity/Resumed продолжают называть приложение, хотя его конкретная Activity уже STOPPED. Именно это было причиной отказа прежнего oracle; новые свидетельства не опираются на эти ссылки для вывода о состоянии экрана. На всем измеренном интервале подтвержден именно Asleep, а не промежуточный Dozing/Changing=true

В четырех исходных ответах pidof PID равен 4260: до сценариев, после каждого возврата и в конце. Шесть чтений установленного APK дают один SHA этого candidate. Язык, крупный текст (text_size=1), темная тема, храм, дата, личное начало 21:19, контекст и темп сохраняются в совпадающем XML. Изменение позиции 8 -> 9 при Home происходит между Play и завершением ухода, поэтому точка отсчета отсутствия фонового продвижения - наблюденный A после паузы, а не исходная фраза 8

Проверены 976 SHA/размеров артефактов и 4 XML действий, 292 успешно завершенных команды и 409 упорядоченных событий одного монотонного процесса. Все 48 OS-наблюдений воспроизведены из исходных power/window/activity dumps; для измеренного интервала отдельно сверены 41 реальная запись MainActivity. Все 5 UI-наблюдений сверены по checked строке, selected вкладке, Play и темпу

Прежняя попытка сохранена отдельно: `s6-final-boundary35/native-evidence/1794162741694566000-player-measure-40f4602a/record.json`, outcome=ERROR, verdict=FAIL, GateError("operation deadline exceeded"). Ее статус не переписан и не превращен в успешный

Возврат выполнялся через am start в том же процессе. Recents, поворот, холодный запуск, offline, защищенная блокировка и непрерывная телеметрия этим заключением не покрываются. Здесь не было визуального принятия PNG или записи root review/finish

Аудит выполнен только чтением готовых файлов. adb/Gradle не запускались, репозиторий и устройство не изменялись. Проверяемая сводка: `/private/tmp/feoria-v05-boundary35-r2-lifecycle-audit.json`; host-only проверка: `/private/tmp/feoria-v05-boundary35-r2-lifecycle-audit.py`
