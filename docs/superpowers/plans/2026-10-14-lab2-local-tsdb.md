# Лаба 2: локально работающая TSDB — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Довести TSDB до состояния «локально работающая БД»: запись батчем, чтение по диапазону с фильтром по лейблам, удаление, флаш Head в блок на диск, чтение блоков при старте.

**Architecture:** Один публичный интерфейс `SeriesSource` реализуют и `MemoryHead`, и `DiskBlock`. `DefaultQuerier` собирает подходящие источники и сливает их ленивым k-way merge по `Labels`, при равном таймстемпе выигрывает Head. Флаш идёт через `HeadSnapshot`, поэтому Head ничего не знает про диск.

**Tech Stack:** Kotlin 2.4.10, JVM toolchain 21, Gradle 9.6, `kotlin-test` + JUnit Platform, `kotlinx.serialization` для `meta.json`, плагин `application` для демо.

**Spec:** `docs/superpowers/specs/2026-10-14-lab2-local-tsdb-design.md` (коммит `c050c31`)

## Global Constraints

- JVM toolchain 21, Kotlin 2.4.10. Не менять.
- Все числа на диске big-endian, фиксированной ширины: `int32`, `int64`, `float64`. Никаких varint и дельт.
- Magic: `index` = `0xBAAAD700`, `chunks` = `0x43484E4B`, `tombstones` = `0x0130BA30`. `version` = `1` во всех трёх.
- TOC индекса: ровно 36 байт (4 × `int64` + `crc32`), лежит в конце файла. Читатель делает `seek(fileLength - 36)`.
- CRC32 пишется и проверяется в каждой секции. Битый CRC это исключение, не молчаливый мусор.
- `encoding` чанка берётся из `ChunkEncoding.RAW.id` (= `0`), не из литерала.
- При равном таймстемпе из разных источников выигрывает Head.
- Дубликат пары (серия, timestamp): последний выигрывает, перезапись.
- Out-of-order сэмплы принимаются.
- Триггер автофлаша считается по таймстемпам сэмплов, не по `System.currentTimeMillis()`.
- `headWindow` по умолчанию `2 * 60 * 60 * 1000`, `stripeSize` по умолчанию `16`.
- `Labels` обязаны содержать `__name__`. Проверка в `Appender.add()`, не в `commit()`.
- Вне скоупа: WAL, crash-recovery, компакция, XOR, mmap, матчеры кроме `=`.
- TDD: сначала падающий тест. Коммит на каждую задачу.
- Публичный класс не может наследовать `internal` интерфейс. `SeriesSource` объявляется `public`.

## Review Focus

Пять классов входных данных, которые спека подразумевает, но ни один шаг явно не покрывает. Тест на каждый добавлен в задачу, владеющую кодом.

1. **Пустая БД.** `querier()` на пустом каталоге и до первой записи. `SeriesSet.next()` обязан вернуть `false`, а не упасть. Тест в задаче 8.
2. **`range` пустого Head.** `minTime` стартует с `Long.MAX_VALUE`, `maxTime` с `Long.MIN_VALUE`, поэтому `TimeRange(minTime, maxTime)` падает на `require(minTime <= maxTime)`. Пустой источник обязан отдавать `TimeRange.EMPTY` и не пересекаться ни с чем. Тест в задаче 1.
3. **Переполнение в триггере флаша.** `head.range.minTime + headWindow` переполняется, если `minTime` близко к `Long.MAX_VALUE`. Обязан не уходить в отрицательное. Тест в задаче 8.
4. **Серия, полностью покрытая tombstone.** Должна исчезнуть из результата целиком, а не вернуться пустой серией без сэмплов. Тест в задаче 9.
5. **`Labels` с дублем имени лейбла.** `Labels.of(mapOf(...))` дедупликации не делает, а `Label` сортируется по `(name, value)`. Две записи с одним именем обязаны давать детерминированный результат, а не разъезжаться между Head и блоком. Тест в задаче 4.

---

## Структура файлов

**Создаются:**

| Файл | Ответственность |
|---|---|
| `api/SeriesSource.kt` | публичный интерфейс источника серий |
| `api/TSDBConfig.kt` | `headWindow`, `stripeSize` |
| `api/impl/LabelsOrder.kt` | `compareLabels`, единый порядок лейблов |
| `api/impl/MergeSeriesSet.kt` | k-way merge источников по `Labels` |
| `api/impl/MergeSeriesIterator.kt` | merge сэмплов по таймстемпу, приоритет Head |
| `api/impl/HeadAppender.kt` | буфер батча, `commit`/`rollback` |
| `api/impl/DefaultQuerier.kt` | снимок источников на `range` |
| `head/HeadSnapshot.kt` | `HeadSnapshot`, `SnapshotSeries`, `Tombstone` |
| `storage/codec/ByteIo.kt` | примитивы: `int32`, `int64`, `float64`, utf8, CRC32 |
| `storage/codec/ChunkFileCodec.kt` | запись и чтение файла `chunks/000001` |
| `storage/codec/IndexFileCodec.kt` | symbol table, series, postings, offset table, TOC |
| `storage/codec/TombstoneFileCodec.kt` | запись и чтение файла `tombstones` |
| `storage/codec/BlockMetaJson.kt` | `meta.json` через `kotlinx.serialization` |
| `storage/DiskBlock.kt` | `Block` + `SeriesSource` поверх папки блока |
| `storage/FileBlockWriter.kt` | `.tmp` → `fsync` → `rename` |
| `storage/FileStorage.kt` | скан каталога, чистка `.tmp`, список блоков |

**Изменяются:**

| Файл | Что |
|---|---|
| `build.gradle.kts` | плагины `serialization` и `application`, зависимость `kotlinx-serialization-json` |
| `index/SeriesRef.kt` | `internal` → `public` |
| `head/Head.kt` | `: SeriesSource`, `trim` → `truncate(upTo: Long): HeadSnapshot?` |
| `head/MemoryHead.kt` | реализует `SeriesSource`, `range` для пустого Head, `append` после `close` |
| `head/MemoryChunk.kt` | сортированный вывод, id серии только на новую серию |
| `head/PostingList.kt` | пустые матчеры = все серии |
| `head/memseries/MemorySeries.kt` | перезапись дубликата таймстемпа |
| `api/Sample.kt` | `TimeRange.EMPTY` |
| `api/impl/LabelsSeries.kt` | `iterator()` отдаёт новый инстанс |
| `api/impl/DefaultTSDB.kt` | полная реализация вместо `TODO()` |
| `api/TSDB.kt` | `fun flush()` в интерфейс |
| `storage/BlockWriter.kt` | `Storage.newBlockWriter()` без параметра |
| `Main.kt` | демо-сценарий, пакет `org.pirsbd.tsdb` |

**Удаляется:** `api/impl/HeadManager.kt` (пустой класс).

---

## Task 0: Починить компиляцию и завести тестовый контур

**Files:**
- Modify: `src/main/kotlin/org/pirsbd/tsdb/index/SeriesRef.kt`
- Modify: `build.gradle.kts`
- Delete: `src/main/kotlin/org/pirsbd/tsdb/api/impl/HeadManager.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/CompileSmokeTest.kt`

**Interfaces:**
- Consumes: ничего.
- Produces: `SeriesRef` снова `public`. Сборка и `./gradlew test` проходят. `TestLabels.of(vararg Pair<String,String>): Labels` и `TestLabels.drain(SeriesSet): List<Pair<Labels, List<Sample>>>` как общие хелперы для всех дальнейших тестов.

- [ ] **Step 1: Смержить PR #7 в master**

```bash
git fetch origin
gh pr checkout 7
git rebase origin/master
git push --force-with-lease
gh pr merge 7 --merge
git checkout master && git pull
git checkout -b pochti-ceasar/lab2-impl
```

Ветка PR отстаёт от master на 2 коммита, поэтому ребейз обязателен. Одобрение PR не требуется, ruleset это разрешает.

- [ ] **Step 2: Написать падающий тест на сборку**

`src/test/kotlin/org/pirsbd/tsdb/CompileSmokeTest.kt`:

```kotlin
package org.pirsbd.tsdb

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.index.SeriesRef
import kotlin.test.Test
import kotlin.test.assertEquals

object TestLabels {
    fun of(vararg pairs: Pair<String, String>): Labels =
        Labels(pairs.map { Label(it.first, it.second) }.sorted())

    fun metric(name: String, vararg pairs: Pair<String, String>): Labels =
        of(*(arrayOf(Labels.METRIC_NAME to name) + pairs))

    fun drain(set: SeriesSet): List<Pair<Labels, List<Sample>>> {
        val out = ArrayList<Pair<Labels, List<Sample>>>()
        set.use {
            while (it.next()) {
                val series = it.at()
                val samples = ArrayList<Sample>()
                series.iterator().use { si -> while (si.next()) samples.add(si.at()) }
                out.add(series.labels to samples)
            }
        }
        return out
    }
}

class CompileSmokeTest {
    @Test
    fun `SeriesRef is usable from a public context`() {
        val ref: SeriesRef = SeriesRef(42L)
        assertEquals(42L, ref.value)
    }
}
```

- [ ] **Step 3: Прогнать и убедиться, что падает**

Run: `./gradlew test --console=plain`
Expected: FAIL, 8 ошибок компиляции вида `'public' function exposes its 'internal' return type 'SeriesRef'` в `Postings.kt`, `IndexReader.kt`, `IndexWriter.kt`, `storage/Tombstones.kt`.

- [ ] **Step 4: Снять internal**

`index/SeriesRef.kt`:

```kotlin
package org.pirsbd.tsdb.index

@JvmInline
value class SeriesRef(val value: Long)
```

- [ ] **Step 5: Добавить плагины и зависимость**

`build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

group = "org.pirsbd.tsdb"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.pirsbd.tsdb.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
```

- [ ] **Step 6: Удалить пустой класс и мусор IDE**

```bash
git rm src/main/kotlin/org/pirsbd/tsdb/api/impl/HeadManager.kt
git rm --cached .idea/gradle.xml .idea/kotlinc.xml .idea/misc.xml .idea/.gitignore
```

- [ ] **Step 7: Прогнать тесты**

Run: `./gradlew build --console=plain`
Expected: PASS, `BUILD SUCCESSFUL`, 1 тест прошёл.

- [ ] **Step 8: Коммит**

```bash
git add -A
git commit -m "[lab-2]: fix compilation, add serialization and application plugins

SeriesRef was internal while public Postings, IndexReader, IndexWriter and
storage.Tombstones expose it, which broke the build with 8 errors.

Also adds the test scaffolding shared by the rest of lab 2 and drops the
empty HeadManager class."
```

---

## Task 1: Фундамент SeriesSource и пять багов из ревью PR #7

**Files:**
- Create: `src/main/kotlin/org/pirsbd/tsdb/api/SeriesSource.kt`
- Create: `src/main/kotlin/org/pirsbd/tsdb/api/impl/LabelsOrder.kt`
- Rename: `api/impl/MapSeriesSet.kt` → `api/impl/SortedMapSeriesSet.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/head/Head.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/api/Sample.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/api/impl/LabelsSeries.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/head/PostingList.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/head/MemoryHead.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/head/memseries/MemorySeries.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/head/MemoryHeadBugsTest.kt`

**Interfaces:**
- Consumes: `TestLabels.of`, `TestLabels.metric`, `TestLabels.drain` из задачи 0.
- Produces: `interface SeriesSource : AutoCloseable { val range: TimeRange; fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet }`. `object LabelsOrder` с `compare(a, b)` и `COMPARATOR`. `internal class SortedMapSeriesSet(series: SortedMap<Labels, List<Sample>>)`. `interface Head : SeriesSource`. `TimeRange.EMPTY: TimeRange`. `MemoryHead.range` отдаёт `TimeRange.EMPTY` для пустого Head.

Фундамент идёт в эту же задачу, а не в следующую, потому что тесты на баги вызывают `head.select(...)` и опираются на сортированный вывод. Без `SeriesSource` и `LabelsOrder` они не скомпилируются. `MemoryHead.append` после `close()` кидает `IllegalStateException`. `PostingList.query(emptyList())` отдаёт все серии. `LabelsSeries.iterator()` отдаёт новый инстанс при каждом вызове. `MemorySeries.append` перезаписывает значение при совпадении таймстемпа.

- [ ] **Step 1: Написать шесть падающих тестов**

`src/test/kotlin/org/pirsbd/tsdb/head/MemoryHeadBugsTest.kt`:

```kotlin
package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private val ALL = TimeRange(0, 1_000)
private fun cpu() = metric("cpu")
private fun eq(name: String, value: String) = listOf(Matcher.Equals(name, value))

class MemoryHeadBugsTest {

    @Test
    fun `B empty matchers select all series`() {
        val head = MemoryHead()
        head.append(metric("cpu"), 10, 1.0)
        head.append(metric("mem"), 10, 2.0)

        val got = drain(head.select(ALL, emptyList()))

        assertEquals(2, got.size)
        assertEquals(listOf(metric("cpu"), metric("mem")), got.map { it.first })
    }

    @Test
    fun `C series iterator is fresh on every call`() {
        val head = MemoryHead()
        head.append(cpu(), 10, 1.0)

        head.select(ALL, eq("__name__", "cpu")).use { set ->
            assertEquals(true, set.next())
            val series = set.at()

            val first = ArrayList<Sample>()
            series.iterator().use { while (it.next()) first.add(it.at()) }
            val second = ArrayList<Sample>()
            series.iterator().use { while (it.next()) second.add(it.at()) }

            assertEquals(first, second)
            assertEquals(listOf(Sample(10, 1.0)), first)
        }
    }

    @Test
    fun `D duplicate timestamp is overwritten by the last value`() {
        val head = MemoryHead()
        head.append(cpu(), 10, 1.0)
        head.append(cpu(), 10, 99.0)

        val samples = drain(head.select(ALL, eq("__name__", "cpu")))[0].second

        assertEquals(listOf(Sample(10, 99.0)), samples)
    }

    @Test
    fun `D2 duplicate timestamp is overwritten on the out of order path`() {
        val head = MemoryHead()
        head.append(cpu(), 30, 3.0)
        head.append(cpu(), 10, 1.0)
        head.append(cpu(), 10, 77.0)

        val samples = drain(head.select(ALL, eq("__name__", "cpu")))[0].second

        assertEquals(listOf(Sample(10, 77.0), Sample(30, 3.0)), samples)
    }

    @Test
    fun `E range of an empty head is EMPTY and overlaps nothing`() {
        val head = MemoryHead()

        assertEquals(TimeRange.EMPTY, head.range)
        assertFalse(head.range.overlaps(TimeRange(0, Long.MAX_VALUE - 1)))
    }

    @Test
    fun `F append after close fails loudly`() {
        val head = MemoryHead()
        head.close()

        assertFailsWith<IllegalStateException> { head.append(cpu(), 10, 1.0) }
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падают**

Run: `./gradlew test --tests '*MemoryHeadBugsTest*' --console=plain`
Expected: FAIL. `B` падает с `IndexOutOfBoundsException: Index 0 out of bounds for length 0`, `C` с `expected: <[Sample(10, 1.0)]> but was: <[]>`, `D` с двумя сэмплами вместо одного, `E` с `IllegalArgumentException: minTime > maxTime`, `F` без исключения. Плюс ошибки компиляции на `head.select` и `TimeRange.EMPTY`, их пока нет.

- [ ] **Step 3: Создать `SeriesSource`, `LabelsOrder`, `SortedMapSeriesSet`, обновить `Head`**

Содержимое четырёх файлов целиком приведено в задаче 2, шаги 3 и 5, и в задаче 2, шаг 6: `api/SeriesSource.kt`, `api/impl/LabelsOrder.kt`, `api/impl/SortedMapSeriesSet.kt` и новый `head/Head.kt`. Создать их здесь, дословно, кроме метода `truncate` в `Head`: он появится в задаче 2 вместе с `HeadSnapshot`. Пока в `Head` только `append`, `delete` и унаследованные от `SeriesSource` `range` и `select`.

- [ ] **Step 4: Добавить `TimeRange.EMPTY`**

`api/Sample.kt`:

```kotlin
data class TimeRange(val minTime: Long, val maxTime: Long) {
    init {
        require(minTime <= maxTime) { "minTime > maxTime: $minTime > $maxTime" }
    }

