# 3.1b - подключение совместимости сохраненного чтения

SOURCE FREEZE. Реализация на базе 9f2bee8, текущие и исторические assets, timing/fingerprint/IDs/schema не менялись. Gradle, adb и git mutations не запускались

## Что реализовано

- AppContainer получает последний необязательный assetReader. Default читает через applicationContext; текущие stages/daily и SHA-проверяемый Historical05Content используют один reader. Прежний discovery override, кэш stage success/failure с forceReload и lazy nullable daily сохранены
- AppNavigation подключает исторический loader к store. Valid остается единственным источником initial player, MigrationCandidate остается отдельным preview с проверенными stage/date/context. Legacy fallback при present snapshot не открыт
- Resume принимает кандидата через beginReading -> ServicePlayerState.accept -> существующий atomic writer с resetBookmarks=false. Только успешная запись открывает читатель. Неуспех оставляет MAIN и возможность повторить тот же проверенный адрес, включая случай двух отказов commit/rollback
- Saved SERVICE автоматически принимает только прежние Valid/legacy пути. Candidate остается непринятым и возвращает MAIN. То же действует после Back из SETTINGS/STAGE_SELECTOR с origin SERVICE и CORRECTION
- Resume защищен актуальным screen и захваченным player.generation. Успешная новая служба, очистка и смена храма инвалидируют старый callback до возможной записи
- Порядок/поиск и CORRECTION сохраняют скорость из player либо candidate только при полном равенстве context. NEW_SERVICE и exhausted pending occurrence используют NORMAL, как прежде
- Runtime migrationNote в ActiveServicePlayback не входит в snapshot/hash/time. MAIN показывает дату и будущее действие для измененного Wait. После commit заметка показана отдельной Surface над телом читателя, включая Meaning, и в details. Обычные операции сохраняют ее, успешный явный seek очищает; при отказе writer остается прежнее чтение с заметкой

## Проверки, подготовленные к запуску root

3 новых unit метода в ServicePlayerStateTest: proposal/failed accept/retry, точный paused offset и скорость; жизнь заметки при play/tick/pause/changeSpeed и отказе seek; очистка при явном выборе строки, track, stage, Missing barrier и новой службы

11 новых instrumentation методов в Published05PlaybackIntegrationTest используют настоящий AppNavigation/Store, synthetic neighboring current texts и точные historical bytes. Покрыты semantic preview при неправильном legacy marker, отсутствие initial player/записей, commit failure после memory update и retry, двойной отказ commit/rollback, точный новый индекс/hash/context/offset/speed и посторонние preferences, повторный restore без historical I/O, future/completed заметка и Home/theme/Meaning, реальные сохраненные SERVICE/SETTINGS/order/CORRECTION маршруты, same-context SLOW/FAST, fresh today/NORMAL, exhausted exact occurrence/NORMAL, late callback после новой службы и смены храма, missing/SHA mismatch historical, default reader/discovery/caching/forceReload

Каждый UI метод обернут существующей withCiFailureDiagnostics без retry. В saved routes проверяются и полная карта preferences, и неизменный счетчик попыток commit. Тесты подготовлены, но еще НЕ ЗАПУСКАЛИСЬ

## Самопроверка и исправленные дефекты

Implementer прочитал runtime diff целиком и все unit/UI тесты. QA агент независимо выполнил самопроверку своего файла; два найденных дефекта затем проверены implementer

1. Первая проверка notice под AlertDialog ожидала сразу две копии одного текста в semantics. Модальное окно может скрывать нижнюю поверхность, поэтому это неверное ожидание. Исправлено на точный reading_details_migration_notice внутри диалога; основной banner проверяется отдельно до и после закрытия
2. Первая проверка default asset reader сравнивала текущий план с frozen historical fingerprint. После запланированного дополнения 3.2 она ошибочно падала бы при корректной реализации. Исправлено на сравнение default с явным reader текущих bytes; historical goldens остаются отдельным строгим контрактом и не меняются

Дополнительно при ревью усилена проверка saved routes счетчиком попыток commit: одно равенство конечной карты не доказывало отсутствие промежуточных записей

## Ограничения и последующая приемка

Root и независимое Astra review, выполнение тестов и нативные свидетельства еще предстоят. Это не проверка обновления опубликованного APK. Полный процесс restart не обязан хранить runtime note; долговечный original/Russian не смешан с временным Meaning

Обязательное открытое условие 3.5a, согласованное с root: на стрессовом размере текста отдельная заметка над weight body может уменьшать viewport. При адаптивной переработке читателя разместить ее в прокручиваемой композиции либо доказать достаточное место; не объявлять этот этап приемкой large-text reader

При двух отказах commit и rollback нельзя обещать физическую целостность диска. Fake применяет изменения памяти перед false, в том числе rollback; проверки отражают именно этот сценарий и отсутствие ложного успеха UI
