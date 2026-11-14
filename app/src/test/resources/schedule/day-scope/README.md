# Атрибуция weekly section018

Источник: https://blagovest72.prihod.ru/main-page/raspisanie-bogosluzhenij/

Минимальный weekly section скопирован дословно из captured `responses/0009.body` case018, строки172-222. Добавлена только оболочка `<main><h1>Расписание богослужений</h1>`. Две недели9-15 и16-22 ноября2026, печатные дневные заголовки и все строки сохранены. Полный captured HTML остается в локальном replay root и не переносится в test resources

Captured source SHA256: `7736895c1493613baecb85858bb0a8f5c6999b584724f265e30a3932aea780fa`

Независимый oracle: `.local/release12/schedule/batch2-primary018/ASTRA-PRIMARY.json`, `criticalFinding.actualEventsAudited` -21 ранее выданная строка с проверенной primarySourceDate. В новом тесте сохранены21 точные service строки, удалены ошибочно приписанные суффиксы следующего дневного заголовка. Это обязательный subset, не cap21. Любая дополнительная строка обязана соответствовать печатному дню и времени