    fun overlaps(other: TimeRange): Boolean =
        minTime <= other.maxTime && other.minTime <= maxTime

    fun contains(t: Long): Boolean = t in minTime..maxTime

    companion object {
        /**
         * Диапазон пустого источника. Не пересекается ни с одним диапазоном,
         * у которого maxTime < Long.MAX_VALUE, поэтому пустой Head и пустой
         * блок никогда не попадают в выборку.
         */
        val EMPTY = TimeRange(Long.MAX_VALUE, Long.MAX_VALUE)
    }
}
```

- [ ] **Step 5: Починить `LabelsSeries.iterator()`**

`api/impl/LabelsSeries.kt`:

```kotlin
package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.Series
import org.pirsbd.tsdb.api.SeriesIterator

internal class LabelsSeries(
    override val labels: Labels,
    private val samples: List<Sample>,
) : Series {
    /** Новый итератор на каждый вызов: Series можно обойти несколько раз. */
    override fun iterator(): SeriesIterator = ListSeriesSampleIterator(samples)
}
```

- [ ] **Step 6: Починить `PostingList` на пустых матчерах**

`head/PostingList.kt`, заменить `query` и `toLabels`:

```kotlin
    /** Отсортированный список всех известных серий. */
    private val allRefs: ArrayList<SeriesRef> = ArrayList()

    fun append(labels: Labels, ref: SeriesRef) {
        lock.writeLock().withLock {
            insertSorted(allRefs, ref)
            for (label in labels) {
                val refs = labelRefMap.getOrPut(label) { ArrayList() }
                insertSorted(refs, ref)
            }
        }
    }

    fun query(matchers: List<Matcher>): List<SeriesRef> {
        lock.readLock().withLock {
            if (matchers.isEmpty()) return ArrayList(allRefs)

            val lists = ArrayList<List<SeriesRef>>(matchers.size)
            for (label in toLabels(matchers)) {
                val list = labelRefMap[label] ?: return emptyList()
                lists.add(list)
            }

            val shortest = lists.minBy { it.size }
            val result = ArrayList<SeriesRef>()
            for (ref in shortest) {
                if (lists.all { it === shortest || it.binarySearch { x -> x.value.compareTo(ref.value) } >= 0 }) {
                    result.add(ref)
                }
            }
            return result
        }
    }

    private fun insertSorted(list: ArrayList<SeriesRef>, ref: SeriesRef) {
        val index = list.binarySearch { x -> x.value.compareTo(ref.value) }
        if (index < 0) list.add(-(index + 1), ref)
    }
```

`getOrPut` вместо `putIfAbsent(label, arrayListOf(ref))` убирает аллокацию списка на каждый вызов. `allRefs` заодно даёт путь «все серии», тот же, что `allPostings` в индексе на диске.

- [ ] **Step 7: Починить `MemorySeries.append` на перезапись**

`head/memseries/MemorySeries.kt`:

```kotlin
    fun append(timestamp: Long, value: Double) {
        lock.writeLock().withLock {
            when {
                timestamp == latestTimestamp && inOrderSeries.count() > 0 ->
                    inOrderSeries.replaceLast(timestamp, value)

                timestamp > latestTimestamp -> {
                    inOrderSeries.insert(timestamp, value)
                    latestTimestamp = timestamp
                }

                else -> addOutOfOrder(timestamp, value)
            }
        }
    }

    private fun addOutOfOrder(timestamp: Long, value: Double) {
        val found = outOfOrderSeries.binarySearch { x -> x.timestamp.compareTo(timestamp) }
        if (found >= 0) {
            outOfOrderSeries[found] = Sample(timestamp, value)
        } else {
            outOfOrderSeries.add(-(found + 1), Sample(timestamp, value))
        }
    }
```

`InOrderSeries` получает новый метод, `RawInOrderSeries` его реализует:

```kotlin
// head/memseries/InOrderSeries.kt
interface InOrderSeries {
    fun insert(timestamp: Long, value: Double)
    /** Перезаписывает последний сэмпл. Вызывается, когда таймстемп совпал. */
    fun replaceLast(timestamp: Long, value: Double)
    fun iterator(): PeekingIterator<Sample>
    fun count(): Int
}
```

```kotlin
// head/memseries/RawInOrderSeries.kt
    override fun replaceLast(timestamp: Long, value: Double) {
        val count = buffer.getInt(0)
        require(count > 0) { "replaceLast on an empty series" }
        val offset = Int.SIZE_BYTES + (count - 1) * (Long.SIZE_BYTES + Double.SIZE_BYTES)
        buffer.putLong(offset, timestamp)
        buffer.putDouble(offset + Long.SIZE_BYTES, value)
    }
```

- [ ] **Step 8: Починить `MemoryHead`: range, append после close, SeriesSource**

Конструктор получает параметр с умолчанием, иначе `MemoryHead()` из тестов и `MemoryHead(config.stripeSize)` из задачи 8 разойдутся:

```kotlin
class MemoryHead(stripeSize: Int = 16) : Head {
    private val chunk: MemoryChunk = MemoryChunk(stripeSize)
```

`MemoryChunk` соответственно принимает `stripeSize` и передаёт его в `Stripe`, вместо константы `StripeSize = 14`.

Далее заменить `range`, `append`, `query`:

```kotlin
    override val range: TimeRange
        get() {
            val min = minTime.load()
            val max = maxTime.load()
            return if (min > max) TimeRange.EMPTY else TimeRange(min, max)
        }

    override fun append(labels: Labels, timestamp: Long, value: Double) {
        lock.readLock().withLock {
            check(!isClosed) { "head is closed" }
            chunk.append(labels, timestamp, value)
            updateRange(timestamp)
        }
    }

    override fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet {
        val result = lock.readLock().withLock { chunk.query(matchers, range) }
        return SortedMapSeriesSet(result)
    }
```

`MemoryChunk.query` меняет тип на `java.util.TreeMap<Labels, List<Sample>>` с компаратором `LabelsOrder.COMPARATOR` из шага 3, поэтому вывод уже отсортирован.

- [ ] **Step 9: Прогнать тесты**

Run: `./gradlew test --tests '*MemoryHeadBugsTest*' --console=plain`
Expected: PASS, 6 тестов.

- [ ] **Step 10: Коммит**

```bash
git add -A
git commit -m "[lab-2]: fix five bugs found while reviewing the head implementation

- empty matcher list selected nothing and crashed on an empty list
- Series.iterator() returned one shared instance, so a second pass was empty
- a duplicate timestamp kept both samples; the last value now wins
- range of an empty head violated TimeRange's own require(minTime <= maxTime)
- append() after close() dropped data silently, now it throws"
```

---

## Task 2: Ленивый merge и HeadSnapshot в интерфейсе Head

**Files:**
- Create: `src/main/kotlin/org/pirsbd/tsdb/api/impl/MergeSeriesSet.kt`
- Create: `src/main/kotlin/org/pirsbd/tsdb/api/impl/MergeSeriesIterator.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/head/Head.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/merge/FakeSeriesSource.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/merge/MergeSeriesSetTest.kt`

**Interfaces:**
- Consumes: `SeriesSource`, `LabelsOrder`, `SortedMapSeriesSet`, `TimeRange.EMPTY` из задачи 1, `TestLabels` из задачи 0.
- Produces:
  - `internal class MergeSeriesSet(sources: List<SeriesSet>) : SeriesSet`
  - `internal class MergeSeriesIterator(iterators: List<SeriesIterator>) : SeriesIterator`
  - `interface Head : SeriesSource` с `truncate(upTo: Long): HeadSnapshot?`

- [ ] **Step 1: Написать семь падающих тестов на фейковых источниках**

`src/test/kotlin/org/pirsbd/tsdb/merge/FakeSeriesSource.kt`:

```kotlin
package org.pirsbd.tsdb.merge

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.LabelsOrder
import org.pirsbd.tsdb.api.impl.SortedMapSeriesSet
import java.util.TreeMap

/** Источник из заранее заданных серий. Считает, сколько раз его опрашивали. */
class FakeSeriesSource(series: Map<Labels, List<Sample>>) : SeriesSource {
    var selectCalls: Int = 0
        private set

    private val data = TreeMap<Labels, List<Sample>>(LabelsOrder.COMPARATOR).also { it.putAll(series) }

    override val range: TimeRange
        get() {
            val all = data.values.flatten()
            if (all.isEmpty()) return TimeRange.EMPTY
            return TimeRange(all.minOf { it.timestamp }, all.maxOf { it.timestamp })
        }

    override fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet {
        selectCalls += 1
        val filtered = TreeMap<Labels, List<Sample>>(LabelsOrder.COMPARATOR)
        for ((labels, samples) in data) {
            if (matchers.any { m -> labels.get(m.name) != (m as Matcher.Equals).value }) continue
            val inRange = samples.filter { range.contains(it.timestamp) }
            if (inRange.isNotEmpty()) filtered[labels] = inRange
        }
        return SortedMapSeriesSet(filtered)
    }

    override fun close() = Unit
}
```

`src/test/kotlin/org/pirsbd/tsdb/merge/MergeSeriesSetTest.kt`:

```kotlin
package org.pirsbd.tsdb.merge

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.MergeSeriesSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private val ALL = TimeRange(0, 1_000)

class MergeSeriesSetTest {

    private fun merge(vararg sources: FakeSeriesSource) =
        MergeSeriesSet(sources.map { it.select(ALL, emptyList()) })

