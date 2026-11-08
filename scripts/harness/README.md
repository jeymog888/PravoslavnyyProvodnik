# Native evidence harness

Небольшой Python stdlib + adb инструмент для проверок конкретного APK. Python 3.9+, без новых Android зависимостей

Он проверяет наблюдаемые условия, сохраняет происхождение сборки и результаты. Он не оценивает красоту, контраст, полный TalkBack traversal, реальный размер касания или отсутствие визуального обрезания текста. Эти результаты остаются в Compose geometry/text-layout checks и отдельном ручном просмотре PNG

## 1. Проверка самого инструмента

Из корня репозитория:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/harness/tests -v
```

Negative controls используют тот же код selector, window guard, postcondition deadline, capture, build receipt и aggregate. Fixture APK/XML/PNG в unit tests не являются результатами настоящей Android сборки или visual acceptance

## 2. Receipt настоящей сборки

Установите обычные переменные JAVA_HOME, ANDROID_HOME и GRADLE_USER_HOME. Затем:

```sh
python3 scripts/harness/feoria_harness.py build \
  --repo "$PWD" \
  --apk "$PWD/app/build/outputs/apk/debug/app-debug.apk" \
  --output /private/tmp/feoria-build-unique \
  --build-type debug \
  --aapt "$ANDROID_HOME/build-tools/35.0.0/aapt" \
  --apksigner "$ANDROID_HOME/build-tools/35.0.0/apksigner" \
  -- --no-daemon assembleDebug
```

Путь версии build-tools укажите существующий. Для release нужны соответствующие `--build-type release`, `app-release.apk`, `assembleRelease` и текущая настройка подписи. Receipt не создает новый ключ

Каталог receipt должен быть новым. Инструмент удаляет только заранее проверенный ожидаемый APK в `app/build/outputs/apk/<type>/app-<type>.apk`, чтобы успешная команда не подтвердила старый файл, затем действительно вызывает Gradle wrapper. Ошибка Gradle, отсутствие нового APK или изменение исходников дают FAIL receipt

Хешируются root/app Gradle configuration, wrapper, buildSrc/build-logic и все main/debug/release source/assets/resources, включая untracked файлы. Docs, verification, instrumentation и unit tests не входят в fingerprint именно APK. Добавляйте внешние влияющие файлы через повторяемый `--extra-input /absolute/path`; `--init-script PATH` добавляется автоматически. Для signing file можно передать `--extra-input`: записывается хеш и basename, не содержимое. Пароли и токены не передавайте через CLI: аргументы команды сохраняются в receipt

В receipt есть commit/tree/dirty, фактические input hashes до/после, exit code, лог, SHA APK, package/version/debug flag и certificate SHA-256. Это provenance конкретной сборки, не доказательство bit-reproducible зависимостей, SDK или произвольных глобальных Gradle init scripts. Если в проекте появятся другие production modules, список fingerprint roots нужно расширить до их сборки

## 3. Фиксированный manifest и явная установка

Manifest заранее перечисляет обязательные случаи. Пример отдельного reader corpus:

```json
{
  "schema": 1,
  "package": "ru.liturgyguide",
  "build_type": "debug",
  "conditions": {
    "data_source": "fixture: saved personal start, not parish live schedule",
    "clock_source": "device clock, not injected",
    "location_source": "not used",
    "network_source": "device connectivity; no external-provider assertion"
  },
  "cases": [
    {
      "id": "reader-original-paused",
      "mode": "static",
      "conditions": {
        "fixture": "describe exact stage, date and language before the run",
        "motion": "paused, checked by expected visible Play action"
      },
      "observed_requirements": {
        "sdk": "35",
        "font_scale": "1.0",
        "timezone": "Europe/Moscow"
      },
      "allowed_windows": [
        {"package": "com.google.android.apps.nexuslauncher", "title": "Taskbar"}
      ],
      "expected": [
        {"content-desc": "Включить плеер", "enabled": true},
        {"text": "Оригинал"}
      ],
      "human_review_required": true
    }
  ]
}
```

Замените fixture описание фактическими данными, включите нужный заголовок части/текста и активное состояние режима в expected. Это пример структуры, не готовое доказательство сценария. В `conditions` записываются **декларированные условия**, отдельно от `observed` device facts. Координаты эмулятора, mock provider или подставленные часы называйте fixture

`conditions.player = paused` само по себе не подтверждает паузу. Для reader static corpus нужен наблюдаемый predicate:

- Обычный reader: видимое действие `Включить плеер`, enabled true, и ожидаемая часть/режим
- Missing/End: disabled Play и соответствующий видимый барьер или конец
- Meaning: выбранный режим и его ожидаемое содержание, если проверенный runtime-контракт ставит Meaning на паузу

Это правила конкретных сценариев, не универсальный детектор состояния. На Main невидимый owner плеера не объявляется наблюдаемым paused

Запуск:

```sh
python3 scripts/harness/feoria_harness.py begin \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5554 \
  --manifest /private/tmp/feoria-manifest.json \
  --build /private/tmp/feoria-build-unique/build.json \
  --run /private/tmp/feoria-run-unique
