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

## CI

Каждый push в `master` и каждый PR прогоняет `./gradlew build` и демо-сценарий
на JDK 21 — см. [.github/workflows/build.yml](.github/workflows/build.yml).
Отчёт по упавшим тестам выкладывается артефактом прогона.

## Тесты

```bash
./gradlew test
```

117 тестов шести уровней: round-trip кодировщиков, Head, k-way merge на фейковых
источниках, end-to-end, мусор и порча файлов, конкурентность.

## Демо

```bash
./gradlew run
```

Сценарий пишет серии, читает с фильтром по лейблам, удаляет диапазон, флашит Head
в блок на диске, дописывает в уже сброшенную серию, перезапускает БД и показывает
out-of-order вставку с перезаписью дубликата.

В выводе каждая серия помечена источником: `[HEAD]`, `[BLOCK]` или `[HEAD+BLOCK]`.
Последняя пометка это и есть доказательство, что запрос действительно сливает
память и диск, а не просто держит всё в памяти.

Посмотреть блок руками:

```bash
cat demo-data/*/meta.json          # метаданные блока
xxd -l 16 demo-data/*/index        # magic 0xBAAAD700
ls -R demo-data/                   # блоки на месте, .tmp не осталось
```

## Структура

```
src/main/kotlin/org/pirsbd/tsdb/
├── api/           — публичный контракт: TSDB, Appender, Querier, SeriesSource, модели
│   └── impl/      — DefaultTSDB, HeadAppender, DefaultQuerier, k-way merge
├── chunks/        — чанки (timestamp, value)
├── index/         — типы индекса блока
├── storage/       — блоки на диске: FileStorage, FileBlockWriter, DiskBlock
│   └── codec/     — форматы файлов: chunks, index, tombstones, meta.json
└── head/          — in-memory часть, отдаёт HeadSnapshot при флаше
```

Ключевая вещь в архитектуре: `SeriesSource` реализуют и `MemoryHead`, и `DiskBlock`,
поэтому запросу всё равно, откуда пришли данные. `DefaultQuerier` собирает подходящие
источники и сливает их ленивым k-way merge по лейблам, Head идёт первым и выигрывает
при равном таймстемпе.

Дизайн и обоснования: [docs/superpowers/specs/2026-10-14-lab2-local-tsdb-design.md](docs/superpowers/specs/2026-10-14-lab2-local-tsdb-design.md).

## Чего нет, сознательно

WAL и crash-recovery, компакция блоков, сжатие (XOR, дельты, LZ4), mmap, матчеры
кроме `=`. Обоснование в разделе 2 спеки.

Durability: `close()` и `Ctrl+C` сбрасывают Head в блок, данные переживают рестарт.
При `kill -9` несброшенный Head теряется, потому что журнала для переигрывания нет.
WAL отложен на лабу 4, где он нужен для репликации.

## Статус по лабам

- [x] Лаба 1 — интерфейсы
- [x] Лаба 2 — реализация, локальная БД
- [ ] Лаба 3 — REST/gRPC сервер, Docker
- [ ] Лаба 4 — репликация
- [ ] Лаба 5 — шардирование
