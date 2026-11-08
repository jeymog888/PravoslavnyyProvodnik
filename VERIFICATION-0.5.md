# Проверка Феории 0.5

Кандидат принят по локальным, нативным и CI-проверкам. Этот документ фиксирует проверки кандидата и состав финальной ревизии. Результат сборки этой ревизии, опубликованный APK и проверка скачивания фиксируются отдельно в `delivery.json` на странице [GitHub Release 0.5](https://github.com/jeymog888/PravoslavnyyProvodnik/releases/tag/v0.5.0-guide). До появления успешной записи доставки выпуск не считается завершенным

## Проверенный APK

- Package `ru.liturgyguide`, versionCode `5`, versionName `0.5.0-guide`, `debuggable=false`
- SHA256 `06001192794dc7ddfaf7bcf950265e761e8a9d06758eb2f280d0b1a70be3b592`
- Source commit `57e48a5f9af879e196ac17ee517fcee0108d0800`, чистое состояние сборки
- Сертификат совпадает с опубликованными 0.2/0.3/0.4: `bb0ea3162b2129b7411ed3a15956c6f2065c685574c1caa24fb260e452ef5247`. DN существующего сертификата - `CN=Android Debug`; это не означает debuggable=true у собранного приложения
- Receipt: `app/build/verification/v0.5/s6-candidate-recents-fixed-r2/build.json`

Диагностические commits `7900494` и `e8ad6a0` меняют тестовую инфраструктуру. Production не изменен. Принятый APK и 71 production/build input сохранились. Финальный RELEASE commit потребует отдельного build receipt и проверки совпадения APK SHA, его результат здесь еще не заявлен

## Сборка и автоматические проверки

| Проверка | Результат | Объем |
| --- | --- | --- |
| Полный локальный unit suite | 336 PASS, 0 failures/errors/skips | Signed candidate receipt |
| Полный локальный UI suite API35 | 120 PASS, 0 failures/errors/skips | Включая регрессию реального top-resumed ownership |
| Lint локально | 0 Error/Fatal, 13 Warning | Предупреждения сохранены |
| Build candidate | BUILD SUCCESSFUL, 124 выполненные задачи | Входные файлы, отчеты и APK независимо сверены |
| ChurchDiscoveryScreenTest на 7900494 | 6/6 PASS, 0 failures/errors/skips | Отдельный local target, source fingerprints before=after; старые результаты лежат в prior-results-not-this-run |
| Harness | 61 host check, 36 сохраненных Android states | Ранее принятые проверки; исходный harness не ослаблен ради поздних прогонов |
| Mutation controls | 3/3 мутации обнаружены точными assertions | Baseline и восстановленное состояние PASS |

## Подписанный APK на API35

| Сценарий | Подтвержденный результат | Источник |
| --- | --- | --- |
| Основной путь | 8/8 static cases, root PNG review и независимый ACCEPT; реальный Play двигает текст, Meaning ставит паузу | s6-final-main35 |
| Home и Sleep | Реальные состояния ОС, persisted A=B=C, возврат на паузе | s6-final-boundary35-r2 и lifecycle review |
| Recents и окно темпа | Потеря top-resumed при еще RESUMED Activity больше не двигает текст; возврат через реальную карточку задачи на паузе; окно темпа само по себе не останавливает Play | r2 и recents review |
| Поворот экрана | portrait -> landscape -> portrait сохраняет фразу, язык, паузу и точные preferences | r2 rotation-check |
| Полный перезапуск без сети | Отсутствие активной сети подтверждено до force-stop; PID сменился, место чтения и preferences сохранены | r3, offline review |
| Сетевой поиск без соединения | Новый некэшированный город дает понятную ошибку, ручной выход работает | r3, offline-search-error |
| Геолокация выключена/запрещена | Две разные причины дают соответствующие подсказки, ручной путь доступен | r4, geo-off/geo-denied |
| Микрофон запрещен | Настоящий OS Deny, сообщение об отказе и рабочий ручной выбор этапа | r4, boundary-final review |
| Справка и приватность | Полные абзацы читаемы, ограничения плеера и обработки данных объяснены | r4, about-player/about-privacy |

Это сводное покрытие принятых cases и самостоятельных native-сценариев, а не выдуманный aggregate PASS прогонов r2/r3/r4. Отдельные операторские ошибки и системные блокировки сохранены

За сохраненный интервал main35/boundary нет `am_crash`/`am_anr` приложения. Основные exit-info причины - обновление пакета, явный force-stop и изменение разрешения. Более ранняя ошибка UiAutomation не отнесена к приложению или этому интервалу

## Настоящее обновление опубликованной 0.4 -> 0.5

Независимый ACCEPT: `s6-upgrade-setup`, `s6-upgrade-old-prep`, `s6-upgrade-checkpoints`, `s6-final-upgrade35`

- Старый APK SHA256 `6f0177583126e884b9a0be743f30a58f1c486bf5f2e423eadcb0eaa4d9cb1632`, подпись совпала с новым APK
- Состояние старой версии создано через UI: Всецарица в Нахабино, темная тема, крупный текст, Original и вторая фраза Херувимской песни
- Между checkpoints выполнен один `install -r`; appId и firstInstallTime прежние, целый preferences XML побайтово одинаков до первого запуска 0.5
- Первый reader 0.5 восстановил вторую фразу на паузе. После настоящего Next и force-stop новый процесс восстановил третью фразу, язык, храм и настройки
- Три старых и шесть новых PNG просмотрены root и независимым QA; финальный gate 6/6 PASS
- В сохраненном интервале обновления нет target crash/ANR. Ошибки первоначальной подготовки сохранены отдельно и не попадают в цепочку before -> install-r -> after

## Совместимость Android

| Среда | Результат | Ограничение |
| --- | --- | --- |
| API26, Android 8, собственный AVD 1080x1920/density420 | Root просмотрел 6 PNG: fresh main, Meaning, Russian reader, cold saved reader, unavailable ASR, manual reader. Play прошел с первой до четвертой фразы, Meaning остановил, Russian сохранил четвертую; новый PID после force-stop восстановил ее на паузе. Недоступный ASR объяснен, ручной путь работает | Ограниченный smoke, не полный suite. Root и независимый review ACCEPT; offline на API26 не проверен |
| API35, Android 15 | Полный локальный suite + signed native + настоящее обновление | Собственный AVD, не физический телефон |
| API37, Android 17 | Установка того же подписанного APK, ручной вход, Play, пояснение, Original, Next и холодное восстановление; 4 PNG приняты root и независимым QA | Ограниченный smoke на собственном AVD, не полный suite или offline |

На API26 попытка отключить Wi-Fi завершилась `svc` exit137 и SecurityException системной команды: UID2000 не имеет CHANGE_WIFI_STATE, сеть осталась активной. Поэтому cold restore API26 не назван offline PASS. Полный подписанный offline-сценарий подтвержден отдельно на API35

## CI и сохраненные отказы

- CI42508996635, head57e48a5: 336 unit PASS, UI119/120, один lazy-node FAIL. Диагностика не установила единственную причину; этот отказ не объясняется более поздним launcher ANR
- CI42511850558, head7900494: 336 unit PASS, UI60/120 с 60 failures, 0 errors/skips; lint0/14. Artifact13630915355 сохранен, ZIP SHA256 `056755e1415d4368c119cd709043cd859dfa051ae23db6c17b75c76fbb65e275`
- До второго suite уже было реальное системное окно "Pixel Launcher isn't responding", тот же focused ANR остался после suite. Среда заранее непригодна, но причинность каждого отдельного failure не утверждается
- Первый diagnostic packet объявлен созданным, но отсутствует при позднем pull. Причина исчезновения не установлена
- CI-only patch `e8ad6a0` принят root/Astra: строгий readiness gate, ограниченное read-only ожидание конкретного FallbackHome, один opt-in restart подтвержденного launcher и shell mirror первого failure packet. 19 host checks и androidTest compile PASS. CI42517059562 на точном `e8ad6a072d8a601f8f33a1939de2206974993d12` принят: 336 unit и 120 UI, без failures/errors/skips; lint 0 ошибок и 14 предупреждений. Preflight и два свежих наблюдения READY, один запуск suite, recovery_count=0. Artifact13631623274 проверен по SHA256 `210feeb9b3acc663c7442f3a451f3af1646f7befdae589d29f01f39421807bf6`. [Отчет и исходный artifact](verification/v0.5/s6-ci-final/REPORT.md). Старые FAIL сохранены. Доставка failure packet после настоящего сбоя и recovery launcher в этом успешном запуске не исполнялись
- Boundary r1 сохраняет ошибочный Sleep oracle FAIL; r2 - операторские ошибки ожидания подписи и пути preferences; r3 - срабатывание foreground guard на реальном GMS overlay; r4 - три неверных операторских ожидания
- API26 сохраняет неверные ожидания подписей/экрана cold launch и неудачную системную команду отключения Wi-Fi

## Границы версии и финальный допуск

Плеер дает приблизительный темп и ручную корректировку, а не точную синхронизацию с реальным хором. Непрерывный микрофон не включен без акустического доказательства качества. Каталог расписаний ограничен двумя опубликованными приходами и датированными источниками ноября2026; нет полного церковного календаря и всех переменных текстов. Датированное русское чтение доступно для 8ноября2026 без подмены отсутствующих дней или оригинала

Независимая предрелизная сверка и итоговое CI-условие приняты. После фиксации RELEASE остаются его clean-source build receipt, совпадение APK с принятым кандидатом и проверка скачанного опубликованного файла. Эти факты записываются в `delivery.json` соответствующего релиза без изменения финального коммита. [Итоговое решение](verification/v0.5/s6-final-evidence/reviews/feoria-v05-final-gate.md)


Постоянный [индекс доказательств и независимых ревью](verification/v0.5/s6-final-evidence/INDEX.md). Архив 28 024 596 байт, 5717 файлов; SHA256 `e9736c82cb09e40ec298255522dba0df99e1d80dbe893834b992747d6ccc5956`. Целостность проверена после упаковки, включая сохранение неуспешных попыток