    @Test
    fun `1 different labels come out ordered by labels`() {
        val a = FakeSeriesSource(mapOf(metric("mem") to listOf(Sample(10, 2.0))))
        val b = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0))))

        val got = drain(merge(a, b))

        assertEquals(listOf(metric("cpu"), metric("mem")), got.map { it.first })
    }

    @Test
    fun `2 same labels in two sources merge their samples by timestamp`() {
        val head = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(30, 3.0))))
        val block = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0), Sample(20, 2.0))))

        val got = drain(merge(head, block))

        assertEquals(1, got.size)
        assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0), Sample(30, 3.0)), got[0].second)
    }

    @Test
    fun `3 on an equal timestamp the first source wins`() {
        val head = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 999.0))))
        val block = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0))))

        val got = drain(merge(head, block))

        assertEquals(listOf(Sample(10, 999.0)), got[0].second)
    }

    @Test
    fun `4 an empty source in the middle is skipped`() {
        val a = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0))))
        val empty = FakeSeriesSource(emptyMap())
        val c = FakeSeriesSource(mapOf(metric("mem") to listOf(Sample(10, 2.0))))

        val got = drain(merge(a, empty, c))

        assertEquals(listOf(metric("cpu"), metric("mem")), got.map { it.first })
    }

    @Test
    fun `5 three sources with overlapping ranges`() {
        val a = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0), Sample(40, 4.0))))
        val b = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(20, 2.0))))
        val c = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(30, 3.0), Sample(50, 5.0))))

        val got = drain(merge(a, b, c))

        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), got[0].second.map { it.timestamp })
    }

    @Test
    fun `6 all sources empty means next is false right away`() {
        val got = merge(FakeSeriesSource(emptyMap()), FakeSeriesSource(emptyMap()))

        got.use { assertFalse(it.next()) }
    }

    @Test
    fun `7 merging nothing at all is also false`() {
        MergeSeriesSet(emptyList()).use { assertFalse(it.next()) }
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падают**

Run: `./gradlew test --tests '*MergeSeriesSetTest*' --console=plain`
Expected: FAIL, ошибки компиляции: нет `SeriesSource`, `LabelsOrder`, `MergeSeriesSet`, `SortedMapSeriesSet`.

- [ ] **Step 3: Справочно, файлы фундамента**

Эти три файла уже созданы в задаче 1, шаг 3. Их содержимое приведено здесь как источник истины, потому что задача 1 ссылается на него. Если они на месте, шаг пропускается.

`api/SeriesSource.kt`:

```kotlin
package org.pirsbd.tsdb.api

/**
 * Источник серий. Реализуют in-memory Head и блок на диске, поэтому
 * запросу всё равно, откуда пришли данные.
 *
 * Публичный, а не internal: MemoryHead публичный, а публичный класс не может
 * наследовать internal интерфейс.
 */
interface SeriesSource : AutoCloseable {
    /** TimeRange.EMPTY, если источник пуст. */
    val range: TimeRange

    /** Серии отсортированы по Labels. Сэмплы ленивые. */
    fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet
}
```

`api/impl/LabelsOrder.kt`:

```kotlin
package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels

/** Единый порядок Labels: лексикографический по парам (name, value). */
object LabelsOrder {
    fun compare(a: Labels, b: Labels): Int {
        val common = minOf(a.size, b.size)
        for (i in 0 until common) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    val COMPARATOR: Comparator<Labels> = Comparator { a, b -> compare(a, b) }
}
```

- [ ] **Step 4: Реализовать merge**

`api/impl/MergeSeriesIterator.kt`:

```kotlin
package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.SeriesIterator

/**
 * Сливает сэмплы нескольких итераторов по возрастанию таймстемпа.
 * При равных таймстемпах выигрывает итератор, идущий раньше в списке,
 * поэтому Head передаётся первым.
 */
internal class MergeSeriesIterator(iterators: List<SeriesIterator>) : SeriesIterator {
    private val live = ArrayList<SeriesIterator>(iterators.size)
    private var current: Sample? = null

    init {
        for (it in iterators) {
            if (it.next()) live.add(it) else it.close()
        }
    }

    override fun next(): Boolean {
        if (live.isEmpty()) {
            current = null
            return false
        }

        var best = 0
        for (i in live.indices) {
            if (live[i].at().timestamp < live[best].at().timestamp) best = i
        }
        val timestamp = live[best].at().timestamp
        current = live[best].at()

        var i = 0
        while (i < live.size) {
            if (live[i].at().timestamp == timestamp && !live[i].next()) {
                live[i].close()
                live.removeAt(i)
            } else {
                i += 1
            }
        }
        return true
    }

    override fun at(): Sample = current ?: throw NoSuchElementException()

    override fun close() {
        live.forEach { it.close() }
        live.clear()
    }
}
```

`api/impl/MergeSeriesSet.kt`:

```kotlin
package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Series
import org.pirsbd.tsdb.api.SeriesIterator
import org.pirsbd.tsdb.api.SeriesSet

/**
 * k-way merge источников по Labels. Источники обязаны отдавать серии
 * отсортированными. Приоритет при совпадении Labels у того, кто раньше
 * в списке: Head передаётся первым.
 */
internal class MergeSeriesSet(sources: List<SeriesSet>) : SeriesSet {
    private val live = ArrayList<SeriesSet>(sources.size)
    private val pending = ArrayList<SeriesSet>()
    private var current: Series? = null

    init {
        for (s in sources) {
            if (s.next()) live.add(s) else s.close()
        }
    }

    override fun next(): Boolean {
        advancePending()

        if (live.isEmpty()) {
            current = null
            return false
        }

        var min = live[0].at().labels
        for (s in live) {
            val labels = s.at().labels
            if (LabelsOrder.compare(labels, min) < 0) min = labels
        }

        val iterators = ArrayList<SeriesIterator>()
        for (s in live) {
            if (LabelsOrder.compare(s.at().labels, min) == 0) {
                iterators.add(s.at().iterator())
                pending.add(s)
            }
        }

        current = MergedSeries(min, iterators)
        return true
    }

    override fun at(): Series = current ?: throw NoSuchElementException()

    override fun close() {
        live.forEach { it.close() }
        live.clear()
        pending.clear()
    }

    /**
     * Источники двигаются не сразу, а в начале следующего next(). Иначе
     * at() вернул бы серию, которую источник уже затёр.
     */
    private fun advancePending() {
        for (s in pending) {
            if (!s.next()) {
                live.remove(s)
                s.close()
            }
        }
        pending.clear()
    }

    private class MergedSeries(
        override val labels: Labels,
        private val iterators: List<SeriesIterator>,
    ) : Series {
        override fun iterator(): SeriesIterator = MergeSeriesIterator(iterators)
    }
}
```

- [ ] **Step 5: Справочно, `SortedMapSeriesSet`**

Переименование `MapSeriesSet` сделано в задаче 1. Содержимое приведено как источник истины.

`api/impl/MapSeriesSet.kt` → `api/impl/SortedMapSeriesSet.kt`:

```kotlin
package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.Series
import org.pirsbd.tsdb.api.SeriesSet
import java.util.SortedMap

/** Тип параметра SortedMap, а не Map: порядок серий это часть контракта SeriesSource. */
internal class SortedMapSeriesSet(series: SortedMap<Labels, List<Sample>>) : SeriesSet {
    private val iterator = series.entries.iterator()
    private var current: Series? = null

    override fun next(): Boolean {
        if (!iterator.hasNext()) {
            current = null
            return false
        }
        val entry = iterator.next()
        current = LabelsSeries(entry.key, entry.value)
        return true
    }

    override fun at(): Series = current ?: throw NoSuchElementException()

    override fun close() = Unit
}
```

- [ ] **Step 6: Добавить `truncate` в интерфейс `Head`**

Сам интерфейс `Head : SeriesSource` создан в задаче 1. Здесь добавляется только `truncate`, вместе с `HeadSnapshot` из задачи 6. Итоговый вид:

`head/Head.kt`:

```kotlin
package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TimeRange

interface Head : SeriesSource {
    fun append(labels: Labels, timestamp: Long, value: Double)
    fun delete(range: TimeRange, matchers: List<Matcher>)

    /**
     * Отрезает всё строго меньше upTo и отдаёт снимок отрезанного.
     * null, если под upTo не попал ни один сэмпл.
     *
     * Снимок, а не Block: Block это артефакт на диске, и Head не должен
     * знать ни про папку, ни про ULID, ни про писателя.
     */
    fun truncate(upTo: Long): HeadSnapshot?
}
```

- [ ] **Step 7: Прогнать тесты**

Run: `./gradlew test --console=plain`
Expected: PASS, 14 тестов (1 + 6 + 7).

- [ ] **Step 8: Коммит**

```bash
git add -A
git commit -m "[lab-2]: add SeriesSource and the lazy k-way merge

One interface is implemented by both the in-memory head and the on-disk
block, so a query does not care where series come from. MergeSeriesSet
merges sources by Labels and MergeSeriesIterator merges samples by
timestamp, with the first source winning on a tie so the head beats a block.

Sources advance at the start of the next next() call, not when the series
is handed out, otherwise at() would return a series the source already
overwrote."
```

---

## Task 3: Примитивы ввода-вывода и файл чанков

**Files:**
- Create: `src/main/kotlin/org/pirsbd/tsdb/storage/codec/ByteIo.kt`
- Create: `src/main/kotlin/org/pirsbd/tsdb/storage/codec/ChunkFileCodec.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/storage/codec/ChunkFileCodecTest.kt`

**Interfaces:**
- Consumes: `ChunkEncoding`, `ChunkRef`, `Sample`, `TimeRange`.
- Produces:
  - `internal class CountingOutput(out: OutputStream)` с `writeInt`, `writeLong`, `writeDouble`, `writeUtf8`, `position: Long`
  - `internal class ByteReader(bytes: ByteArray)` с `readInt`, `readLong`, `readDouble`, `readUtf8`, `seek`, `position`
  - `internal fun crc32(bytes: ByteArray, from: Int, length: Int): Int`
  - `internal class CorruptBlockException(message: String) : IOException`
  - `internal object ChunkFileCodec` с `write(out: OutputStream, chunks: List<List<Sample>>): List<ChunkRef>` и `read(bytes: ByteArray, ref: ChunkRef): List<Sample>`
  - `ChunkRef` кодирует номер файла в старших 32 битах и оффсет в младших

- [ ] **Step 1: Написать пять падающих тестов**

`src/test/kotlin/org/pirsbd/tsdb/storage/codec/ChunkFileCodecTest.kt`:

```kotlin
package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.Sample
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkFileCodecTest {

    private fun roundTrip(chunks: List<List<Sample>>): Pair<ByteArray, List<Sample>> {
        val out = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(out, chunks)
        val bytes = out.toByteArray()
        return bytes to ChunkFileCodec.read(bytes, refs[0])
    }

    @Test
    fun `a single chunk round trips`() {
        val samples = listOf(Sample(10, 1.5), Sample(20, 2.5), Sample(30, -3.0))

        val (_, back) = roundTrip(listOf(samples))

        assertEquals(samples, back)
    }

    @Test
    fun `several chunks each round trip through their own ref`() {
        val first = listOf(Sample(10, 1.0))
        val second = listOf(Sample(20, 2.0), Sample(30, 3.0))
        val out = ByteArrayOutputStream()

        val refs = ChunkFileCodec.write(out, listOf(first, second))
        val bytes = out.toByteArray()

        assertEquals(first, ChunkFileCodec.read(bytes, refs[0]))
        assertEquals(second, ChunkFileCodec.read(bytes, refs[1]))
    }

    @Test
    fun `extreme values survive the round trip`() {
        val samples = listOf(
            Sample(Long.MIN_VALUE, Double.MIN_VALUE),
            Sample(0, 0.0),
            Sample(Long.MAX_VALUE, Double.MAX_VALUE),
        )

        val (_, back) = roundTrip(listOf(samples))

        assertEquals(samples, back)
    }

    @Test
    fun `a flipped byte in the data is caught by the crc`() {
        val out = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(out, listOf(listOf(Sample(10, 1.0))))
        val bytes = out.toByteArray()
        val offset = (refs[0].value and 0xFFFFFFFFL).toInt()
        bytes[offset + 9] = (bytes[offset + 9] + 1).toByte()

        val error = assertFailsWith<CorruptBlockException> { ChunkFileCodec.read(bytes, refs[0]) }

        assertEquals(true, error.message!!.contains("crc"))
    }

    @Test
    fun `a wrong magic is rejected`() {
        val out = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(out, listOf(listOf(Sample(10, 1.0))))
        val bytes = out.toByteArray()
        bytes[0] = 0

        assertFailsWith<CorruptBlockException> { ChunkFileCodec.read(bytes, refs[0]) }
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падают**

Run: `./gradlew test --tests '*ChunkFileCodecTest*' --console=plain`
Expected: FAIL, ошибки компиляции: нет `ChunkFileCodec` и `CorruptBlockException`.

- [ ] **Step 3: Реализовать примитивы**

`storage/codec/ByteIo.kt`:

```kotlin
package org.pirsbd.tsdb.storage.codec

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Файл блока не прошёл проверку: magic, version, длина или CRC32. */
internal class CorruptBlockException(message: String) : IOException(message)

internal fun crc32(bytes: ByteArray, from: Int, length: Int): Int {
    val crc = CRC32()
    crc.update(bytes, from, length)
    return crc.value.toInt()
}

/** Пишет big-endian и считает позицию, чтобы запомнить оффсеты секций. */
internal class CountingOutput(private val out: OutputStream) {
    var position: Long = 0
        private set

    fun writeByte(value: Int) {
        out.write(value)
        position += 1
    }

    fun writeInt(value: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
        position += 4
    }

    fun writeLong(value: Long) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array())
        position += 8
    }

    fun writeDouble(value: Double) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array())
        position += 8
    }

    fun writeBytes(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    fun writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        writeBytes(bytes)
    }

    fun flush() = out.flush()
}

/** Читает big-endian из массива, проверяя границы. */
internal class ByteReader(private val bytes: ByteArray) {
    var position: Int = 0
        private set

    val size: Int get() = bytes.size

    fun seek(to: Int) {
        if (to < 0 || to > bytes.size) {
            throw CorruptBlockException("seek out of bounds: $to, size ${bytes.size}")
        }
        position = to
    }

    private fun require(length: Int) {
        if (position + length > bytes.size) {
            throw CorruptBlockException("truncated: need $length at $position, size ${bytes.size}")
        }
    }

    fun readByte(): Int {
        require(1)
        return bytes[position++].toInt()
    }

    fun readInt(): Int {
        require(4)
        val value = ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.BIG_ENDIAN).int
        position += 4
        return value
    }

    fun readLong(): Long {
        require(8)
        val value = ByteBuffer.wrap(bytes, position, 8).order(ByteOrder.BIG_ENDIAN).long
        position += 8
        return value
    }

    fun readDouble(): Double {
        require(8)
        val value = ByteBuffer.wrap(bytes, position, 8).order(ByteOrder.BIG_ENDIAN).double
        position += 8
        return value
    }

    fun readUtf8(): String {
        val length = readInt()
        if (length < 0) throw CorruptBlockException("negative string length: $length")
        require(length)
        val value = String(bytes, position, length, Charsets.UTF_8)
        position += length
        return value
    }

    fun verifyCrc(from: Int, length: Int) {
        val expected = crc32(bytes, from, length)
        val actual = readInt()
        if (expected != actual) {
            throw CorruptBlockException("crc mismatch at $from: expected $expected, got $actual")
        }
    }
}
```

- [ ] **Step 4: Реализовать `ChunkFileCodec`**

`storage/codec/ChunkFileCodec.kt`:

```kotlin
package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.chunks.ChunkEncoding
import org.pirsbd.tsdb.chunks.ChunkRef
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * chunks/000001:
 *   magic:int32, version:int8, padding:3 байта,
 *   затем на чанк: len:int32, encoding:int8, data, crc32:int32
 *
 * len это длина только data. CRC считается по encoding + data.
 * data = count:int32, count × (ts:int64, value:float64)
 */
internal object ChunkFileCodec {
    const val MAGIC: Int = 0x43484E4B
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 8

    fun chunkRef(fileNumber: Int, offset: Long): ChunkRef =
        ChunkRef((fileNumber.toLong() shl 32) or (offset and 0xFFFFFFFFL))

    fun fileNumberOf(ref: ChunkRef): Int = (ref.value ushr 32).toInt()

    fun offsetOf(ref: ChunkRef): Int = (ref.value and 0xFFFFFFFFL).toInt()

    fun write(out: OutputStream, chunks: List<List<Sample>>, fileNumber: Int = 1): List<ChunkRef> {
        val sink = CountingOutput(out)
        sink.writeInt(MAGIC)
        sink.writeByte(VERSION)
        sink.writeBytes(ByteArray(3))

        val refs = ArrayList<ChunkRef>(chunks.size)
        for (samples in chunks) {
            refs.add(chunkRef(fileNumber, sink.position))

            val data = ByteArrayOutputStream()
            val body = CountingOutput(data)
            body.writeInt(samples.size)
            for (sample in samples) {
                body.writeLong(sample.timestamp)
                body.writeDouble(sample.value)
            }
            val payload = data.toByteArray()

            sink.writeInt(payload.size)
            sink.writeByte(ChunkEncoding.RAW.id.toInt())
            sink.writeBytes(payload)

            val crcInput = ByteArray(1 + payload.size)
            crcInput[0] = ChunkEncoding.RAW.id
            payload.copyInto(crcInput, 1)
            sink.writeInt(crc32(crcInput, 0, crcInput.size))
        }
        sink.flush()
        return refs
    }

    fun read(bytes: ByteArray, ref: ChunkRef): List<Sample> {
        val reader = ByteReader(bytes)
        if (reader.readInt() != MAGIC) throw CorruptBlockException("bad chunks magic")
        if (reader.readByte() != VERSION) throw CorruptBlockException("bad chunks version")

        reader.seek(offsetOf(ref))
        val length = reader.readInt()
        if (length < 0) throw CorruptBlockException("negative chunk length: $length")
        val encodingAt = reader.position
        val encoding = reader.readByte().toByte()
        if (encoding != ChunkEncoding.RAW.id) {
            throw CorruptBlockException("unsupported chunk encoding: $encoding")
        }

        val dataAt = reader.position
        val count = reader.readInt()
        if (count < 0) throw CorruptBlockException("negative sample count: $count")
        val samples = ArrayList<Sample>(count)
        repeat(count) { samples.add(Sample(reader.readLong(), reader.readDouble())) }

        if (reader.position - dataAt != length) {
            throw CorruptBlockException("chunk length mismatch at ${offsetOf(ref)}")
        }
        reader.verifyCrc(encodingAt, 1 + length)
        return samples
    }
}
```

- [ ] **Step 5: Прогнать тесты**

Run: `./gradlew test --tests '*ChunkFileCodecTest*' --console=plain`
Expected: PASS, 5 тестов.

- [ ] **Step 6: Коммит**

```bash
git add -A
git commit -m "[lab-2]: add byte io primitives and the chunks file codec

