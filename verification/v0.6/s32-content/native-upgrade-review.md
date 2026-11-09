# Независимое review нативного переноса 3.2

Вердикт: ACCEPT проверенного переноса измененного Wait и видимости состояния в четырех кадрах. Это published 0.5 release code 5 -> content debug candidate code 5, не финальное обновление на релиз 0.6 и не приемка итогового reader

Лично просмотрены `s32-upgrade-new/cases/{preview,accepted,meaning,cold}/screen.png`. Сверены case XML/receipts, run/manifest/build, actions, checkpoint XML/result и код read-only checkpoint script. Gradle/adb не запускались, устройство и репозиторий не изменялись

## Происхождение

Run `094da652428f4782aa490311981f170a`, manifest SHA `51ca473e6b916a02d7984f56d0b471c096ec67b6b304649afaa4d01e451945d0`. APK SHA `23e7b99086152c332a6a51cfecd752a1d28c9a16aea7d1cd5c3b7f05520666f0` независимо пересчитан и совпал. Внутри него текущий liturgy_stages SHA `ea0b891378c31dc7428643fe599862cf3bc06e58c88b8319c82ce2b8a04d7233`

Предыдущий опубликованный APK SHA `de1634752bf2c9512e022be6dc04d07d60d7058c8632dbacdf492c860aaa1e71`. В checkpoint до/после установки сохранены appId 10355, firstInstallTime и одинаковый сертификат. Run/install receipts относятся к замене пакета с сохранением данных, не к новой подстановке prefs. Оба APK имеют versionCode 5; новый debuggable=true. Все четыре case привязаны к тому же run/APK/manifest, SHA всех case artifacts и manifest/build проверены и совпали

## Данные и принятие

До обновления сохранены Great Litany Wait index 6, old fingerprint `3d69b0bf7c8e566560f8ab7651fa373f5c00304c2a609c73df4b8c115bf6497a`, elapsedQuarterMillis 42947, SLOW. Полный context: vsetsaritsa_nakhabino, 2026-11-09, Europe/Moscow, MANUAL, start=null

Preferences before/installed/preview побайтно равны, SHA `91ccfd4da30776e2d2564ba14a7ae21ba8b17d89b23911882d4d61c92997683a`. Установка и предварительный показ не изменили сохраненные данные. Preview показывает дату старого чтения и будущее действие "При открытии вернемся...", не сообщение о якобы выполненном восстановлении

Action receipt фиксирует явное нажатие "Сохраненное чтение". После него новый fingerprint `f53c9454bf3eb4d8d66f4fa091130a5908a45ee96160494cb0957c2d433753e7`, offset 0, SLOW, прежний полный context. Из всей карты изменились только session и service_playback_v1; тема dark, app text_size 1, original и остальные закладки сохранены

По frozen old table и current compiled table независимо сверено: old Wait index 6 преобразован в последнюю старую фразу "О свышнем мире...", track `great_litany:prayer:ac3d2a43fb85189f2d7258b3`, line suffix `166e9b26f96929a5e093046e`. В новом плане это тоже index 6, но тип Text. Совпадение числа не принято за доказательство: проверены semantic IDs, canonical текст и fingerprint. Offset старого Wait намеренно не переносится в строку, применяется согласованный возврат на offset 0

Current table создан реальным compiled Plan той же сборки из извлеченных APK assets; producer честно отличает это от Android исполнения. Android доказательство здесь дают actual install/кадры/чтение prefs, не один producer

## Четыре кадра

- preview: сохраненное чтение, точная дата и будущее объяснение читаются полностью, не смешаны с будущей службой по расписанию
- accepted: старая фраза, голос "Диакон", Original, `0,75×`, "На паузе" и завершенная заметка видимы. Текст и заметка не перекрываются
- meaning: выбран "Смысл", completed note остается над пояснением; первый раздел читается. Это подтверждает видимость notice в Meaning, не новый дизайн карточек
- cold: после перезапуска прежняя фраза/голос/Original/SLOW/пауза сохраняются. Runtime note исчезла, что разрешено принятым контрактом; повторная миграция не заявляется

Force-stop и последующий start -W сохранены как supplemental completion RECORDED, stdout содержит Status ok и LaunchState COLD. Начальный event.json имеет служебный ERROR до completion; его не следует читать отдельно как окончательный результат. Cold checkpoint PASS; prefs после него побайтно равны accepted, SHA `244fb02709218b5b622508efc13de6551113588ae670806c93b4898241aab061`

## Пределы

Только emulator-5554/API 35, 1080x2400, density 420, system font 1.0 + app "Крупный", темная тема. Это не системные 200%, не физический телефон, не акустика и не приемка 120 dp/полировки 3.5a. Нативный сценарий подтверждает измененный Wait, а не отдельное сохраненное Text с ненулевым offset

Old-r2 gate FAIL из-за сохраненных неудачных setup actions остается FAIL; две старые baseline картинки ранее приняты лишь индивидуально. Настоящий новый install и четыре принятых кадра не переписывают этот отрицательный результат. Targeted UI и финальная версия 0.6 требуют отдельных отчетов
