# Независимое review ограниченного release scope

ACCEPT исходников и документов подготовки выпуска. Блокеров в проверенном diff не найдено. Это не финальный release PASS: полный UI, подписанный upgrade и проверка опубликованного SHA остаются PENDING

После db44cc3 git diff содержит ровно три файла: versionCode 5 -> 6, versionName 0.5.0-guide -> 0.6.0-guide; Settings "Феория · 0.6"; User-Agent Feoria/0.6. Application ID ru.liturgyguide, minSdk26/target35, исторические assets и migration contracts не меняются. Версионная правка не меняет чтение, permission или сеть

Прочитаны README, секция 0.6 CHANGELOG, VERIFICATION-0.6 и исторический VERIFICATION.md. README ясно называет APK неподтвержденным до окончания выпуска, объясняет отдельную release подпись и отличие CI debug artifact. Документы не обещают полное последование/календарь, реальную синхронизацию с хором, доступность публичных серверов или проверку физических условий. Перенос через read-only candidate не объявлен уже выполненным финальным signed0.6 upgrade. Локальные ссылки этих трех текущих документов существуют

Основные договоренности согласованы с принятыми review: одна лента этапа, explicit Play/accept, пауза после возврата, сохранение date/mode/speed при CORRECTION, direct Mic с ограниченной попыткой, безопасный ручной путь, screen awake только для активного видимого Text/Wait. Доказательства разных revisions не названы одним общим тестовым прогоном

Неблокирующая редакционная поправка перед окончательной публикацией: последний абзац секции 0.6 CHANGELOG еще включает "приемка микрофона" в PENDING, хотя VERIFICATION-0.6 и s4 report уже принимают ограниченный stage4. Убрать этот устаревший элемент при итоговом заполнении статусов; это не основание менять runtime или повторять stage4

Остаются только уже согласованные release gates: результат текущего final-core, полный финальный UI и имеющиеся host checks, подписанный release с прежним совместимым сертификатом, actual published0.5 -> code6 update без очистки, чистый RELEASE commit и сверка скачанного публичного APK с опубликованным SHA. На момент этого review они не присвоены как выполненные

Физический телефон/свет/TalkBack/акустика/сеть пользователя остаются NOT TESTED. Continuous NO-GO. Косметика больших заголовков отложена после 0.6 по явному приоритету, новых требований или матриц не добавляю

Точные прочитанные SHA:

app/build.gradle.kts: 1f8fdea3ae93afe4c74accf71bb231dd66bb7a1231559fd89f65e44ee3a14957
app/src/main/java/ru/liturgyguide/ui/SettingsScreen.kt: fba2b038dd3537c899131c7242452860d82ff4fb5cd08f0218aedc7f4f77c71e
app/src/main/java/ru/liturgyguide/service/discovery/DiscoveryTransport.kt: 83f868223b9193f595a8974e33b94a745eb9fe2eb5119ef1fa859ce1ad87a665
README.md: 5b359afa4e4ccf2b8498bbca6f193a45ba34515a3a9833074d3f7c96821f2bd8
CHANGELOG.md: 5fbceeda81e061d7dcbcb642d974ed0ddabc2a19803b5bdd41c63719ac4d5a2b
VERIFICATION-0.6.md: 1a4876c863747c5d04041654a574a7964ffa6c820f854925ed390bc9d4af7258

Reviewer не запускал Gradle/adb и не менял репозиторий

## Actual published0.5 -> signed0.6 upgrade

ACCEPT обновления на собственном API35 emulator. Лично просмотрены все шесть итоговых кадров: old-settings, old-wait-paused, preview, accepted, meaning, cold. Old Wait показывает 9 ноября, Original, SLOW 0.75x и паузу. New preview честно сообщает о будущем возврате к последней фразе. Accepted/cold выделяют "О свышнем мире и спасении душ наших, Господу помолимся", сохраняют Original, темную тему, Крупный и паузу. Meaning показывает разделы и заметку о переносе. Фиксированные действия читаемы, частичные соседние строки относятся к границе прокрутки