Fixed-width big-endian encoding, no varint and no delta coding, so the
structure from docs/structure-and-usecases.md is kept while the encoder
stays replaceable. Every section carries a CRC32 and a bad one raises
CorruptBlockException instead of returning silent garbage."
```

---

## Task 4: Кодек индекса

**Files:**
- Create: `src/main/kotlin/org/pirsbd/tsdb/storage/codec/IndexFileCodec.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/storage/codec/IndexFileCodecTest.kt`

**Interfaces:**
- Consumes: `ByteIo` из задачи 3, `LabelsOrder` из задачи 2.
- Produces:
  - `internal data class IndexSeries(val labels: Labels, val chunks: List<SeriesChunkMeta>)`
  - `internal object IndexFileCodec` с `write(out: OutputStream, series: List<IndexSeries>)` и `read(bytes: ByteArray): IndexContents`
  - `internal class IndexContents` с `fun all(): List<IndexSeries>`, `fun lookup(matchers: List<Matcher>): List<Int>`, `fun seriesAt(ordinal: Int): IndexSeries`
  - Константа `ALL_POSTINGS_LABEL = Label("", "")`
  - `TOC_SIZE = 36`

- [ ] **Step 1: Написать шесть падающих тестов**

`src/test/kotlin/org/pirsbd/tsdb/storage/codec/IndexFileCodecTest.kt`:

```kotlin
package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.TestLabels.of
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.LabelsOrder
import org.pirsbd.tsdb.chunks.ChunkRef
import org.pirsbd.tsdb.index.SeriesChunkMeta
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IndexFileCodecTest {

    private fun chunk(from: Long, to: Long) =
        listOf(SeriesChunkMeta(ChunkRef(0x100000008L), TimeRange(from, to)))

    private fun encode(series: List<IndexSeries>): ByteArray {
        val out = ByteArrayOutputStream()
        IndexFileCodec.write(out, series.sortedWith { a, b -> LabelsOrder.compare(a.labels, b.labels) })
        return out.toByteArray()
    }

    @Test
    fun `series and their chunks round trip`() {
        val series = listOf(
            IndexSeries(metric("cpu", "host" to "a"), chunk(10, 20)),
            IndexSeries(metric("mem"), chunk(30, 40)),
        )

        val back = IndexFileCodec.read(encode(series)).all()

        assertEquals(series, back)
    }

    @Test
    fun `symbols are interned once and reused`() {
        val series = (1..50).map { IndexSeries(metric("cpu", "host" to "h$it"), chunk(10, 20)) }

        val contents = IndexFileCodec.read(encode(series))

        assertEquals(50, contents.all().size)
        // __name__, cpu, host, h1..h50 = 53 уникальных символа
        assertEquals(53, contents.symbolCount)
    }

    @Test
    fun `postings intersect on two matchers`() {
        val series = listOf(
            IndexSeries(metric("cpu", "host" to "a"), chunk(10, 20)),
            IndexSeries(metric("cpu", "host" to "b"), chunk(10, 20)),
            IndexSeries(metric("mem", "host" to "a"), chunk(10, 20)),
        )
        val contents = IndexFileCodec.read(encode(series))

        val found = contents.lookup(
            listOf(Matcher.Equals("__name__", "cpu"), Matcher.Equals("host", "a")),
        )

        assertEquals(listOf(metric("cpu", "host" to "a")), found.map { contents.seriesAt(it).labels })
    }

    @Test
    fun `empty matchers select every series through all postings`() {
        val series = listOf(
            IndexSeries(metric("cpu"), chunk(10, 20)),
            IndexSeries(metric("mem"), chunk(10, 20)),
        )
        val contents = IndexFileCodec.read(encode(series))

        assertEquals(2, contents.lookup(emptyList()).size)
    }

    @Test
    fun `a matcher nobody has selects nothing`() {
        val contents = IndexFileCodec.read(encode(listOf(IndexSeries(metric("cpu"), chunk(10, 20)))))

        assertEquals(emptyList(), contents.lookup(listOf(Matcher.Equals("host", "nope"))))
    }

    @Test
    fun `a duplicated label name stays deterministic`() {
        // Labels.of дедупликации не делает, порядок задаёт Label.compareTo по (name, value)
        val labels = of("__name__" to "cpu", "host" to "b", "host" to "a")
        val contents = IndexFileCodec.read(encode(listOf(IndexSeries(labels, chunk(10, 20)))))

        val back = contents.all()[0].labels

        assertEquals(labels.toList(), back.toList())
        assertEquals(listOf("a", "b"), back.filter { it.name == "host" }.map { it.value })
    }

    @Test
    fun `a file shorter than the toc is rejected`() {
        val bytes = encode(listOf(IndexSeries(metric("cpu"), chunk(10, 20))))

        assertFailsWith<CorruptBlockException> { IndexFileCodec.read(bytes.copyOf(20)) }
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падают**

Run: `./gradlew test --tests '*IndexFileCodecTest*' --console=plain`
Expected: FAIL, нет `IndexFileCodec`, `IndexSeries`.

- [ ] **Step 3: Реализовать кодек индекса**

`storage/codec/IndexFileCodec.kt`:

```kotlin
package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.chunks.ChunkRef
import org.pirsbd.tsdb.index.SeriesChunkMeta
import java.io.ByteArrayOutputStream
import java.io.OutputStream

internal data class IndexSeries(val labels: Labels, val chunks: List<SeriesChunkMeta>)

/**
 * index:
 *   magic:int32, version:int8,
 *   Symbol Table, Series, Postings 1..N, Postings Offset Table,
 *   TOC (36 байт, в самом конце файла)
 *
 * Запись с парой ("", "") в Postings Offset Table это список всех серий,
 * он же allPostings. Без него пустой список матчеров пришлось бы обслуживать
 * полным сканом секции Series.
 */
internal object IndexFileCodec {
    const val MAGIC: Int = 0xBAAAD700.toInt()
    const val VERSION: Int = 1
    const val TOC_SIZE: Int = 36

    val ALL_POSTINGS_LABEL = Label("", "")

    fun write(out: OutputStream, series: List<IndexSeries>) {
        val sink = CountingOutput(out)
        sink.writeInt(MAGIC)
        sink.writeByte(VERSION)

        val symbols = LinkedHashMap<String, Int>()
        fun symbolId(value: String): Int = symbols.getOrPut(value) { symbols.size }
        for (entry in series) {
            for (label in entry.labels) {
                symbolId(label.name)
                symbolId(label.value)
            }
        }

        val symbolsAt = sink.position
        writeSection(sink) { body ->
            body.writeInt(symbols.size)
            for (symbol in symbols.keys) body.writeUtf8(symbol)
        }

        val seriesAt = sink.position
        val seriesOffsets = ArrayList<Long>(series.size)
        for (entry in series) {
            seriesOffsets.add(sink.position)
            writeSection(sink) { body ->
                body.writeInt(entry.labels.size)
                for (label in entry.labels) {
                    body.writeInt(symbols.getValue(label.name))
                    body.writeInt(symbols.getValue(label.value))
                }
                body.writeInt(entry.chunks.size)
                for (meta in entry.chunks) {
                    body.writeLong(meta.range.minTime)
                    body.writeLong(meta.range.maxTime)
                    body.writeLong(meta.ref.value)
                }
            }
        }

        val postings = LinkedHashMap<Label, MutableList<Long>>()
        postings[ALL_POSTINGS_LABEL] = ArrayList(seriesOffsets)
        for ((ordinal, entry) in series.withIndex()) {
            for (label in entry.labels) {
                postings.getOrPut(label) { ArrayList() }.add(seriesOffsets[ordinal])
            }
        }

        val postingsAt = sink.position
        val postingsOffsets = LinkedHashMap<Label, Long>()
        for ((label, refs) in postings) {
            postingsOffsets[label] = sink.position
            writeSection(sink) { body ->
                body.writeInt(refs.size)
                for (ref in refs.sorted()) body.writeLong(ref)
            }
        }

        val offsetTableAt = sink.position
        writeSection(sink) { body ->
            body.writeInt(postingsOffsets.size)
            for ((label, offset) in postingsOffsets) {
                body.writeUtf8(label.name)
                body.writeUtf8(label.value)
                body.writeLong(offset)
            }
        }

        val toc = ByteArrayOutputStream()
        val tocBody = CountingOutput(toc)
        tocBody.writeLong(symbolsAt)
        tocBody.writeLong(seriesAt)
        tocBody.writeLong(postingsAt)
        tocBody.writeLong(offsetTableAt)
        val tocBytes = toc.toByteArray()
        sink.writeBytes(tocBytes)
        sink.writeInt(crc32(tocBytes, 0, tocBytes.size))
        sink.flush()
    }

    fun read(bytes: ByteArray): IndexContents {
        if (bytes.size < TOC_SIZE) {
            throw CorruptBlockException("index shorter than the toc: ${bytes.size} < $TOC_SIZE")
        }
        val reader = ByteReader(bytes)
        if (reader.readInt() != MAGIC) throw CorruptBlockException("bad index magic")
        if (reader.readByte() != VERSION) throw CorruptBlockException("bad index version")

        reader.seek(bytes.size - TOC_SIZE)
        val tocAt = reader.position
        val symbolsAt = reader.readLong()
        val seriesAt = reader.readLong()
        reader.readLong() // postings start, нужен только для отладки
        val offsetTableAt = reader.readLong()
        reader.verifyCrc(tocAt, TOC_SIZE - 4)

        reader.seek(symbolsAt.toInt())
        val symbols = readSection(reader) { body ->
            val count = body.readInt()
            if (count < 0) throw CorruptBlockException("negative symbol count: $count")
            ArrayList<String>(count).also { list -> repeat(count) { list.add(body.readUtf8()) } }
        }

        reader.seek(offsetTableAt.toInt())
        val offsetTable = readSection(reader) { body ->
            val count = body.readInt()
            val map = LinkedHashMap<Label, Long>(count)
            repeat(count) {
                val name = body.readUtf8()
                val value = body.readUtf8()
                map[Label(name, value)] = body.readLong()
            }
            map
        }

        return IndexContents(bytes, symbols, offsetTable, seriesAt)
    }

    private inline fun writeSection(sink: CountingOutput, body: (CountingOutput) -> Unit) {
        val buffer = ByteArrayOutputStream()
        body(CountingOutput(buffer))
        val payload = buffer.toByteArray()
        sink.writeInt(payload.size)
        sink.writeBytes(payload)
        sink.writeInt(crc32(payload, 0, payload.size))
    }

    private inline fun <T> readSection(reader: ByteReader, body: (ByteReader) -> T): T {
        val length = reader.readInt()
        if (length < 0) throw CorruptBlockException("negative section length: $length")
        val at = reader.position
        val value = body(reader)
        if (reader.position - at != length) {
            throw CorruptBlockException("section length mismatch at $at")
        }
        reader.verifyCrc(at, length)
        return value
    }
}

/** Разобранный индекс: символы и таблица оффсетов в памяти, серии читаются по требованию. */
internal class IndexContents(
    private val bytes: ByteArray,
    private val symbols: List<String>,
    private val postingsOffsets: Map<Label, Long>,
    private val seriesAt: Long,
) {
    val symbolCount: Int get() = symbols.size

    /** Оффсеты серий, отсортированные по возрастанию = в порядке Labels. */
    fun lookup(matchers: List<Matcher>): List<Int> {
        if (matchers.isEmpty()) {
            return postingsAt(IndexFileCodec.ALL_POSTINGS_LABEL)
        }
        val lists = ArrayList<List<Int>>(matchers.size)
        for (matcher in matchers) {
            val equals = matcher as Matcher.Equals
            val list = postingsOffsets[Label(equals.name, equals.value)]
                ?.let { postingsAtOffset(it) }
                ?: return emptyList()
            lists.add(list)
        }
        val shortest = lists.minBy { it.size }
        return shortest.filter { candidate ->
            lists.all { it === shortest || it.binarySearch(candidate) >= 0 }
        }
    }

    fun all(): List<IndexSeries> = lookup(emptyList()).map { seriesAt(it) }

    fun seriesAt(ordinal: Int): IndexSeries {
        val reader = ByteReader(bytes)
        reader.seek(ordinal)
        val length = reader.readInt()
        if (length < 0) throw CorruptBlockException("negative series length: $length")
        val at = reader.position

        val labelCount = reader.readInt()
        val labels = ArrayList<Label>(labelCount)
        repeat(labelCount) {
            val name = symbolAt(reader.readInt())
            val value = symbolAt(reader.readInt())
            labels.add(Label(name, value))
        }

        val chunkCount = reader.readInt()
        val chunks = ArrayList<SeriesChunkMeta>(chunkCount)
        repeat(chunkCount) {
            val minTime = reader.readLong()
            val maxTime = reader.readLong()
            chunks.add(SeriesChunkMeta(ChunkRef(reader.readLong()), TimeRange(minTime, maxTime)))
        }

        if (reader.position - at != length) {
            throw CorruptBlockException("series length mismatch at $ordinal")
        }
        reader.verifyCrc(at, length)
        return IndexSeries(Labels(labels), chunks)
    }

    private fun symbolAt(id: Int): String {
        if (id !in symbols.indices) throw CorruptBlockException("symbol id out of range: $id")
        return symbols[id]
    }

    private fun postingsAt(label: Label): List<Int> =
        postingsOffsets[label]?.let { postingsAtOffset(it) } ?: emptyList()

    private fun postingsAtOffset(offset: Long): List<Int> {
        val reader = ByteReader(bytes)
        reader.seek(offset.toInt())
        val length = reader.readInt()
        val at = reader.position
        val count = reader.readInt()
        if (count < 0) throw CorruptBlockException("negative postings count: $count")
        val refs = ArrayList<Int>(count)
        repeat(count) { refs.add(reader.readLong().toInt()) }
        if (reader.position - at != length) {
            throw CorruptBlockException("postings length mismatch at $offset")
        }
        reader.verifyCrc(at, length)
        return refs
    }
}
```

`Labels(labels)` вызывается напрямую, без `sorted()`: серии пишутся уже отсортированными, и повторная сортировка затёрла бы порядок дублей имён из теста 6.

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew test --tests '*IndexFileCodecTest*' --console=plain`
Expected: PASS, 7 тестов.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "[lab-2]: add the index file codec

Symbol table, series entries, postings, postings offset table and a
36-byte TOC at the end of the file, because offsets are only known once
the sections are written. A reserved ('', '') entry holds all postings,
so an empty matcher list does not need a full scan of the series section."
```

---

## Task 5: Кодек tombstones и meta.json

**Files:**
- Create: `src/main/kotlin/org/pirsbd/tsdb/storage/codec/TombstoneFileCodec.kt`
- Create: `src/main/kotlin/org/pirsbd/tsdb/storage/codec/BlockMetaJson.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/storage/codec/TombstoneFileCodecTest.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/storage/codec/BlockMetaJsonTest.kt`

**Interfaces:**
- Consumes: `ByteIo` из задачи 3, `BlockMeta`, `BlockStats`, `Compaction`.
- Produces:
  - `internal data class Tombstone(val seriesOffset: Int, val range: TimeRange)`
  - `internal object TombstoneFileCodec` с `write(out: OutputStream, tombstones: List<Tombstone>)` и `read(bytes: ByteArray): List<Tombstone>`
  - `internal object BlockMetaJson` с `encode(meta: BlockMeta): String` и `decode(text: String): BlockMeta`

- [ ] **Step 1: Написать падающие тесты**

`src/test/kotlin/org/pirsbd/tsdb/storage/codec/TombstoneFileCodecTest.kt`:

```kotlin
package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.TimeRange
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TombstoneFileCodecTest {

    private fun roundTrip(tombstones: List<Tombstone>): List<Tombstone> {
        val out = ByteArrayOutputStream()
        TombstoneFileCodec.write(out, tombstones)
        return TombstoneFileCodec.read(out.toByteArray())
    }

    @Test
    fun `tombstones round trip`() {
        val tombstones = listOf(
            Tombstone(8, TimeRange(10, 20)),
            Tombstone(8, TimeRange(40, 50)),
            Tombstone(64, TimeRange(0, 5)),
        )

        assertEquals(tombstones, roundTrip(tombstones))
    }

    @Test
    fun `an empty tombstone file round trips`() {
        assertEquals(emptyList(), roundTrip(emptyList()))
    }

    @Test
    fun `a flipped byte is caught by the crc`() {
        val out = ByteArrayOutputStream()
        TombstoneFileCodec.write(out, listOf(Tombstone(8, TimeRange(10, 20))))
        val bytes = out.toByteArray()
        bytes[bytes.size - 6] = (bytes[bytes.size - 6] + 1).toByte()

        assertFailsWith<CorruptBlockException> { TombstoneFileCodec.read(bytes) }
    }
}
```

`src/test/kotlin/org/pirsbd/tsdb/storage/codec/BlockMetaJsonTest.kt`:

```kotlin
package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.storage.BlockMeta
import org.pirsbd.tsdb.storage.BlockStats
import org.pirsbd.tsdb.storage.Compaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockMetaJsonTest {

    private val meta = BlockMeta(
        ulid = "01EM6Q6A1YPX4G9TEB20J22B2R",
        range = TimeRange(1602237600000, 1602244800000),
        stats = BlockStats(numSamples = 553, numSeries = 13, numChunks = 44),
        compaction = Compaction(level = 1, sources = emptyList()),
        version = 1,
    )

    @Test
    fun `meta round trips`() {
        assertEquals(meta, BlockMetaJson.decode(BlockMetaJson.encode(meta)))
    }

    @Test
    fun `meta json is human readable for the defense`() {
        val text = BlockMetaJson.encode(meta)

        assertTrue(text.contains("\n"), "should be pretty printed")
        assertTrue(text.contains("\"minTime\""))
        assertTrue(text.contains("\"numSamples\""))
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падают**

Run: `./gradlew test --tests '*TombstoneFileCodecTest*' --tests '*BlockMetaJsonTest*' --console=plain`
Expected: FAIL, нет `TombstoneFileCodec`, `Tombstone`, `BlockMetaJson`.

- [ ] **Step 3: Реализовать кодек tombstones**

`storage/codec/TombstoneFileCodec.kt`:

```kotlin
package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.TimeRange
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Маркер удаления: для серии по оффсету в index диапазон считается удалённым. */
internal data class Tombstone(val seriesOffset: Int, val range: TimeRange)

/**
 * tombstones:
 *   magic:int32, version:int8, count:int32,
 *   count × (seriesRef:int64, mint:int64, maxt:int64), crc32:int32
 *
 * Единственный мутабельный файл блока: delete по уже сброшенным данным
 * перезаписывает его целиком через .tmp и rename.
 */
internal object TombstoneFileCodec {
    const val MAGIC: Int = 0x0130BA30
    const val VERSION: Int = 1

    fun write(out: OutputStream, tombstones: List<Tombstone>) {
        val sink = CountingOutput(out)
        sink.writeInt(MAGIC)
        sink.writeByte(VERSION)

        val buffer = ByteArrayOutputStream()
        val body = CountingOutput(buffer)
        body.writeInt(tombstones.size)
        for (tombstone in tombstones) {
            body.writeLong(tombstone.seriesOffset.toLong())
            body.writeLong(tombstone.range.minTime)
            body.writeLong(tombstone.range.maxTime)
        }
        val payload = buffer.toByteArray()

        sink.writeBytes(payload)
        sink.writeInt(crc32(payload, 0, payload.size))
        sink.flush()
    }

    fun read(bytes: ByteArray): List<Tombstone> {
        val reader = ByteReader(bytes)
        if (reader.readInt() != MAGIC) throw CorruptBlockException("bad tombstones magic")
        if (reader.readByte() != VERSION) throw CorruptBlockException("bad tombstones version")

        val at = reader.position
        val count = reader.readInt()
        if (count < 0) throw CorruptBlockException("negative tombstone count: $count")
        val tombstones = ArrayList<Tombstone>(count)
        repeat(count) {
            val seriesOffset = reader.readLong().toInt()
            val minTime = reader.readLong()
            val maxTime = reader.readLong()
            tombstones.add(Tombstone(seriesOffset, TimeRange(minTime, maxTime)))
        }
        reader.verifyCrc(at, reader.position - at)
        return tombstones
    }
}
```

- [ ] **Step 4: Реализовать `meta.json`**

`storage/codec/BlockMetaJson.kt`:

```kotlin
package org.pirsbd.tsdb.storage.codec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.storage.BlockMeta
import org.pirsbd.tsdb.storage.BlockStats
import org.pirsbd.tsdb.storage.Compaction

/**
 * meta.json по образцу из docs/structure-and-usecases.md.
 * Pretty print не для красоты: на защите файл открывается глазами.
 */
internal object BlockMetaJson {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    private data class StatsDto(val numSamples: Long, val numSeries: Long, val numChunks: Long)

    @Serializable
    private data class CompactionDto(val level: Int, val sources: List<String> = emptyList())

    @Serializable
    private data class MetaDto(
        val ulid: String,
        val minTime: Long,
        val maxTime: Long,
        val stats: StatsDto,
        val compaction: CompactionDto,
        val version: Int,
    )

    fun encode(meta: BlockMeta): String = json.encodeToString(
        MetaDto(
            ulid = meta.ulid,
            minTime = meta.range.minTime,
            maxTime = meta.range.maxTime,
            stats = StatsDto(meta.stats.numSamples, meta.stats.numSeries, meta.stats.numChunks),
            compaction = CompactionDto(meta.compaction.level, meta.compaction.sources),
            version = meta.version,
        ),
    )

    fun decode(text: String): BlockMeta {
        val dto = json.decodeFromString<MetaDto>(text)
        return BlockMeta(
            ulid = dto.ulid,
            range = TimeRange(dto.minTime, dto.maxTime),
            stats = BlockStats(dto.stats.numSamples, dto.stats.numSeries, dto.stats.numChunks),
            compaction = Compaction(dto.compaction.level, dto.compaction.sources),
            version = dto.version,
        )
    }
}
```

- [ ] **Step 5: Прогнать тесты**

Run: `./gradlew test --tests '*TombstoneFileCodecTest*' --tests '*BlockMetaJsonTest*' --console=plain`
Expected: PASS, 5 тестов.

- [ ] **Step 6: Коммит**

```bash
git add -A
git commit -m "[lab-2]: add the tombstones codec and meta.json

Tombstones are the only mutable file in a block, so the codec rewrites
the whole file. meta.json goes through kotlinx.serialization and is
pretty printed because it gets read by eye during the defense."
```

---

## Task 6: Запись блока и `DiskBlock`

**Files:**
- Create: `src/main/kotlin/org/pirsbd/tsdb/head/HeadSnapshot.kt`
- Create: `src/main/kotlin/org/pirsbd/tsdb/storage/FileBlockWriter.kt`
- Create: `src/main/kotlin/org/pirsbd/tsdb/storage/DiskBlock.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/storage/FixedSnapshotFor.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/storage/DiskBlockTest.kt`

**Interfaces:**
- Consumes: все кодеки из задач 3-5, `SeriesSource` из задачи 2, `LabelsOrder`.
- Produces:
  - `interface HeadSnapshot { val range: TimeRange; val numSeries: Int; val numSamples: Long; fun series(): List<SnapshotSeries>; fun tombstones(): List<SnapshotTombstone> }`
  - `data class SnapshotSeries(val labels: Labels, val samples: List<Sample>)`
  - `data class SnapshotTombstone(val labels: Labels, val range: TimeRange)`
  - `internal class FileBlockWriter(dir: Path)` с `fun write(snapshot: HeadSnapshot): DiskBlock`
  - `internal class DiskBlock(dir: Path) : SeriesSource` с `val meta: BlockMeta`, `fun addTombstones(new: List<Tombstone>)`, `fun offsetsOf(matchers: List<Matcher>): List<Int>`

`DiskBlock` сознательно **не** реализует интерфейс `Block` из лабы 1. `Block` требует `index(): IndexReader`, `chunks(): ChunkReader` и `tombstones(): TombstoneReader`, то есть три отдельных ридера. Запросу нужен один метод `select`, а ридеры были бы пустой прослойкой. `Block`, `IndexReader`, `ChunkReader`, `TombstoneReader` остаются в коде неиспользованными до лабы 3, где пригодятся для компакции. Это отклонение от лабы 1, его надо назвать на защите.

- [ ] **Step 1: Написать шесть падающих тестов**

`src/test/kotlin/org/pirsbd/tsdb/storage/DiskBlockTest.kt`:

```kotlin
package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.HeadSnapshot
import org.pirsbd.tsdb.head.SnapshotSeries
import org.pirsbd.tsdb.head.SnapshotTombstone
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ALL = TimeRange(0, 1_000)

class DiskBlockTest {

    private fun tempDir(): Path = Files.createTempDirectory("tsdb-block")

    private fun snapshot() = FixedSnapshotFor(
        listOf(
            SnapshotSeries(metric("cpu", "host" to "a"), listOf(Sample(10, 1.0), Sample(20, 2.0))),
            SnapshotSeries(metric("cpu", "host" to "b"), listOf(Sample(10, 3.0))),
            SnapshotSeries(metric("mem"), listOf(Sample(30, 4.0))),
        ),
    )

    @Test
    fun `a written block reads back through select`() {
        val dir = tempDir()
        FileBlockWriter(dir).write(snapshot()).use { block ->
            val got = drain(block.select(ALL, emptyList()))

            assertEquals(3, got.size)
            assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0)), got[0].second)
        }
    }

    @Test
    fun `series come out ordered by labels`() {
        val dir = tempDir()
        FileBlockWriter(dir).write(snapshot()).use { block ->
            val got = drain(block.select(ALL, emptyList()))

            assertEquals(
                listOf(metric("cpu", "host" to "a"), metric("cpu", "host" to "b"), metric("mem")),
                got.map { it.first },
            )
        }
    }

    @Test
    fun `matchers filter through postings`() {
        val dir = tempDir()
        FileBlockWriter(dir).write(snapshot()).use { block ->
            val got = drain(block.select(ALL, listOf(Matcher.Equals("host", "b"))))

            assertEquals(listOf(metric("cpu", "host" to "b")), got.map { it.first })
        }
    }

    @Test
    fun `the time range filters samples`() {
        val dir = tempDir()
        FileBlockWriter(dir).write(snapshot()).use { block ->
            val got = drain(block.select(TimeRange(15, 25), emptyList()))

            assertEquals(1, got.size)
            assertEquals(listOf(Sample(20, 2.0)), got[0].second)
        }
    }

    @Test
    fun `the block lands in its own folder with the four expected files`() {
        val dir = tempDir()
        val block = FileBlockWriter(dir).write(snapshot())

        val folder = dir.listDirectoryEntries().single()
        assertTrue(folder.resolve("meta.json").exists())
        assertTrue(folder.resolve("index").exists())
        assertTrue(folder.resolve("chunks/000001").exists())
        assertTrue(folder.resolve("tombstones").exists())
        assertEquals(block.meta.ulid, folder.fileName.toString())
        block.close()
    }

    @Test
    fun `no tmp folder is left behind`() {
        val dir = tempDir()
        FileBlockWriter(dir).write(snapshot()).close()

        assertEquals(emptyList(), dir.listDirectoryEntries().filter { it.fileName.toString().endsWith(".tmp") })
    }

    @Test
    fun `meta carries the range and the stats`() {
        val dir = tempDir()
        FileBlockWriter(dir).write(snapshot()).use { block ->
            assertEquals(TimeRange(10, 30), block.meta.range)
            assertEquals(3, block.meta.stats.numSeries)
            assertEquals(4, block.meta.stats.numSamples)
        }
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падают**

Run: `./gradlew test --tests '*DiskBlockTest*' --console=plain`
Expected: FAIL, нет `FileBlockWriter`, `DiskBlock`, `HeadSnapshot`, `SnapshotSeries`, `SnapshotTombstone`.

- [ ] **Step 3: Создать `HeadSnapshot`**

`head/HeadSnapshot.kt`:

```kotlin
package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange

/** Серия снимка. Сэмплы отсортированы по таймстемпу, дублей нет. */
data class SnapshotSeries(val labels: Labels, val samples: List<Sample>)

/** Маркер удаления в терминах Labels: оффсеты серий знает только писатель блока. */
data class SnapshotTombstone(val labels: Labels, val range: TimeRange)

/**
 * Снимок отрезанной части Head. Head отдаёт данные, а писать их в блок
 * это дело BlockWriter, поэтому Head не знает про диск.
 */
interface HeadSnapshot {
    val range: TimeRange
    val numSeries: Int
    val numSamples: Long

    /** Серии в порядке Labels. */
    fun series(): List<SnapshotSeries>

    fun tombstones(): List<SnapshotTombstone>
}
```

- [ ] **Step 4: Реализовать `FileBlockWriter`**

`storage/FileBlockWriter.kt`:

```kotlin
package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.LabelsOrder
import org.pirsbd.tsdb.head.HeadSnapshot
import org.pirsbd.tsdb.index.SeriesChunkMeta
import org.pirsbd.tsdb.storage.codec.ChunkFileCodec
import org.pirsbd.tsdb.storage.codec.IndexFileCodec
import org.pirsbd.tsdb.storage.codec.IndexSeries
import org.pirsbd.tsdb.storage.codec.Tombstone
import org.pirsbd.tsdb.storage.codec.TombstoneFileCodec
import org.pirsbd.tsdb.storage.codec.BlockMetaJson
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Пишет блок в <ulid>.tmp, делает fsync, затем атомарно переименовывает
 * в <ulid>. Если процесс упал в середине, на диске остаётся .tmp, его
 * вычищает FileStorage при открытии.
 */
internal class FileBlockWriter(private val dir: Path) {

    fun write(snapshot: HeadSnapshot): DiskBlock {
        val ulid = newUlid()
        val staging = dir.resolve("$ulid.tmp")
        Files.createDirectories(staging.resolve("chunks"))

        val series = snapshot.series().sortedWith { a, b -> LabelsOrder.compare(a.labels, b.labels) }

        val chunkBytes = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(chunkBytes, series.map { it.samples })
        writeAndSync(staging.resolve("chunks/000001"), chunkBytes.toByteArray())

        val indexBytes = ByteArrayOutputStream()
        IndexFileCodec.write(
            indexBytes,
            series.mapIndexed { i, entry ->
                IndexSeries(
                    labels = entry.labels,
                    chunks = listOf(
                        SeriesChunkMeta(
                            ref = refs[i],
                            range = TimeRange(entry.samples.first().timestamp, entry.samples.last().timestamp),
                        ),
                    ),
                )
            },
        )
        val indexContent = indexBytes.toByteArray()
        writeAndSync(staging.resolve("index"), indexContent)

        val offsets = IndexFileCodec.read(indexContent).let { contents ->
            contents.lookup(emptyList()).associateBy { contents.seriesAt(it).labels }
        }
        val tombstoneBytes = ByteArrayOutputStream()
        TombstoneFileCodec.write(
            tombstoneBytes,
            snapshot.tombstones().mapNotNull { t ->
                offsets[t.labels]?.let { Tombstone(it, t.range) }
            },
        )
        writeAndSync(staging.resolve("tombstones"), tombstoneBytes.toByteArray())

        val meta = BlockMeta(
            ulid = ulid,
            range = snapshot.range,
            stats = BlockStats(
                numSamples = snapshot.numSamples,
                numSeries = snapshot.numSeries.toLong(),
                numChunks = refs.size.toLong(),
            ),
            compaction = Compaction(level = 1, sources = emptyList()),
            version = 1,
        )
        writeAndSync(staging.resolve("meta.json"), BlockMetaJson.encode(meta).toByteArray())

        val target = dir.resolve(ulid)
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        return DiskBlock(target)
    }

    private fun writeAndSync(path: Path, content: ByteArray) {
        Files.createDirectories(path.parent)
        Files.newOutputStream(path).use { out ->
            out.write(content)
            out.flush()
        }
        java.io.FileOutputStream(path.toFile(), true).use { it.fd.sync() }
    }

    private companion object {
        private val random = SecureRandom()
        private val counter = AtomicLong(0)
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /**
         * ULID-подобный id: время в старшей части, поэтому папки блоков
         * сортируются лексикографически = хронологически.
         */
        fun newUlid(): String {
            val timestamp = System.currentTimeMillis()
            val sb = StringBuilder(26)
            for (i in 9 downTo 0) {
                sb.append(ALPHABET[((timestamp shr (i * 5)) and 0x1F).toInt()])
            }
            val entropy = counter.getAndIncrement() xor random.nextLong()
            for (i in 15 downTo 0) {
                sb.append(ALPHABET[((entropy shr (i * 2)) and 0x1F).toInt()])
            }
            return sb.toString()
        }
    }
}
```

- [ ] **Step 5: Реализовать `DiskBlock`**

`storage/DiskBlock.kt`:

```kotlin
package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.LabelsOrder
import org.pirsbd.tsdb.api.impl.SortedMapSeriesSet
import org.pirsbd.tsdb.storage.codec.BlockMetaJson
import org.pirsbd.tsdb.storage.codec.ChunkFileCodec
import org.pirsbd.tsdb.storage.codec.IndexContents
import org.pirsbd.tsdb.storage.codec.IndexFileCodec
import org.pirsbd.tsdb.storage.codec.Tombstone
import org.pirsbd.tsdb.storage.codec.TombstoneFileCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.TreeMap

/** Блок на диске. Все файлы иммутабельны, кроме tombstones. */
internal class DiskBlock(private val dir: Path) : SeriesSource {

    val meta: BlockMeta = BlockMetaJson.decode(Files.readString(dir.resolve("meta.json")))

    private val indexBytes: ByteArray = Files.readAllBytes(dir.resolve("index"))
    private val chunkBytes: ByteArray = Files.readAllBytes(dir.resolve("chunks/000001"))
    private val contents: IndexContents = IndexFileCodec.read(indexBytes)

    @Volatile
    private var tombstones: List<Tombstone> =
        TombstoneFileCodec.read(Files.readAllBytes(dir.resolve("tombstones")))

    override val range: TimeRange get() = meta.range

    override fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet {
        val deleted = tombstones.groupBy({ it.seriesOffset }, { it.range })
        val result = TreeMap<Labels, List<Sample>>(LabelsOrder.COMPARATOR)

        for (offset in contents.lookup(matchers)) {
            val series = contents.seriesAt(offset)
            val samples = ArrayList<Sample>()
            for (chunk in series.chunks) {
                if (!chunk.range.overlaps(range)) continue
                for (sample in ChunkFileCodec.read(chunkBytes, chunk.ref)) {
                    if (!range.contains(sample.timestamp)) continue
                    if (deleted[offset]?.any { it.contains(sample.timestamp) } == true) continue
                    samples.add(sample)
                }
            }
            if (samples.isNotEmpty()) result[series.labels] = samples
        }
        return SortedMapSeriesSet(result)
    }

    /** Добавляет маркеры удаления. Единственная мутация блока. */
    fun addTombstones(new: List<Tombstone>) {
        if (new.isEmpty()) return
        val merged = tombstones + new
        val staging = dir.resolve("tombstones.tmp")
        Files.newOutputStream(staging).use { TombstoneFileCodec.write(it, merged) }
        java.io.FileOutputStream(staging.toFile(), true).use { it.fd.sync() }
        Files.move(staging, dir.resolve("tombstones"), StandardCopyOption.ATOMIC_MOVE)
        tombstones = merged
    }

    /** Оффсеты серий, подходящих под матчеры. Нужны delete, чтобы собрать tombstones. */
    fun offsetsOf(matchers: List<Matcher>): List<Int> = contents.lookup(matchers)

    override fun close() = Unit
}
```

- [ ] **Step 6: Прогнать тесты**

Run: `./gradlew test --tests '*DiskBlockTest*' --console=plain`
Expected: PASS, 7 тестов.

- [ ] **Step 7: Коммит**

```bash
git add -A
git commit -m "[lab-2]: write blocks to disk and read them back as a SeriesSource

FileBlockWriter stages a block in <ulid>.tmp, fsyncs it and renames it
into place, so a block is either fully there or not there at all.
DiskBlock implements SeriesSource, which is what lets a query treat it
exactly like the in-memory head.

HeadSnapshot carries tombstones as Labels rather than refs, because the
series offsets only exist once the index has been written."
```

---

## Task 7: `FileStorage`

**Files:**
- Create: `src/main/kotlin/org/pirsbd/tsdb/storage/FileStorage.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/storage/FileStorageTest.kt`

**Interfaces:**
- Consumes: `DiskBlock`, `FileBlockWriter` из задачи 6.
- Produces: `internal class FileStorage(dir: Path) : AutoCloseable` с `fun blocks(): List<DiskBlock>`, `fun write(snapshot: HeadSnapshot): DiskBlock`, `fun overlapping(range: TimeRange): List<DiskBlock>`.

- [ ] **Step 1: Написать пять падающих тестов**

`src/test/kotlin/org/pirsbd/tsdb/storage/FileStorageTest.kt`:

```kotlin
package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.SnapshotSeries
import org.pirsbd.tsdb.storage.codec.CorruptBlockException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileStorageTest {

    private fun tempDir(): Path = Files.createTempDirectory("tsdb-storage")

    private fun snapshotAt(from: Long, to: Long) = FixedSnapshotFor(
        listOf(SnapshotSeries(metric("cpu"), listOf(Sample(from, 1.0), Sample(to, 2.0)))),
    )

    @Test
    fun `an empty directory yields no blocks`() {
        FileStorage(tempDir()).use { assertEquals(emptyList(), it.blocks()) }
    }

    @Test
    fun `a written block is visible after reopening the storage`() {
        val dir = tempDir()
        FileStorage(dir).use { it.write(snapshotAt(10, 20)) }

        FileStorage(dir).use { assertEquals(1, it.blocks().size) }
    }

    @Test
    fun `a leftover tmp folder is removed on open`() {
        val dir = tempDir()
        val leftover = dir.resolve("01ABC.tmp").also { it.createDirectories() }
        leftover.resolve("index").writeText("garbage")

        FileStorage(dir).use { assertEquals(emptyList(), it.blocks()) }

        assertFalse(leftover.exists())
    }

    @Test
    fun `blocks come out ordered by time and overlapping filters by range`() {
        val dir = tempDir()
        FileStorage(dir).use { storage ->
            storage.write(snapshotAt(100, 200))
            storage.write(snapshotAt(300, 400))

            assertEquals(2, storage.blocks().size)
            assertEquals(listOf(100L, 300L), storage.blocks().map { it.meta.range.minTime })
            assertEquals(listOf(300L), storage.overlapping(TimeRange(350, 360)).map { it.meta.range.minTime })
            assertEquals(emptyList(), storage.overlapping(TimeRange(250, 260)).map { it.meta.range.minTime })
        }
    }

    @Test
    fun `a block folder with a corrupt index fails loudly on open`() {
        val dir = tempDir()
        FileStorage(dir).use { it.write(snapshotAt(10, 20)) }
        val block = Files.list(dir).use { it.toList() }.single()
        block.resolve("index").writeText("too short")

        assertFailsWith<CorruptBlockException> { FileStorage(dir).use { it.blocks() } }
    }
}
```

Хелпер `FixedSnapshotFor` создан в задаче 6, шаг 3, в `src/test/kotlin/org/pirsbd/tsdb/storage/FixedSnapshotFor.kt`. Содержимое приведено как источник истины:

```kotlin
package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.HeadSnapshot
import org.pirsbd.tsdb.head.SnapshotSeries
import org.pirsbd.tsdb.head.SnapshotTombstone

class FixedSnapshotFor(
    private val series: List<SnapshotSeries>,
    private val tombstones: List<SnapshotTombstone> = emptyList(),
) : HeadSnapshot {
    override val range: TimeRange
        get() {
            val all = series.flatMap { it.samples }
            return TimeRange(all.minOf { it.timestamp }, all.maxOf { it.timestamp })
        }
    override val numSeries: Int get() = series.size
    override val numSamples: Long get() = series.sumOf { it.samples.size.toLong() }
    override fun series(): List<SnapshotSeries> = series
    override fun tombstones(): List<SnapshotTombstone> = tombstones
}
```

- [ ] **Step 2: Прогнать и убедиться, что падают**

Run: `./gradlew test --tests '*FileStorageTest*' --console=plain`
Expected: FAIL, нет `FileStorage`.

- [ ] **Step 3: Реализовать `FileStorage`**

`storage/FileStorage.kt`:

```kotlin
package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.HeadSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Каталог блоков. При открытии вычищает недописанные .tmp и поднимает
 * остальные папки как DiskBlock.
 *
 * Компакции в лабе 2 нет, блоки не удаляются, поэтому открытый querier
 * не может остаться со ссылкой на снесённый блок.
 */
internal class FileStorage(private val dir: Path) : AutoCloseable {

    @Volatile
    private var blocks: List<DiskBlock>

    private val writer = FileBlockWriter(dir)

    init {
        Files.createDirectories(dir)
        cleanStaging()
        blocks = openAll()
    }

    fun blocks(): List<DiskBlock> = blocks

    fun overlapping(range: TimeRange): List<DiskBlock> = blocks.filter { it.range.overlaps(range) }

    fun write(snapshot: HeadSnapshot): DiskBlock {
        val block = writer.write(snapshot)
        blocks = (blocks + block).sortedBy { it.meta.range.minTime }
        return block
    }

    override fun close() {
        blocks.forEach { it.close() }
    }

    private fun cleanStaging() {
        Files.list(dir).use { stream ->
            stream.filter { it.name.endsWith(".tmp") }.forEach { deleteRecursively(it) }
        }
    }

    private fun openAll(): List<DiskBlock> =
        Files.list(dir).use { stream ->
            stream.filter { it.isDirectory() }
                .map { DiskBlock(it) }
                .toList()
                .sortedBy { it.meta.range.minTime }
        }

    private fun deleteRecursively(path: Path) {
        if (path.isDirectory()) {
            Files.list(path).use { stream -> stream.forEach { deleteRecursively(it) } }
        }
        Files.deleteIfExists(path)
    }
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew test --console=plain`
Expected: PASS, все тесты задач 0-7.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "[lab-2]: add FileStorage with startup scan and tmp cleanup

Opening a storage removes leftover .tmp folders from an interrupted
write and then opens every remaining folder as a block, which is what
makes flushed data survive a restart. A corrupt index fails loudly
instead of being skipped silently."
```

---

## Task 8: `DefaultTSDB`, `Appender`, `Querier`, флаш

**Files:**
- Create: `src/main/kotlin/org/pirsbd/tsdb/api/TSDBConfig.kt`
- Create: `src/main/kotlin/org/pirsbd/tsdb/api/impl/HeadAppender.kt`
- Create: `src/main/kotlin/org/pirsbd/tsdb/api/impl/DefaultQuerier.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/api/TSDB.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/api/impl/DefaultTSDB.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/head/MemoryHead.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/head/MemoryChunk.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/TsdbEndToEndTest.kt`
- Test: `src/test/kotlin/org/pirsbd/tsdb/TsdbConcurrencyTest.kt`

**Interfaces:**
- Consumes: `FileStorage` из задачи 7, `MergeSeriesSet` из задачи 2, `HeadSnapshot` из задачи 6.
- Produces:
  - `data class TSDBConfig(val headWindow: Long = 7_200_000, val stripeSize: Int = 16)`
  - `interface TSDB` получает `fun flush()`
  - `class DefaultTSDB(dir: Path, config: TSDBConfig = TSDBConfig()) : TSDB`
  - `MemoryHead.truncate(upTo: Long): HeadSnapshot?`

- [ ] **Step 1: Написать одиннадцать end-to-end тестов и четыре на конкурентность**

`src/test/kotlin/org/pirsbd/tsdb/TsdbEndToEndTest.kt`:

```kotlin
package org.pirsbd.tsdb

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TSDB
import org.pirsbd.tsdb.api.TSDBConfig
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ALL = TimeRange(0, Long.MAX_VALUE - 1)

class TsdbEndToEndTest {

    private fun tempDir(): Path = Files.createTempDirectory("tsdb-e2e")

    private fun TSDB.put(vararg samples: Triple<String, Long, Double>) {
        appender().use { appender ->
            for ((name, timestamp, value) in samples) {
                appender.add(metric(name), timestamp, value)
            }
            appender.commit()
        }
    }

    private fun TSDB.read(vararg matchers: Matcher) =
        querier(ALL).use { drain(it.select(matchers.toList())) }

    @Test
    fun `1 append then query without a flush`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0), Triple("cpu", 20L, 2.0))

            assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0)), db.read()[0].second)
        }
    }

    @Test
    fun `2 append then flush then query`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0))
            db.flush()

            assertEquals(listOf(Sample(10, 1.0)), db.read()[0].second)
        }
    }

    @Test
    fun `3 a query merges the head and a block`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0))
            db.flush()
            db.put(Triple("cpu", 20L, 2.0))

            assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0)), db.read()[0].second)
        }
    }

    @Test
    fun `4 data survives close and reopen`() {
        val dir = tempDir()
        DefaultTSDB(dir).use { it.put(Triple("cpu", 10L, 1.0)) }

        DefaultTSDB(dir).use { db ->
            assertEquals(listOf(Sample(10, 1.0)), db.read()[0].second)
        }
    }

    @Test
    fun `5 two flushes make two blocks and a query covers both`() {
        val dir = tempDir()
        DefaultTSDB(dir).use { db ->
            db.put(Triple("cpu", 10L, 1.0))
            db.flush()
            db.put(Triple("cpu", 20L, 2.0))
            db.flush()

            assertEquals(2, dir.listDirectoryEntries().size)
            assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0)), db.read()[0].second)
        }
    }

    @Test
    fun `6 a narrow range only touches one block`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 100L, 1.0))
            db.flush()
            db.put(Triple("cpu", 300L, 3.0))
            db.flush()

            val got = db.querier(TimeRange(250, 350)).use { drain(it.select(emptyList())) }

            assertEquals(listOf(Sample(300, 3.0)), got[0].second)
        }
    }

    @Test
    fun `7 empty matchers select every series`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0), Triple("mem", 10L, 2.0))

            assertEquals(listOf(metric("cpu"), metric("mem")), db.read().map { it.first })
        }
    }

    @Test
    fun `8 rollback does not apply the batch`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { appender ->
                appender.add(metric("cpu"), 10, 1.0)
                appender.rollback()
            }

            assertEquals(emptyList(), db.read())
        }
    }

    @Test
    fun `9 an appender closed without commit drops the batch`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { it.add(metric("cpu"), 10, 1.0) }

            assertEquals(emptyList(), db.read())
        }
    }

    @Test
    fun `10 an empty database answers a query instead of crashing`() {
        DefaultTSDB(tempDir()).use { db ->
            db.querier(ALL).use { querier ->
                querier.select(emptyList()).use { assertFalse(it.next()) }
            }
            assertEquals(emptyList(), db.read())
        }
    }

    @Test
    fun `11 a sample without __name__ is rejected on add`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { appender ->
                assertFailsWith<IllegalArgumentException> {
                    appender.add(TestLabels.of("host" to "a"), 10, 1.0)
                }
            }
        }
    }

    @Test
    fun `12 the auto flush threshold is driven by sample timestamps`() {
        val dir = tempDir()
        DefaultTSDB(dir, TSDBConfig(headWindow = 1_000)).use { db ->
            db.put(Triple("cpu", 0L, 1.0))
            assertEquals(0, dir.listDirectoryEntries().size)

            db.put(Triple("cpu", 5_000L, 2.0))

            assertTrue(dir.listDirectoryEntries().isNotEmpty(), "crossing headWindow must flush")
            assertEquals(listOf(Sample(0, 1.0), Sample(5_000, 2.0)), db.read()[0].second)
        }
    }

    @Test
    fun `13 a timestamp near Long MAX does not overflow the flush threshold`() {
        DefaultTSDB(tempDir(), TSDBConfig(headWindow = 1_000)).use { db ->
            db.put(Triple("cpu", Long.MAX_VALUE - 10, 1.0))

            assertEquals(1, db.read().size)
        }
    }

    @Test
    fun `14 delete before a flush hides the samples`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0), Triple("cpu", 20L, 2.0), Triple("cpu", 30L, 3.0))

            db.delete(TimeRange(15, 25), listOf(Matcher.Equals("__name__", "cpu")))

            assertEquals(listOf(Sample(10, 1.0), Sample(30, 3.0)), db.read()[0].second)
        }
    }
}
```

`src/test/kotlin/org/pirsbd/tsdb/TsdbConcurrencyTest.kt`:

```kotlin
package org.pirsbd.tsdb

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val ALL = TimeRange(0, Long.MAX_VALUE - 1)

