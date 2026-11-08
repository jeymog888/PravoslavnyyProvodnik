# Независимая проверка новой сборки 0.5

ACCEPT только для receipt, результатов и привязки текущих исходников

- Commit: `57e48a5f9af879e196ac17ee517fcee0108d0800`, tree совпал, receipt и текущая рабочая копия чистые
- APK SHA256: `06001192794dc7ddfaf7bcf950265e761e8a9d06758eb2f280d0b1a70be3b592`
- Receipt/log/APK проверены существующим validate_build, fingerprints до и после сборки совпадают
- Проверены текущие hashes 116 входных файлов, включая оба production-файла Recents и тесты
- 336 unit и 120 UI testcases: 0 failures, 0 errors, 0 skipped. Суммы XML совпали с checks.json, дубликатов testcase нет; копии результатов совпали с текущими Gradle outputs
- Тесты немедленной паузы top-resumed, старого lifecycle fallback, ownership bind/unbind и неизменного speed-сценария присутствуют и прошли
- Lint: 0 Error/Fatal, 13 Warning. XML совпал с исходным отчетом
- Build log: BUILD SUCCESSFUL, 124 задачи выполнены; новый full UI run завершил 120/120

Приватный signing key повторно не читался. Его external digest одинаков до и после build; сертификат и debuggable=false взяты из принятого receipt. Debug-тесты не заменяют native-проверки signed release

Это не визуальная приемка, не подтверждение нового native Recents/upgrade и не общий release PASS. adb/Gradle/повторные тесты не запускались, repo не менялся

Машинный отчет: `/private/tmp/feoria-v05-signed-candidate-review.json`
