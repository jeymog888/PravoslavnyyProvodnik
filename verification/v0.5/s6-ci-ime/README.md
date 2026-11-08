# Read-only QA preflight: клавиатура и порядок UI-suite

Вывод: явное `show_ime_with_hard_keyboard=1` в CI обосновано как условие теста и совпадение с проверяемым локальным окружением. Доказательств уже воспроизведенного падения CI из-за аппаратной клавиатуры нет. Нельзя утверждать, что pixel_7 в текущем workflow обязательно включает аппаратную клавиатуру

## Свидетельства

- `.github/workflows/build.yml:51-60` запускает API35/google_apis/x86_64/pixel_7, но не задает secure setting IME
- `ChurchDiscoveryScreenTest.kt:207-232` требует сначала фактически видимый IME, затем скрытый IME с inset bottom=0 и снятием фокуса. Комментарий в тесте и отчет cycle3 объясняют прежнюю гонку: проверка hidden могла пройти раньше отложенного show
- `GuideNavigationTest.kt:90-109` тоже прямо ожидает visible IME перед проверкой скрытия. Это не новая зависимость только одного класса
- Локальный `/private/tmp/liturgy-android-toolchain/avd/Liturgy_API_35.avd/config.ini:78` содержит `hw.keyboard = no`, соответствующий hardware-qemu.ini:10 - false
- Root отдельно сообщил актуальный результат чтения secure setting на API35: 1. Это сообщение оркестратора о текущей конфигурации, не новый независимый receipt этого агента
- В сохраненном `verification/v0.5/s5-cycle3/design-cycle3-native3/cases/epistle-full-header/windows.txt:440` global configuration содержит `-keyb/v/h`; это согласуется с локальным AVD без аппаратных клавиш. Сохраненного dumpsys input_method с secure setting среди просмотренных capture-файлов не найдено
- `verification/v0.5/s5-cycle3/design-cycle3-native3/setup.jsonl` в конце возвращает portrait/rotation0, wm size reset и font_scale1.0. Сам secure IME precondition там не записан

По текущему [action.yml runner@v2](https://github.com/ReactiveCircus/android-emulator-runner/blob/v2/action.yml#L56-L58) `enable-hw-keyboard` по умолчанию false. [Исходник runner](https://github.com/ReactiveCircus/android-emulator-runner/blob/v2/src/emulator-manager.ts#L42-L44) добавляет аппаратную клавиатуру лишь при true; [при этом же true](https://github.com/ReactiveCircus/android-emulator-runner/blob/v2/src/emulator-manager.ts#L102-L104) он отдельно ставит show_ime_with_hard_keyboard=0. В нашем workflow параметр не включен

В [AOSP Android 15 InputMethodService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/inputmethodservice/InputMethodService.java#L2268-L2278) решение показать input view учитывает настройку показа с аппаратной клавиатурой; без нее разрешает показ при отсутствии аппаратных клавиш или скрытой клавиатуре. Отсюда вывод: установка1 делает предпосылку явной и устойчивой к наличию клавиш, но текущий no-keyboard AVD может работать и без нее

## Минимальный кандидат

`ci-ime.patch` меняет только workflow: после запуска эмулятора и до полного UI-suite выставляет одну настройку. Никаких повторов теста, новых sleeps, исключений или изменений assertions

Инвариант остается прежним: фокус и ввод действительно открывают IME, действие приложения действительно закрывает его и снимает фокус, после чего настоящий touch выбирает результат в устойчивой области экрана. Настройка разрешает системной клавиатуре появляться; она не подделывает видимость и не заставляет клавиатуру закрыться за приложение

База: `01c625a8a15f12c2bbe012a3eca20ac244713134`

SHA-256 patch: `08e3f2e586cf9342a29afd25a89d75db242b9d17b4a0725307ea105bd79c6095`

`git apply --check` проходит. CI, Gradle и adb не запускались, working tree не менялся. Проверка поведения остается за root/CI

## Порядок полного прогона и остаточный риск

Просмотрен инвентарь 17 instrumentation-классов и их места обращения к глобальному состоянию

- В тестах нет записей `settings`, `wm`, default Locale/TimeZone, runtime-permission mutations или принудительного порядка методов. Shell-команды из двух test-классов только создают каталог и снимают диагностические PNG
- Изменение fontScale/viewport в CompactReaderMatrixTest и ReaderAccessibilityTest локально в CompositionLocalProvider; системный размер текста не переписывается
- Все три тестовых класса с requestedOrientation возвращают portrait в finally и ожидают ориентацию. Это снижает утечку после обычного assertion failure, но не после аварийного завершения процесса
- Сценарии с реальными SharedPreferences очищают/заполняют их перед использованием; MainActivity-классы GuideNavigationTest и SavedChurchFlowTest делают это внешним RuleChain до старта Activity. Остальные экранные проверки получают локальные данные или fake store. Конкретной оставшейся утечки prefs между классами не найдено
- В `DiscoveryNavigationTest.kt:57-62` остается последовательность input -> IME action -> scroll/touch результата без явного visible -> hidden ожидания. Она похожа на уже найденную гонку в ChurchDiscoveryScreenTest. Это подтвержденное сходство кода, но не доказанное падение данного сценария. На полном прогоне стоит смотреть именно этот тест и сохранить failure артефакты; сейчас его не менять без воспроизведения
- Отдельные методы не доказывают чистоту общего порядка. Нужен запланированный полный прогон на записанной базовой конфигурации, а не замена его набором успешных выборочных запусков

Самопроверка: первоначальная гипотеза о включенной аппаратной клавиатуре CI не подтвердилась - исправлена в выводе после чтения runner default и локального AVD. Обоснование patch сведено к явному precondition и паритету; утверждение о доказанном исправлении CI не используется

Root прочитал patch и предпосылки. Astra review ACCEPT. Применено как явное условие CI; результаты удаленного запуска будут записаны отдельно. Локальный полный suite119PASS выполнен с тем же IME=1
