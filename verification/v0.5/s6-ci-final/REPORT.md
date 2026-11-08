# CI 42517059562, attempt 1 - PASS

Независимо проверен run https://github.com/jeymog888/PravoslavnyyProvodnik/actions/runs/42517059562 на точном head `e8ad6a072d8a601f8f33a1939de2206974993d12`, GitHub conclusion `success`

- Unit336/336 PASS, 0 failures/errors/skips, 336 уникальных случаев в25 JUnit XML
- UI120/120 PASS, 0 failures/errors/skips, 120 уникальных случаев
- Lint0 errors/fatal, 14 warnings
- Реальный журнал: Starting120tests19:56:25.397Z, Finished120tests20:01:56.697Z, BUILD SUCCESSFUL20:01:57.697Z
- Verification artifact13631623274, 4642301 bytes, SHA256 `210feeb9b3acc663c7442f3a451f3af1646f7befdae589d29f01f39421807bf6` совпадает с GitHub API digest
- Metadata самого artifact указывает этот же run и head

## Подготовка окружения

`suite-result.json` подтверждает `tests_started=true`, `test_exit_code=0`, `environment=READY`, `recovery_count=0`. Preflight и два следующих независимых наблюдения дали READY. Эти verdict повторно проверены на фактических raw dumps точным parser из commit e8ad6a0; after-suite также READY

В preflight/readiness focused window принадлежит обычному NexusLauncherActivity, power Awake/interactive=true, keyguard showing=false. Реальный preflight PNG просмотрен: домашний экран с собственным меню Wallpaper & style/Widgets/Home settings, без системного ANR. After-suite PNG также просмотрен, блокирующего ANR нет. Не утверждается, что preflight был пустым домашним экраном без меню

В47 записях commands.json нет restart-launcher действий. Разрешенное восстановление ANR здесь не потребовалось. Наблюдается один запуск полной suite, без повторного запуска workflow или тестов со стороны reviewer

## First-failure mirror

Shell-owned каталог этого run создан успешно. Final mirror pull завершился0: 0files pulled/0skipped. Ошибок тестов нет, поэтому первый failure packet не создавался. App-external pull вернул1 из-за отсутствия каталога; в этом успешном run это не потеря существовавшего packet

Этот run проверяет подготовку и доступность mirror, но НЕ доказывает сохранение packet после фактического failure/cleanup. Ветвь восстановления launcher ANR также не исполнена. Эти ограничения не меняют подтвержденные336/120 PASS

## Границы

ACCEPT для данного полного CI-прогона и его фактических проверок. Один успешный прогон не доказывает статистическое устранение flaky-сбоев. CI app-debug не подменяет отдельно проверенный подписанный release APK. Lint14 warnings сохранены, zero-warning результат не заявляется

Reviewer скачал артефакт и журнал, проверил SHA/происхождение/JUnit/raw readiness и PNG. Не запускал adb/Gradle, не повторял/отменял workflow и не менял исходники приложения
