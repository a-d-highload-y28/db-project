package org.pirsbd.tsdb

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries

private val ALL = TimeRange(Long.MIN_VALUE, Long.MAX_VALUE)

private fun metric(name: String, vararg pairs: Pair<String, String>): Labels =
    Labels((listOf(Label(Labels.METRIC_NAME, name)) + pairs.map { Label(it.first, it.second) }).sorted())

private fun Labels.render(): String = joinToString(",") { "${it.name}=${it.value}" }


internal fun demo() {
    val dir = Path("demo-data")

    // Сценарий рассказывает историю по шагам, поэтому он обязан начинаться с
    // пустого каталога. На втором прогоне поверх старых данных шаг [3] уже
    // печатал [HEAD+BLOCK], то есть результат шага [8], и демо перестало
    // показывать то, ради чего существует.
    if (Files.exists(dir)) {
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
    // SIGTERM (Ctrl+C) перехватывается, и Head успевает сброситься.
    // SIGKILL нет: там несброшенный Head теряется, WAL отложен на лабу 4.
    val output = StringBuilder()
    runDemo(dir, output)
    print(output)
}

internal fun runDemo(dir: Path, out: Appendable) {
    var db = DefaultTSDB(dir)
    val hook = Thread { runCatching { db.close() } }
    Runtime.getRuntime().addShutdownHook(hook)

    fun show(label: String, matchers: List<Matcher>) {
        db.querier(ALL).use { querier ->
            querier.select(matchers).use { set ->
                var empty = true
                while (set.next()) {
                    empty = false
                    val series = set.at()
                    val samples = ArrayList<Sample>()
                    series.iterator().use { while (it.next()) samples.add(it.at()) }
                    val source = db.sourceTagsOf(series.labels, ALL).joinToString("+")
                    out.appendLine("    $label ${series.labels.render()} [$source] ${samples.map { it.timestamp }}")
                }
                if (empty) out.appendLine("    $label (ничего не найдено)")
            }
        }
    }

    out.appendLine("[1] создаю БД в $dir")

    out.appendLine("[2] пишу 3 серии через Appender, один commit")
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

    out.appendLine("[4] запрос __name__=cpu и host=a, пересечение postings")
    show("cpu+a", listOf(Matcher.Equals("__name__", "cpu"), Matcher.Equals("host", "a")))

    out.appendLine("[5] delete [2,3] для __name__=cpu, через tombstones")
    db.delete(TimeRange(2, 3), listOf(Matcher.Equals("__name__", "cpu")))
    show("после delete", listOf(Matcher.Equals("__name__", "cpu")))

    out.appendLine("[6] flush(), содержимое $dir")
    db.flush()
    for (entry in dir.listDirectoryEntries().sorted()) {
        out.appendLine("    ${entry.fileName}/")
        for (file in entry.listDirectoryEntries().sorted()) {
            out.appendLine("      ${file.fileName}")
            if (Files.isDirectory(file)) {
                for (nested in file.listDirectoryEntries().sorted()) {
                    out.appendLine("        ${nested.fileName}")
                }
            }
        }
    }

    out.appendLine("[7] тот же запрос, данные те же, источник теперь BLOCK")
    show("из блока", listOf(Matcher.Equals("__name__", "cpu")))

    out.appendLine("[8] дописываю t=6 в ту же серию, запрос сливает Head и блок")
    db.appender().use { appender ->
        appender.add(metric("cpu", "host" to "a"), 6, 6.0)
        appender.commit()
    }
    show("merge", listOf(Matcher.Equals("__name__", "cpu")))

    out.appendLine("[9] close(), открываю заново, данные пережили рестарт")
    db.close()
    Runtime.getRuntime().removeShutdownHook(hook)
    db = DefaultTSDB(dir)
    show("после рестарта", listOf(Matcher.Equals("__name__", "cpu")))

    out.appendLine("[10] out-of-order вставка и дубликат таймстемпа")
    db.appender().use { appender ->
        appender.add(metric("ooo"), 30, 3.0)
        appender.add(metric("ooo"), 10, 1.0)
        appender.add(metric("ooo"), 10, 111.0)
        appender.commit()
    }
    db.querier(ALL).use { querier ->
        querier.select(listOf(Matcher.Equals("__name__", "ooo"))).use { set ->
            while (set.next()) {
                val samples = ArrayList<Sample>()
                set.at().iterator().use { while (it.next()) samples.add(it.at()) }
                out.appendLine("    ooo $samples")
            }
        }
    }
    out.appendLine("    t=10 пришёл после t=30 и встал на место, дубликат t=10 перезаписан на 111.0")

    db.close()
    out.appendLine("готово, блоки лежат в $dir, метаданные в meta.json каждого блока")
}

@SpringBootApplication
open class Main

fun main(args: Array<String>) {
    runApplication<Main>(*args)
}