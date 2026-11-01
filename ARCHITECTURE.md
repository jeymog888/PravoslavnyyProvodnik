# Архитектура: Проводник по богослужению

## ADR-001: Выбор архитектуры

**Контекст**: Android-приложение для помощи в понимании православного богослужения. Требуется офлайн-first, быстрая загрузка, масштабируемость для будущих фич.

**Решение**: MVVM + Clean Architecture + Repository Pattern

**Обоснование**:
- MVVM — стандарт для Android, поддержка Google, Lifecycle-aware
- Clean Architecture — разделение на data/domain/presentation слои
- Repository — абстракция над источниками данных (локальные + удалённые)

**Альтернативы**:
- MVI — избыточно для MVP, выше порог входа
- Redux — не-native для Android, сложная интеграция

---

## ADR-002: Dependency Injection

**Контекст**: Требуется DI для тестируемости и управления зависимостями между слоями.

**Решение**: Koin (для MVP), с миграцией на Hilt (для прода)

**Обоснование**:
- Koin — легковесный, без кодогенерации, быстрый старт
- Hilt — стандарт Google, лучшая интеграция с Android, но требует Annotation Processing

---

## ADR-003: UI Framework

**Контекст**: Требуется современный UI с поддержкой церковнославянского текста.

**Решение**: Jetpack Compose + Material3

**Обоснование**:
- Compose — Declarative UI, меньше boilerplate чем View system
- Material3 — адаптивные темы, поддержка dark mode
- Кастомный renderer для церковнославянского (буквица, юникод)

---

## ADR-004: Локальное хранение данных

**Контекст**: Офлайн-first требует локальное хранение богослужебных данных.

**Решение**: Room + DataStore

**Обоснование**:
- Room — SQLite ORM, type-safe, миграции
- DataStore — preferences, small data
- JSON asset для MVP (11 этапов литургии)

---

## ADR-005: Уведомления

**Контекст**: Пользователи хотят напоминания о службах.

**Решение**: WorkManager + NotificationManager

**Обоснование**:
- WorkManager — гарантированное выполнение, battery-efficient
- NotificationManager — стандартные каналы Android 8+

---

## Слои архитектуры

```
┌─────────────────────────────────────────────────────────────┐
│                     PRESENTATION LAYER                       │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ MainScreen  │  │ServiceScreen│  │  StageSelector      │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│  ┌─────────────────────────────────────────────────────────┐│
│  │                  ViewModel / State                       ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                       DOMAIN LAYER                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │CalendarEngine│ │StageMatcher │  │  ProgressTracker    │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│  ┌─────────────────────────────────────────────────────────┐│
│  │                    Use Cases                             ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                        DATA LAYER                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   Room DB   │  │  DataStore  │  │  JSON Assets        │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│  ┌─────────────────────────────────────────────────────────┐│
│  │                 Repository Implementation                ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

---

## Модель данных

### LiturgyStage
```
id: String (UUID)
order: Int
name: String (Проскомидия, Начало литургии, ...)
description: String
durationMin: Int
texts: List<StageText>
```

### StageText
```
type: String (молитва, возглас, ектения, пение, чтение)
original: String (церковнославянский)
translation: String (русский)
explanation: String
```

### ChurchDay
```
date: String (YYYY-MM-DD)
dayOfWeek: String
fasting: String? (null если не пост)
feast: String? (null если не праздник)
typicalService: String
scheduleToday: String?
```

### ServiceProgress
```
currentStage: LiturgyStage?
stageNumber: Int
totalStages: Int
elapsedMinutes: Int
remainingMinutes: Int
progressPercent: Int
nextStage: LiturgyStage?
```

---

## Навигация

```
MainScreen
  ├── ServiceScreen (автоматический режим)
  │     └── StageSelector (выбор этапа)
  └── SettingsScreen
        └── DND Settings
```

---

## Технологический стек

| Компонент | Технология | Версия |
|-----------|------------|--------|
| Язык | Kotlin | 1.9.x |
| UI | Jetpack Compose | BOM 2024.01 |
| DI | Koin | 3.5.x |
| База данных | Room | 2.6.x |
| Preferences | DataStore | 1.0.x |
| JSON | Gson | 2.10.x |
| Notifications | WorkManager | 2.9.x |
| Тестирование | JUnit4 + Robolectric | - |

---

## Будущие расширения

1. **Распознавание речи** — интеграция Whisper для автоматического определения этапа
2. **Бэкенд** — Firebase/Supabase для синхронизации и обновлений
3. **Уведомления** — WorkManager для напоминаний о службах
4. **Избранное** — Room для сохранения любимых молитв
5. **Календарь** — полный годовой цикл богослужений
