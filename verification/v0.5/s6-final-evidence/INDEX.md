# Финальные доказательства 0.5

[Архив нативных проверок, сборки и прошлых CI](Feoria-0.5-evidence.tar.gz) содержит 5717 файлов с исходными путями и SHA256. [Манифест](MANIFEST.json), [проверка упаковки](RESULT.json), [контрольные суммы](SHA256SUMS)

SHA256 архива: `e9736c82cb09e40ec298255522dba0df99e1d80dbe893834b992747d6ccc5956`

Это проверка целостности материалов. Ошибочные попытки сохранены; архив сам по себе не означает общий PASS. Приемка дана отдельно для конкретных сценариев

## Независимые отчеты

Отчеты сохраняют состояние на момент проверки. Актуальная сводка - в [VERIFICATION-0.5.md](../../../VERIFICATION-0.5.md); поздние boundary-final, upgrade-final и API26/API37 reviews закрывают соответствующие pending-пункты ранних отчетов

- [feoria-v05-signed-candidate-review](reviews/feoria-v05-signed-candidate-review.md)
- [feoria-v05-final-main35-review](reviews/feoria-v05-final-main35-review.md)
- [feoria-v05-boundary35-r2-lifecycle-review](reviews/feoria-v05-boundary35-r2-lifecycle-review.md)
- [feoria-v05-boundary35-r2-recents-review](reviews/feoria-v05-boundary35-r2-recents-review.md)
- [feoria-v05-rotation-and-gate-review](reviews/feoria-v05-rotation-and-gate-review.md)
- [feoria-v05-offline-review](reviews/feoria-v05-offline-review.md)
- [feoria-v05-boundary-final-review](reviews/feoria-v05-boundary-final-review.md)
- [feoria-v05-native-scope-gate-review](reviews/feoria-v05-native-scope-gate-review.md)
- [feoria-v05-upgrade-final-review](reviews/feoria-v05-upgrade-final-review.md)
- [feoria-v05-api26-review](reviews/feoria-v05-api26-review.md)
- [feoria-v05-api37-review](reviews/feoria-v05-api37-review.md)

Внутри архива: полный signed candidate 336 unit +120 UI; основной native путь; Home/Sleep/Recents/поворот; offline и отказы разрешений; обновление 0.4; API26/37; целевой локальный класс; неуспешные CI42508996635 и42511850558. Старые диагностические ошибки и неверные операторские ожидания не удалены

[Предрелизная сверка документов](reviews/feoria-v05-release-consistency-review.md) дает условный допуск с оставшимися проверками CI, окончательной сборки и доставки

[Итоговый CI42517059562](../s6-ci-final/REPORT.md) принят отдельно: 336 unit +120 UI без ошибок и пропусков, lint 0/14. [Закрытие CI-условия](reviews/feoria-v05-final-gate.md). Сборка финального RELEASE commit и проверка скачанного APK публикуются отдельным delivery-артефактом, чтобы не менять принятую ревизию
