# Независимый review CI health patch

Финальная ревизия: ACCEPT patch SHA256 `96715d745f9b0ad4e89e94d9a426481f537575c182bd415c1f9d1203e8b70dda`. Узкая поправка BOOT_TRANSITION выполнена и независимо проверена, незакрытых замечаний нет. Допуск относится к применению и compile, а не к еще не выполненному CI

Дельта затрагивает только wrapper и host tests. Части workflow/collector побайтно совпадают с ранее принятым patch. Полностью прочитана дельта: Settings/FallbackHome разрешает лишь read-only ожидание в прежнем deadline; общий READY whitelist не расширен. BOOT_TRANSITION не открывает recovery-ветку; последующий ANR/UNKNOWN останавливает подготовку. Требуются два свежих READY, а deadline повторно проверяется после snapshot

Независимо повторены 19 host tests, 4 реальных window fixture checks, 4 controls перехода реального FallbackHome, 6 missing metadata controls и 3 shell mirror controls - PASS. `git apply --check` PASS, repo остался чистым. adb/Gradle/CI не запускались

Ниже сохранено исходное review и обоснование поправки

Проверен patch SHA256 `787bb928012192a2348b680f3b21753a1edab6ccdd9d2df824810480c99520e3`, база `7900494e5f2320f5669927acea8b14465816d1dd`

Вердикт по этому hash: ACCEPT по безопасности и сохранению тестового результата. Блокирующего дефекта в recovery, mirror и rethrow не найдено. Перед единственным новым CI рекомендована одна небольшая корректировка ожидания доказанного FallbackHome, описанная ниже; новый hash после нее требует сверки только этой дельты

Проверены REPORT/result, полный patch и materialized after. `git apply --check` прошел, рабочая копия осталась чистой. adb/Gradle/CI не запускались

## Подтверждено

- Единственная recovery-ветка расположена до запуска suite. Она требует opt-in, точный serial, qemu/boot, HOME resolver, awake/interactive/unlocked, сохраненный исходный PNG, точный focused visible ANR с owner android/UID1000. Unknown, другой owner/ANR и overlay не разрешают recovery
- Ровно один force-stop известного launcher и один start известного HOME component. После плохого состояния второй попытки нет. Действий восстановления после Gradle нет
- Команда suite вызывается один раз. Ненулевой exit сохраняется. Ошибка preflight/mirror-directory не выдается за failure тестов: tests_started=false, test_exit_code=null, exit=3
- Readiness проверяется по полным owner/visibility строкам, связанной идентичности focus window и известным видимым окнам. Командный exit не подменяет наблюдение READY
- Скрипт mirror получает только проверенные run/path. Не содержит Throwable/текст теста в shell command. Run path и packet не перезаписываются, а completion marker возникает только после успешного cp
- После timeout read поздний Java worker сам не пишет artifact. Shell export отдельно ограничен `timeout 2`, идет в зарезервированных 3 секундах внутри прежнего 30-секундного бюджета diagnostics. При неполной копии completion marker не обещается
- Catch/rethrow прежние: любые ошибки mirror/capture перехватываются внутри collector, wrapper бросает исходный объект Throwable. Assertions, UI actions, IME waits, runtime и тестовые timeout patch не меняет
- Экспортный лог сохраняется рядом с packet вне app data. Host pulls mirror отдельно от старого app-scoped path; поздний missing source не перезаписывает mirror

## Независимая проверка без устройства

Повторно выполнены 15 host tests - PASS. Дополнительно выполнены existing host evidence checks: 2 actual ANR window fixtures, 6 missing metadata negative controls, 3 embedded mirror script controls - PASS. В mirror success копия пережила удаление source, collision не изменила существующий packet, failed copy не создала marker

Отдельно прогнан parser на реальных dumps прежнего успешного run42507137388:

- preflight -> NOT_READY, `unexpected visible window`, `com.android.settings/com.android.settings.FallbackHome`
- after-suite -> READY с нормальным NexusLauncher

У старых dumps отсутствуют boot/qemu/HOME resolver. В этих parser checks они заданы как явно синтетические ожидаемые metadata, это не новые наблюдения устройства

## Решение по FallbackHome

Добавить узкий read-only wait полезно до единственного нового CI. Сейчас prepare немедленно выходит при этом реальном preflight, не используя свой 20-секундный budget. Такой fail-fast безопасен, но способен снова остановить проверку на штатном переходе запуска эмулятора, который уже был в успешном прогоне

Минимальная поправка:

1. Отдельный `BOOT_TRANSITION` только для полностью разобранного coherent focused visible `com.android.settings/com.android.settings.FallbackHome`, правильного owner, awake/unlocked/qemu/boot и без неизвестных visible windows
2. FallbackHome никогда не считать READY и не добавлять в общий whitelist готового состояния. Сохранить original snapshot без изменений
3. Только если исходное состояние BOOT_TRANSITION, читать готовность до READY в том же deadline 20 секунд. Никаких taps, Home navigation, launcher restart или повторов suite в этой ветке
4. UNKNOWN, другой NOT_READY или ANR во время такого ожидания немедленно останавливают подготовку. Не переносить поздний ANR в recovery-ветку: разрешенный recovery по-прежнему относится к исходному подтвержденному ANR и выполняется максимум один раз
5. После перехода все равно получить два свежих READY. Зависший FallbackHome завершает подготовку по deadline без запуска tests

Нужны небольшие host controls: реальный FallbackHome -> normal HOME -> suite ровно1/no reset; постоянный FallbackHome -> deadline/no suite; FallbackHome -> ANR или UNKNOWN -> no reset/no suite. Использовать управляемые fake clock/snapshot, не sleep на 20 секунд в host tests

Это исправление известного входного состояния текущей CI image, не предложение нового общего framework

## Границы допуска

Host shell не проверяет доступ shell к Android external storage/SELinux и наличие Android `timeout`. Компиляцию Kotlin и фактический transport на Android еще проверяет root. Диагностический budget ограничивает ожидания, а не дает жесткую гарантию всех Android Binder/file operations ровно до 30 секунд

Recovery READY не доказывает устранение первопричины системного launcher ANR. Старые CI failures сохраняются. Один новый CI запускается после финальной сверки patch и compile; полная локальная UI-suite и signed native повторно не нужны