class TsdbConcurrencyTest {

    private val threads = 8
    private val perThread = 200

    @Test
    fun `1 concurrent writers to one series keep every sample`() {
        DefaultTSDB(Files.createTempDirectory("tsdb-conc")).use { db ->
            runParallel { thread ->
                db.appender().use { appender ->
                    for (i in 0 until perThread) {
                        appender.add(metric("cpu"), (thread * perThread + i).toLong(), i.toDouble())
                    }
                    appender.commit()
                }
            }

            val samples = db.querier(ALL).use { drain(it.select(emptyList()))[0].second }
            assertEquals(threads * perThread, samples.size)
            assertEquals(samples.map { it.timestamp }.sorted(), samples.map { it.timestamp })
        }
    }

    @Test
    fun `2 concurrent writers to distinct series keep every series`() {
        DefaultTSDB(Files.createTempDirectory("tsdb-conc")).use { db ->
            runParallel { thread ->
                db.appender().use { appender ->
                    for (i in 0 until perThread) {
                        appender.add(metric("cpu", "thread" to "$thread"), i.toLong(), i.toDouble())
                    }
                    appender.commit()
                }
            }

            assertEquals(threads, db.querier(ALL).use { drain(it.select(emptyList())).size })
        }
    }

    @Test
    fun `3 a reader running next to writers never throws`() {
        DefaultTSDB(Files.createTempDirectory("tsdb-conc")).use { db ->
            val failure = AtomicReference<Throwable?>(null)
            val stop = CountDownLatch(1)
            val reader = Thread {
                try {
                    while (stop.count > 0L) {
                        db.querier(ALL).use { drain(it.select(emptyList())) }
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            reader.start()

            runParallel { thread ->
                db.appender().use { appender ->
                    for (i in 0 until perThread) {
                        appender.add(metric("cpu", "thread" to "$thread"), i.toLong(), i.toDouble())
                    }
                    appender.commit()
                }
            }

            stop.countDown()
            reader.join(5_000)
            assertNull(failure.get())
        }
    }

    @Test
    fun `4 a flush running next to writers loses nothing`() {
        DefaultTSDB(Files.createTempDirectory("tsdb-conc")).use { db ->
            val failure = AtomicReference<Throwable?>(null)
            val stop = CountDownLatch(1)
            val flusher = Thread {
                try {
                    while (stop.count > 0L) db.flush()
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            flusher.start()

            runParallel { thread ->
                db.appender().use { appender ->
                    for (i in 0 until perThread) {
                        appender.add(metric("cpu", "thread" to "$thread"), (thread * perThread + i).toLong(), 1.0)
                    }
                    appender.commit()
                }
            }

            stop.countDown()
            flusher.join(5_000)
            assertNull(failure.get())

            val total = db.querier(ALL).use { drain(it.select(emptyList())).sumOf { s -> s.second.size } }
            assertEquals(threads * perThread, total)
        }
    }

    private fun runParallel(body: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        for (thread in 0 until threads) {
            pool.submit {
                try {
                    body(thread)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        pool.shutdown()
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падают**

Run: `./gradlew test --tests '*TsdbEndToEndTest*' --tests '*TsdbConcurrencyTest*' --console=plain`
Expected: FAIL, нет `TSDBConfig`, `TSDB.flush()`, `DefaultTSDB(Path)`.

- [ ] **Step 3: Добавить конфиг и `flush()` в интерфейс**

`api/TSDBConfig.kt`:

```kotlin
package org.pirsbd.tsdb.api

/**
 * headWindow сравнивается с диапазоном таймстемпов в Head, а не со стенными
 * часами: иначе тест на автофлаш пришлось бы писать через Thread.sleep.
 */
data class TSDBConfig(
    val headWindow: Long = 2 * 60 * 60 * 1000,
    val stripeSize: Int = 16,
) {
    init {
        require(headWindow > 0) { "headWindow must be positive: $headWindow" }
        require(stripeSize > 0) { "stripeSize must be positive: $stripeSize" }
    }
}
```

`api/TSDB.kt`, добавить в интерфейс:

```kotlin
interface TSDB : AutoCloseable {
    fun appender(): Appender
    fun querier(range: TimeRange): Querier
    fun delete(range: TimeRange, matchers: List<Matcher>)

    /**
     * Сбрасывает Head в блок на диске. Публичный, потому что нужен тестам
     * и демо: без него «ручной флаш» из спеки недоступен снаружи.
     */
    fun flush()
}
```

- [ ] **Step 4: Реализовать `HeadAppender`**

`api/impl/HeadAppender.kt`:

```kotlin
package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Appender
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.head.Head

/**
 * Буферизует батч и применяет его одним commit(). Валидация идёт в add(),
 * чтобы ошибка была рядом с вызовом, а не всплыла на commit().
 */
internal class HeadAppender(
    private val head: Head,
    private val afterCommit: () -> Unit,
) : Appender {

    private class Pending(val labels: Labels, val timestamp: Long, val value: Double)

    private val pending = ArrayList<Pending>()
    private var committed = false

    override fun add(labels: Labels, timestamp: Long, value: Double) {
        require(labels.isNotEmpty()) { "labels must not be empty" }
        require(labels.get(Labels.METRIC_NAME) != null) {
            "labels must contain ${Labels.METRIC_NAME}: $labels"
        }
        pending.add(Pending(labels, timestamp, value))
    }

    override fun commit() {
        for (sample in pending) {
            head.append(sample.labels, sample.timestamp, sample.value)
        }
        pending.clear()
        committed = true
        afterCommit()
    }

    override fun rollback() {
        pending.clear()
    }

    /** Без commit() батч отбрасывается: close() это rollback. */
    override fun close() {
        if (!committed) pending.clear()
    }
}
```

- [ ] **Step 5: Реализовать `DefaultQuerier`**

`api/impl/DefaultQuerier.kt`:

```kotlin
package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Querier
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TimeRange

/**
 * Снимок источников на момент открытия. Head передаётся первым, поэтому
 * при равном таймстемпе выигрывает он.
 *
 * Снимок настоящий только для блоков: Head продолжает мутировать под
 * открытым querier. Полный снимок потребовал бы MVCC.
 */
internal class DefaultQuerier(
    override val range: TimeRange,
    private val sources: List<SeriesSource>,
) : Querier {

    override fun select(matchers: List<Matcher>): SeriesSet =
        MergeSeriesSet(sources.map { it.select(range, matchers) })

    /** Источники принадлежат БД, а не querier, поэтому закрывать их нельзя. */
    override fun close() = Unit
}
```

- [ ] **Step 6: Реализовать `MemoryHead.truncate` и `DefaultTSDB`**

`head/MemoryHead.kt`, добавить:

```kotlin
    override fun truncate(upTo: Long): HeadSnapshot? {
        lock.writeLock().withLock {
            val series = chunk.drain(upTo)
            if (series.isEmpty()) return null

            val tombstones = chunk.drainTombstones()
            minTime.store(Long.MAX_VALUE)
            maxTime.store(Long.MIN_VALUE)
            for (entry in series) {
                for (sample in entry.samples) updateRangeLocked(sample.timestamp)
            }
            return MaterializedSnapshot(series, tombstones)
        }
    }
```

`MemoryChunk` получает `drain(upTo: Long): List<SnapshotSeries>` и `drainTombstones(): List<SnapshotTombstone>`: обе собирают сэмплы строго меньше `upTo`, убирают их из серий и возвращают в порядке `Labels`. При `upTo == Long.MAX_VALUE` забирается всё.

`api/impl/DefaultTSDB.kt`:

```kotlin
package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Appender
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Querier
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TSDB
import org.pirsbd.tsdb.api.TSDBConfig
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.MemoryHead
import org.pirsbd.tsdb.storage.FileStorage
import org.pirsbd.tsdb.storage.codec.Tombstone
import java.nio.file.Path

class DefaultTSDB(
    dir: Path,
    private val config: TSDBConfig = TSDBConfig(),
) : TSDB {

    private val head = MemoryHead(config.stripeSize)
    private val storage = FileStorage(dir)

    override fun appender(): Appender = HeadAppender(head) { maybeAutoFlush() }

    override fun querier(range: TimeRange): Querier {
        val sources = ArrayList<SeriesSource>()
        if (head.range.overlaps(range)) sources.add(head)
        sources.addAll(storage.overlapping(range))
        return DefaultQuerier(range, sources)
    }

    override fun delete(range: TimeRange, matchers: List<Matcher>) {
        head.delete(range, matchers)
        for (block in storage.overlapping(range)) {
            block.addTombstones(block.offsetsOf(matchers).map { Tombstone(it, range) })
        }
    }

    override fun flush() {
        val snapshot = head.truncate(Long.MAX_VALUE) ?: return
        storage.write(snapshot)
    }

    /** Флашит Head: без WAL это единственный способ не потерять свежие данные. */
    override fun close() {
        flush()
        head.close()
        storage.close()
    }

    /**
     * Порог считается по таймстемпам сэмплов. Сложение разворачивается
     * через вычитание, иначе minTime близко к Long.MAX_VALUE переполняет сумму.
     */
    private fun maybeAutoFlush() {
        val range = head.range
        if (range == TimeRange.EMPTY) return
        if (range.maxTime - range.minTime <= config.headWindow) return

        val snapshot = head.truncate(range.minTime + config.headWindow) ?: return
        storage.write(snapshot)
    }
}
```

- [ ] **Step 7: Прогнать тесты**

Run: `./gradlew test --console=plain`
Expected: PASS, все тесты задач 0-8.

- [ ] **Step 8: Коммит**

```bash
git add -A
git commit -m "[lab-2]: wire up the public API, the flush path and the querier

DefaultTSDB owns the head and the storage; a querier takes a snapshot of
the sources that overlap its range and hands them to the merge, head
first so it wins on a tie. The auto flush threshold is measured in sample
timestamps rather than wall clock, which keeps the test deterministic
without a sleep, and the comparison avoids overflowing near Long.MAX_VALUE.

close() flushes the head, since without a WAL that is the only way a
graceful shutdown keeps its data."
```

---

## Task 9: `delete` через границу флаша

**Files:**
- Test: `src/test/kotlin/org/pirsbd/tsdb/DeleteAcrossFlushTest.kt`
- Modify: `src/main/kotlin/org/pirsbd/tsdb/storage/DiskBlock.kt` (если тесты найдут расхождение)

**Interfaces:**
- Consumes: `DefaultTSDB` из задачи 8, `DiskBlock.addTombstones` из задачи 6.
- Produces: ничего нового, задача закрепляет поведение.

- [ ] **Step 1: Написать пять падающих тестов**

`src/test/kotlin/org/pirsbd/tsdb/DeleteAcrossFlushTest.kt`:

```kotlin
package org.pirsbd.tsdb

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

private val ALL = TimeRange(0, Long.MAX_VALUE - 1)
private val CPU = listOf(Matcher.Equals("__name__", "cpu"))

class DeleteAcrossFlushTest {

    private fun tempDir(): Path = Files.createTempDirectory("tsdb-delete")

    private fun DefaultTSDB.fill(name: String, timestamps: LongRange) {
        appender().use { appender ->
            for (t in timestamps) appender.add(metric(name), t, t.toDouble())
            appender.commit()
        }
    }

    private fun DefaultTSDB.timestamps() =
        querier(ALL).use { q -> drain(q.select(emptyList())).flatMap { it.second }.map { it.timestamp } }

    @Test
    fun `1 delete after a flush hides samples that already live in a block`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..5L)
            db.flush()

            db.delete(TimeRange(2, 3), CPU)

            assertEquals(listOf(1L, 4L, 5L), db.timestamps())
        }
    }

    @Test
    fun `2 tombstones written to a block survive a reopen`() {
        val dir = tempDir()
        DefaultTSDB(dir).use { db ->
            db.fill("cpu", 1L..5L)
            db.flush()
            db.delete(TimeRange(2, 3), CPU)
        }

        DefaultTSDB(dir).use { db ->
            assertEquals(listOf(1L, 4L, 5L), db.timestamps())
        }
    }

    @Test
    fun `3 delete spanning the head and a block hides both halves`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..3L)
            db.flush()
            db.fill("cpu", 4L..6L)

            db.delete(TimeRange(2, 5), CPU)

            assertEquals(listOf(1L, 6L), db.timestamps())
        }
    }

    @Test
    fun `4 a series fully covered by a tombstone disappears from the result`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..3L)
            db.fill("mem", 1L..3L)
            db.flush()

            db.delete(TimeRange(0, 100), CPU)

            val got = db.querier(ALL).use { drain(it.select(emptyList())) }
            assertEquals(listOf(metric("mem")), got.map { it.first })
        }
    }

    @Test
    fun `5 delete only touches the series matched by the matchers`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..3L)
            db.fill("mem", 1L..3L)
            db.flush()

            db.delete(TimeRange(2, 2), CPU)

            val got = db.querier(ALL).use { drain(it.select(emptyList())) }
            assertEquals(listOf(1L, 3L), got.first { it.first == metric("cpu") }.second.map { it.timestamp })
            assertEquals(listOf(1L, 2L, 3L), got.first { it.first == metric("mem") }.second.map { it.timestamp })
        }
    }

    @Test
    fun `6 deleting twice is idempotent`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..5L)
            db.flush()

            db.delete(TimeRange(2, 3), CPU)
            db.delete(TimeRange(2, 3), CPU)

            assertEquals(listOf(1L, 4L, 5L), db.timestamps())
        }
    }

    @Test
    fun `7 deleted samples stay deleted after a second flush`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..5L)
            db.delete(TimeRange(2, 3), CPU)
            db.flush()

            assertEquals(listOf(1L, 4L, 5L), db.timestamps())
        }
    }
}
```

Тест 4 покрывает пункт 4 из Review Focus: серия, целиком накрытая tombstone, обязана исчезнуть, а не вернуться пустой. В `DiskBlock.select` это обеспечивает `if (samples.isNotEmpty())`, в Head это надо проверить отдельно.

Тест 7 покрывает перенос tombstones из Head в блок при флаше.

- [ ] **Step 2: Прогнать и посмотреть, что именно падает**

Run: `./gradlew test --tests '*DeleteAcrossFlushTest*' --console=plain`
Expected: часть тестов падает. Наиболее вероятные: 4 (пустая серия из Head), 7 (tombstones не доехали до блока при флаше).

- [ ] **Step 3: Починить найденное**

В `MemoryChunk.query` отбрасывать серии, у которых после применения tombstones не осталось сэмплов:

```kotlin
            val merged = mergeRef(metric, tombstone)
            if (merged.isNotEmpty()) result[labels] = merged
```

В `MemoryChunk.drainTombstones` возвращать маркеры в терминах `Labels`, чтобы `FileBlockWriter` смог превратить их в оффсеты после записи индекса.

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew test --console=plain`
Expected: PASS, все тесты.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "[lab-2]: make delete work across the flush boundary

A delete has to reach samples that already moved into a block, so it
appends tombstones to the block's tombstones file, the one mutable file
a block has. A series left with no samples after tombstones are applied
drops out of the result entirely instead of coming back empty."
```

---

## Task 10: Демо и документация

**Files:**
- Modify: `src/main/kotlin/Main.kt`
- Modify: `README.md`
- Modify: `pochti_ceasar/defense.md`
- Test: `src/test/kotlin/org/pirsbd/tsdb/DemoScenarioTest.kt`

**Interfaces:**
- Consumes: весь публичный API из задачи 8.
- Produces: `fun main()` в пакете `org.pirsbd.tsdb`, `internal fun runDemo(dir: Path, out: Appendable)` для теста.

- [ ] **Step 1: Написать падающий тест на демо**

`src/test/kotlin/org/pirsbd/tsdb/DemoScenarioTest.kt`:

```kotlin
package org.pirsbd.tsdb

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class DemoScenarioTest {

    @Test
    fun `the demo scenario runs end to end and reports both sources`() {
        val output = StringBuilder()

        runDemo(Files.createTempDirectory("tsdb-demo"), output)

        val text = output.toString()
        for (step in 1..9) {
            assertTrue(text.contains("[$step]"), "step $step missing from the demo output")
        }
        assertTrue(text.contains("HEAD"), "a series served from the head must be marked")
        assertTrue(text.contains("BLOCK"), "a series served from a block must be marked")
        assertTrue(text.contains("meta.json"))
    }
}
```

- [ ] **Step 2: Прогнать и убедиться, что падает**

Run: `./gradlew test --tests '*DemoScenarioTest*' --console=plain`
Expected: FAIL, нет `runDemo`.

- [ ] **Step 3: Написать демо**

`src/main/kotlin/Main.kt`:

```kotlin
package org.pirsbd.tsdb

import org.pirsbd.tsdb.TestLabelsUnavailable.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TSDB
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries

fun main() {
    val dir = Path("demo-data")
    val output = StringBuilder()
    runDemo(dir, output)
    print(output)
}

internal fun runDemo(dir: Path, out: Appendable) {
    val all = TimeRange(0, Long.MAX_VALUE - 1)
    val db = DefaultTSDB(dir)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { db.close() } })

    fun show(label: String, matchers: List<Matcher>) {
        db.querier(all).use { querier ->
            querier.select(matchers).use { set ->
                while (set.next()) {
                    val series = set.at()
                    val samples = ArrayList<Sample>()
                    series.iterator().use { while (it.next()) samples.add(it.at()) }
                    val source = if (db.isInHead(series.labels)) "HEAD" else "BLOCK"
                    out.appendLine("    $label ${series.labels.joinToString(",") { l -> "${l.name}=${l.value}" }} [$source] $samples")
                }
            }
        }
    }

    out.appendLine("[1] создаю БД в $dir")

    out.appendLine("[2] пишу 3 серии через Appender, commit")
    db.appender().use { appender ->
        for (t in 1L..5L) {
            appender.add(metric("cpu", "host" to "a"), t, t.toDouble())
            appender.add(metric("cpu", "host" to "b"), t, t * 10.0)
            appender.add(metric("mem"), t, t * 100.0)
        }
        appender.commit()
    }

    out.appendLine("[3] запрос __name__=cpu")
    show("cpu", listOf(Matcher.Equals("__name__", "cpu")))

    out.appendLine("[4] запрос __name__=cpu, host=a, пересечение postings")
    show("cpu+a", listOf(Matcher.Equals("__name__", "cpu"), Matcher.Equals("host", "a")))

    out.appendLine("[5] delete [2,3] для __name__=cpu")
    db.delete(TimeRange(2, 3), listOf(Matcher.Equals("__name__", "cpu")))
    show("после delete", listOf(Matcher.Equals("__name__", "cpu")))

    out.appendLine("[6] flush(), содержимое $dir")
    db.flush()
    for (entry in dir.listDirectoryEntries().sorted()) {
        out.appendLine("    $entry")
        for (file in entry.listDirectoryEntries().sorted()) out.appendLine("      ${file.fileName}")
    }
    out.appendLine("    метаданные лежат в meta.json")

    out.appendLine("[7] запрос снова, данные те же, источник теперь BLOCK")
    show("из блока", listOf(Matcher.Equals("__name__", "cpu")))

    out.appendLine("[8] close() и открываю заново")
    db.close()
    val reopened = DefaultTSDB(dir)
    reopened.querier(all).use { querier ->
        querier.select(emptyList()).use { set ->
            var count = 0
            while (set.next()) count += 1
            out.appendLine("    серий после рестарта: $count")
        }
    }

    out.appendLine("[9] out-of-order вставка и дубликат таймстемпа")
    reopened.appender().use { appender ->
        appender.add(metric("ooo"), 30, 3.0)
        appender.add(metric("ooo"), 10, 1.0)
        appender.add(metric("ooo"), 10, 111.0)
        appender.commit()
    }
    show("ooo", listOf(Matcher.Equals("__name__", "ooo")))
    out.appendLine("    t=10 пришёл после t=30 и встал на место, дубликат перезаписан на 111.0")
    reopened.close()
}
```

`metric` в демо берётся не из тестов, а из маленького хелпера рядом с `main`:

```kotlin
// src/main/kotlin/org/pirsbd/tsdb/DemoLabels.kt
package org.pirsbd.tsdb

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels

internal object TestLabelsUnavailable {
    fun metric(name: String, vararg pairs: Pair<String, String>): Labels =
        Labels((listOf(Label(Labels.METRIC_NAME, name)) + pairs.map { Label(it.first, it.second) }).sorted())
}
```

`DefaultTSDB` получает метод для демо:

```kotlin
    /** Только для демо: показать, откуда пришла серия. */
    internal fun isInHead(labels: Labels): Boolean =
        head.select(head.range, emptyList()).use { set ->
            while (set.next()) if (LabelsOrder.compare(set.at().labels, labels) == 0) return true
            false
        }
```

- [ ] **Step 4: Прогнать тесты и демо**

Run: `./gradlew test --console=plain && ./gradlew run --console=plain`
Expected: PASS. Вывод демо содержит шаги `[1]`..`[9]`, пометки `HEAD` и `BLOCK`, дерево `demo-data`.

- [ ] **Step 5: Обновить README и defense.md**

`README.md`, заменить блоки «Тесты» и «Статус по лабам»:

```markdown
## Тесты

```bash
./gradlew test
```

## Демо

```bash
./gradlew run
```

Сценарий пишет серии, читает их с фильтром по лейблам, удаляет диапазон,
флашит Head в блок на диске, перезапускает БД и показывает, что данные на
месте. В выводе каждая серия помечена источником: `HEAD` или `BLOCK`.

Посмотреть блок руками:

```bash
cat demo-data/*/meta.json
xxd -l 16 demo-data/*/index    # magic 0xBAAAD700
ls demo-data/
```

## Статус по лабам

- [x] Лаба 1 — интерфейсы
- [x] Лаба 2 — реализация, локальная БД
- [ ] Лаба 3 — REST/gRPC сервер, Docker
- [ ] Лаба 4 — репликация
- [ ] Лаба 5 — шардирование
```

`pochti_ceasar/defense.md`, дописать в конец раздел «Что изменилось в лабе 2» с таблицей из раздела 10 спеки: out-of-order принимаются, дубликат перезаписывается, `truncate` отдаёт `HeadSnapshot?`, снимок консистентен только для блоков, автофлаш только по времени данных, crash-recovery отсутствует.

- [ ] **Step 6: Коммит**

```bash
git add -A
git commit -m "[lab-2]: add the demo scenario and refresh the docs

./gradlew run walks through write, filtered read, delete, flush and
restart, marking every series with the source it came from. The HEAD and
BLOCK markers are the only visible proof that the merge works rather
than everything simply sitting in memory.

defense.md gets a section on what changed since the lab-1 interface
defense."
```

- [ ] **Step 7: Открыть PR**

```bash
git push -u origin pochti-ceasar/lab2-impl
gh pr create --title "[lab-2]: local working TSDB" --body "$(cat <<'BODY'
Реализация лабы 2 по спеке `docs/superpowers/specs/2026-10-14-lab2-local-tsdb-design.md`.

## Что сделано

- Публичный API: `DefaultTSDB`, `HeadAppender`, `DefaultQuerier`, `flush()`
- `SeriesSource` как единый интерфейс для Head и блока на диске
- Ленивый k-way merge источников по `Labels`, Head выигрывает при равном таймстемпе
- Формат блока на диске: `meta.json`, `chunks`, `index` с symbol table и postings, `tombstones`
- `FileBlockWriter` с атомарной публикацией через `rename`
- `FileStorage`: скан каталога при старте, чистка `.tmp`
- Флаш по ручному вызову и по порогу времени данных
- `delete` через tombstones, включая уже сброшенные блоки
- Тесты шести уровней, демо-сценарий `./gradlew run`

## Чего нет, сознательно

WAL и crash-recovery, компакция, XOR, mmap, матчеры кроме `=`. Обоснование в разделе 2 спеки.

## Как проверить

```bash
./gradlew test
./gradlew run
cat demo-data/*/meta.json
```

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

---

## Самопроверка плана

**Покрытие спеки:**

| Раздел спеки | Задача |
|---|---|
| 3.1 `SeriesSource` | 2 |
| 3.2 слой слияния | 2 |
| 3.3 что ленивое | 2, 6 |
| 3.4 `SeriesRef` локален | 0 |
| 4.1 отклонения кодирования | 3, 4, 5 |
| 4.2 `index` | 4 |
| 4.3 все серии | 4 |
| 4.4 `chunks` | 3 |
| 4.5 `tombstones` | 5, 9 |
| 4.6 `meta.json` | 5 |
| 4.7 атомарность и старт | 6, 7 |
| 5.1 флаш через `HeadSnapshot` | 6, 8 |
| 5.2 триггер по времени данных | 8 |
| 5.3 `Appender` | 8 |
| 5.4 семантика записи | 1 |
| 5.5 `Querier` и снимок | 8 |
| 5.6 durability и shutdown hook | 8, 10 |
| 6 тесты, уровни 1-6 | 3-9 |
| 7 демо | 10 |
| 9 баги PR #7 | 0, 1 |
| 10 правки документации | 10 |

Пропусков нет.

**Review Focus:** все пять пунктов имеют тест. Пустая БД в задаче 8 (тест 10), `range` пустого Head в задаче 1 (тест E), переполнение порога в задаче 8 (тест 13), серия под tombstone в задаче 9 (тест 4), дубль имени лейбла в задаче 4 (тест 6).

**Согласованность имён:** `SeriesSource.select` одинаково во всех задачах. `MemoryHead.truncate(upTo)` одинаково в задачах 2, 6, 8. `SortedMapSeriesSet` вводится в задаче 2 и используется в 1, 2, 6. `Tombstone(seriesOffset, range)` одинаково в задачах 5, 6, 8. `IndexContents.lookup` возвращает `List<Int>` в задачах 4 и 6.

**Известная шероховатость:** `DefaultTSDB.isInHead` нужен только демо и работает полным перебором Head. Если на шаге 10 он окажется неудобным, демо может вместо него печатать `db.querier` дважды: один раз только по блокам, один раз целиком. Это решение исполнителя задачи 10.