```

`begin` сверяет receipt и исходный APK, выполняет `install -r`, читает фактически установленный единственный `base.apk` и сверяет его SHA. Он не очищает данные, не запускает приложение, не меняет настройки устройства. Для чистого сценария или upgrade root выполняет явно необходимые действия и сохраняет их отдельные доказательства. Splits здесь намеренно не поддержаны

Manifest и build receipt копируются в новый run и связываются хешами. Serial обязателен. Нельзя подменить package, manifest, APK или повторно использовать каталог run

Обязательные наблюдаемые device facts: serial, sdk, fingerprint, wm_size, density, font_scale, locale, timezone, device_epoch_seconds. `UNKNOWN` в них запрещает PASS. `observed_requirements` добавляет точные обязательные условия конкретного случая, например разрешения или настройки сети. Остальные network/permission facts могут оставаться честным UNKNOWN, если сценарий их не проверяет. `wifi_setting` не означает доступность интернета

Окна SystemUI StatusBar/NavigationBar и ScreenDecor разрешены по узкому списку. Иные видимые окна, включая клавиатуру, требуют явного `allowed_windows` с точными package/title. Для permission dialog задайте `window_package` этого кейса и нужные разрешенные фоновые окна. Не разрешайте неизвестный overlay только чтобы получить green. Неизвестный формат dumpsys завершает проверку ошибкой; парсер пока сверялся с сохраненным API 35 dump, новые API требуют фактической проверки

## 4. Действие, postcondition, capture

```sh
python3 scripts/harness/feoria_harness.py tap \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5554 \
  --run /private/tmp/feoria-run-unique --case reader-original-paused \
  --selector '{"text":"Оригинал"}' \
  --expect '[{"text":"Оригинал"},{"content-desc":"Включить плеер","enabled":true}]'

python3 scripts/harness/feoria_harness.py capture \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5554 \
  --run /private/tmp/feoria-run-unique --case reader-original-paused --timeout 30
```

Selector поддерживает точные text/content-desc/resource-id/class и state attributes. Raw label nodes поднимаются только в своем XML window/package к ближайшему clickable ancestor; несколько label nodes одного target дедуплицируются. Несколько targets, disabled ancestor, неизвестные или выходящие за display bounds, чужой foreground/overlay запрещают tap

В настоящем Compose XML подпись часто имеет `selected=false` и `enabled=true`, даже когда состояние задано контейнеру. Для наблюдения этого состояния используйте один ограниченный `ancestor` predicate: `{"text":"Смысл","ancestor":{"class":"android.view.View","selected":true}}`. Для заблокированного Play проверяйте его label вместе с `ancestor` класса фактической кнопки и `enabled=false`. Состояние соседнего элемента не подходит. Вложенные ancestor predicates запрещены; окно и package по-прежнему едины. Это наблюдение состояния, не разрешение нажать disabled кнопку

После tap обязателен непустой postcondition и общий монотонный deadline. Каждый вызов наблюдения получает остаток бюджета. Правильное состояние, полученное уже после deadline, не считается успехом

Capture делает XML -> PNG -> XML. Сравнивается полное дерево с отсортированными attributes, включая текст, selected/enabled, bounds и порядок. PNG dimensions сверяются с текущим display. Стабильность accessibility tree не доказывает стабильность пикселей или качество дизайна. Автоматического переключения на dynamic режим нет; этот небольшой runner поддерживает только static corpus. Play с настоящим временем проверяется отдельной последовательностью наблюдений, которую нельзя называть static baseline

Каждый case привязан к run, APK, manifest, declared/observed conditions и artifact hashes. Case нельзя перезаписать. Ошибочная попытка/незавершенный action не исчезает из общего результата. После изменения сценария создайте новый manifest/run, сохраните причину предыдущего FAIL

## 5. Ручное принятие и итог

После просмотра изображения:

```sh
python3 scripts/harness/feoria_harness.py review \
  --run /private/tmp/feoria-run-unique --case reader-original-paused \
  --reviewer root --decision ACCEPT \
  --observations 'Запишите конкретные наблюдения о заголовке, тексте, режимах и управлении'

python3 scripts/harness/feoria_harness.py finish --run /private/tmp/feoria-run-unique
```

`review` - запись отдельного ручного решения о конкретном PNG, не автоматический visual judge. `REJECT`, отсутствие обязательного review, missing/skipped/failed case, UNKNOWN обязательного условия, дубликат, пустой/измененный/старый артефакт или failed action запрещают общий PASS. `finish` создает `gate.json` только при принятом полном manifest

Для последовательных сценариев можно импортировать функции `Adb`, `begin_run`, `tap`, `capture_case`, `review_case`, `aggregate`. Один владелец устройства и одна сборка одновременно. Функции не перехватывают сторонние raw adb действия и не утверждают, что незафиксированные шаги проверены
