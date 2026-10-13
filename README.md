# db-project

Учебная time-series БД, вдохновлённая внутренним устройством Prometheus TSDB.
Проект курса ПИРСБД, ИТМО. 5 лабораторных = единый проект.

Спецификация: [docs/specs.md](docs/specs.md), форматы и юзкейсы: [docs/structure-and-usecases.md](docs/structure-and-usecases.md).

## Требования

- JDK 21+ (Gradle сам подтянет toolchain, если стоит `foojay`)
- Gradle wrapper уже в репо — отдельный Gradle ставить не нужно

## Сборка

```bash
./gradlew build
```

Первый запуск скачает Gradle 9.6 и зависимости — займёт несколько минут.

## Компиляция без тестов

```bash
./gradlew compileKotlin
```

## Тесты

```bash
./gradlew test
```

(Тесты появятся в лабе 2 вместе с реализациями интерфейсов.)

## Структура

```
src/main/kotlin/org/pirsbd/tsdb/
├── api/       — публичный контракт БД (TSDB, Appender, Querier, Sample, Labels, Matcher)
├── chunks/    — чанки (timestamp, value): Chunk, ChunkReader/Writer, ChunkBuilder
├── index/     — индекс блока: IndexReader/Writer, Postings, SymbolTable, SeriesRef
├── storage/   — блоки на диске: Block, BlockWriter, Storage, Tombstones
└── head/      — in-memory часть, флашится в Block
```

## Статус по лабам

- [x] Лаба 1 — интерфейсы
- [ ] Лаба 2 — реализация, локальная БД
- [ ] Лаба 3 — REST/gRPC сервер, Docker
- [ ] Лаба 4 — репликация
- [ ] Лаба 5 — шардирование