Все case artifact SHA/bytes сверены. Old run 2ac38e0f541243b4a980a951eb22ae2c использует published APK de1634752bf2c9512e022be6dc04d07d60d7058c8632dbacdf492c860aaa1e71. New run 7a4c9bd4d20d41819a4465d8260a20eb использует подписанный code6 APK 2e08852050413a7e39913723f9bb7096519d1028f332b1a62ba2d56060fb6997

Пять checkpoints before/installed/preview/accepted/cold PASS прочитаны, SHA preferences пересчитаны. UID10427 и firstInstallTime сохранены при смене code5 -> code6. Before/installed/preview preferences побайтно равны, SHA 67ff7d91977e22692cdaa7d5091ce4fefa0c6c4c4b59d6e9f12746fc102590da. Установка и preview не приняли кандидат автоматически

Контекст vsetsaritsa_nakhabino/2026-11-09/Europe/Moscow/MANUAL, SLOW, church_id, dark=true, text_size=1 и lyric_original=true сохранены. Old Wait index6/offset51833 перенесен в retained Text с прежними trackId/lineId, новым fingerprint и безопасным offset0 по принятому контракту. Совпадение численного index6 не означает повторное использование старого индекса без проверки

Accepted/cold preferences побайтно равны, SHA 75ce5c8148e89acbe8bb858de4c3389124e23b22e8ce4e7b96bb7f870db700a1. Supplemental содержит force-stop и следующий LaunchState:COLD. Это реальный signed upgrade без очистки; old state подготовлен native UI, чтение own emulator prefs через su обозначено отдельно

Old preparation partial/FAIL сохраняется, успешные кадры приняты отдельно. New aggregate gate на момент чтения еще отсутствует, четыре capture и пять checkpoints приняты. Публикация и сверка скачанного публичного APK еще PENDING

## Final-core API33 correction

ACCEPT узкого diff SpeechBackend. RecognitionSupportCallback, RecognitionSupport и три API33 getter теперь находятся в отдельном private @RequiresApi(TIRAMISU) object SpeechSupportApi33. Его единственный вызов следует после прежнего SDK_INT <33 early return. Тот же recognizer, intent, mainExecutor и success/error callbacks передаются без изменений; on-device factory, permission, timeout/token/cleanup не затронуты. Блокеров по статическому review нет

Первый final-core имеет 460 unit PASS, но lint FAIL с 4 NewApi errors по сообщению root; он не является успешным финальным core. Предыдущее lint0 относится к более ранней ревизии и не переносится на финальный backend. Повтор lint/unit еще PENDING, никакого обхода NewApi suppression не добавлено

Прочитанный SpeechBackend SHA: 7673e4f6d94a4020d008611dceae209d139b180addc062ef670067cd33b6309a

## Full UI: предварительная проверка Gospel status oracle

По коду гипотеза подтверждается: ExhaustedServiceEntryTest seed сохраняет lyric_original=true, поиск "Лазарь" выбирает русское чтение Лк 16:19-31; PrayerTrackFactory создает Scripture lines с original=null. ReaderPresentation.kt:25 для такого TEXT с global Original добавляет " · по-русски" к "На паузе". Это честная уже принятая подпись fallback, а не новая ошибка player

Минимальная корректировка после окончания текущего прогона и сверки actual XML: helper assertAccepted(value, stageId, expectedStatus: String = "На паузе"); прежний assertTextEquals(expectedStatus). Только вызов в aSearchAfterMidnightRestoresAndFindsTheChosenDaysScripture передает expectedStatus = "На паузе · по-русски". Остальные calls/default и проверки date/speed/offset/session/paused/bookmarks остаются прежними. Не использовать contains, не вычислять expectation через production helper

Это статический preflight исправления oracle. Actual failure XML/PNG еще не получены, окончательная причина и результат повторного теста здесь не присвоены. Во время Gradle файлы не менялись reviewer

## Final UI exact-oracle correction

ACCEPT test-only diff ExhaustedServiceEntryTest/SavedReadingTest. Actual final-ui XML personally read: 217 tests, failures=2, errors=0, skipped=1. Gospel actual Text and StateDescription are exactly "На паузе · по-русски". Legacy stale reading actual Text and StateDescription are exactly "На паузе · сохраненное чтение · примерное место". Both agree with existing runtime semantics and the previously examined fixtures

