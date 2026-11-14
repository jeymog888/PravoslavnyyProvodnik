# Восстановление экрана и право старых действий

Полная проверка сборки обнаружила RememberReturnType в MainActivity: remember использовался для побочного изменения route token. Единственный переход восстановления SERVICE обходил обычный navigate и требовал этой дополнительной синхронизации

Переход восстановления теперь проходит через navigate. Перед сменой screen этот путь синхронно отзывает прежний автоматический поиск и меняет token. Лишний remember удален. Новых состояний, отложенного SideEffect, suppression или искусственного возвращаемого значения нет. Успешная миграция чтения и разрешенные переходы между расписанием и источниками сохраняют прежние правила

Actual Astra проверила точные два hunk и обратное восстановление preimage. Root отдельно сверил все записи screen: осталась одна, внутри navigate

## Проверка

- Полный повторный debug build: app и androidTest собраны, 1944 JVM теста, 0 FAIL/ERROR/SKIP; lint 0 errors, 15 warnings
- Native54 реально исполнил 4 случая восстановления и ручного выбора источника, все PASS. Запрошенные parameterized method selectors не нашли еще 16 случаев. Исходный manifest и отрицательный FAIL_SELECTION_MISMATCH сохранены, этот прогон не назван полным успехом
- Native55 исполнил целиком AutomaticSourceContinuationFlowTest: 11 методов в четырех конфигурациях, 44 PASS, 0 FAIL/SKIP. Проверены поздние результаты, Settings, stop/resume, восстановление, viewing-family, новый draw/frame witness и старые URL callbacks
- Все эти 48 UI случаев выполнены на одном и том же проверенном APK. Все 68 прежних файлов книг и настроек совпали побайтно. Все 1023 source inputs совпали до и после прогонов

Артефакты: `.local/release12/schedule/composition-route-sync-fix/`, `.local/release12/schedule/link-priority-draft/root-runtime/full-build-second/`, `.local/release12/integration/native-fifty-fourth/` и `native-fifty-fifth/`

Проверки используют управляемые ответы и отдельные preferences. Они не заменяют живой Main-проход и холодный запуск приложения с выключенной сетью