Gospel uses one explicit expectedStatus override with the old default for every other helper call. SavedReading changes only two exact strings in the affected method. No action, fixture, date/offset/phrase/bookmark assertion or tolerance is removed. No production correction is needed for these failures

Original full UI remains FAIL with 214 PASS, 2 FAIL, 1 SKIP. If both targeted classes pass, latest distinct coverage becomes 216 PASS + 1 opt-in SKIP; it must not be represented as a single green full run. Targeted result is still pending at this addendum. No failure PNG was exported, and reviewer makes no claim of viewing one. Source/test edits and Gradle/adb were performed only by root

## Final core/UI и подписанный кандидат

ACCEPT проверенных результатов. Независимо прочитаны receipts/XML и сверены SHA build logs: final-core-r2 PASS, 460 unit/0 failures/0 skipped; lint XML содержит 0 errors и 14 warnings (не прежние 13). Original final-ui остается FAIL: 214 PASS/2 FAIL/1 SKIP. final-ui-corrections дает 11/11 PASS. Независимо собранная карта testcase из обоих XML побайтно по значениям совпадает с final-notes/ui-coverage.json: 216 PASS и 1 opt-in LiveChurchDiscovery SKIP

Inputs внутри каждого run до/после одинаковы. Между core/full UI и corrections/release различаются только два ранее принятых test expectations, записанные harness как external/58/ExhaustedServiceEntryTest.kt и external/83/SavedReadingTest.kt; остальные inputs неизменны. Debug SHA в core и corrections одинаковый 5091ade433f0575cbded0379ee66df0661000d10a58227e968895e555a204612

Release receipt PASS от чистого 5c467fde7e44765622c8ea0e2d5bfa7848199c91, package ru.liturgyguide, code6, name0.6.0-guide, debuggable=false, прежний cert bb0ea3162b2129b7411ed3a15956c6f2065c685574c1caa24fb260e452ef5247. Фактический candidate.apk лично захеширован: 11 307 998 bytes, SHA 2e08852050413a7e39913723f9bb7096519d1028f332b1a62ba2d56060fb6997, совпадает с receipt

Это приемка core/UI equivalence и metadata кандидата, не финальное завершение выпуска. Actual published0.5 upgrade, его новые PNG/checkpoints и опубликованный скачанный APK пока PENDING. Никаких запусков или изменений source со стороны reviewer

## Actual upgrade old preparation: partial review

Лично просмотрен final-upgrade-old-r2/cases/old-settings/screen.png: ACCEPT достоверности baseline, темная тема включена и выбран Крупный. Все artifact SHA/bytes сверены. Run 2ac38e0f541243b4a980a951eb22ae2c, actual published0.5 APK de1634752bf2c9512e022be6dc04d07d60d7058c8632dbacdf492c860aaa1e71, code5/0.5.0-guide. Это не приемка качества старой UI

Preparation r1/r2 содержат сохраненные ошибки ожидаемых подписей root; успешный отдельный capture не превращает partial run в PASS. Старый Wait, строгий before checkpoint и новая установка/accept/cold еще ожидаются. Повторять успешные шаги ради зеленого aggregate не требуется

## Final native microphone capability

ACCEPT microphone-result: personally viewed the release PNG. It clearly states that the Russian model is not installed, offers another check and a fully visible manual path. No clipped action or misleading listening state is shown

All case artifact SHA/bytes match. APK is the same signed code6 release 2e08852050413a7e39913723f9bb7096519d1028f332b1a62ba2d56060fb6997. Case observations and raw real-grant package.txt independently record RECORD_AUDIO granted=true. Supplemental result.json reports the real platform response, no injected audio, stop_observed=false

This accepts real OS grant and safe missing-model outcome. Active capture and native Stop after grant were not observed; acoustic quality is NOT TESTED, continuous remains NO-GO. No additional runtime blocker found. Final source/core/UI/upgrade/native review is ACCEPT in the documented scope. Only archive/docs, RELEASE build provenance and publication/download verification remain pending
