package org.pirsbd.tsdb.web.service

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.TSDB
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import org.pirsbd.tsdb.web.models.Label
import org.pirsbd.tsdb.web.models.Sample
import org.pirsbd.tsdb.web.models.Series
import org.pirsbd.tsdb.web.service.configuration.TsdbConfig
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import kotlin.io.path.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

interface DbService {
    suspend fun insert(labels: List<Label>, timestamp: Long, value: Double)
    suspend fun delete(labels: List<Label>, from: Long, to: Long)
    suspend fun query(matchers: List<Label>, from: Long, to: Long): List<Series>
}

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class TsdbDbService(
    private val config: TsdbConfig
) : DbService {
    private val db: TSDB = DefaultTSDB(Path(config.directory))

    @PostConstruct
    fun initDbDirectory() {
        val path = Path(config.directory)

        if (path.exists() && path.isDirectory()) {
            return
        }

        if (path.exists()) {
            throw IllegalStateException("The path at ${path.toAbsolutePath()} is already taken by a file.")
        }

        path.createDirectory()
    }

    override suspend fun insert(
        labels: List<Label>,
        timestamp: Long,
        value: Double
    ) {
        val tsdbLabels = labels.map { label -> org.pirsbd.tsdb.api.Label(label.name, label.value) }.toList()

        return withContext(Dispatchers.IO) {
            db.appender().add(Labels(tsdbLabels), timestamp, value)
        }
    }

    override suspend fun delete(labels: List<Label>, from: Long, to: Long) {
        val matchers = labels.map { label -> Matcher.Equals(label.name, label.value) }.toList()

        return withContext(Dispatchers.IO) {
            db.delete(TimeRange(from, to), matchers)
        }
    }

    override suspend fun query(
        matchers: List<Label>,
        from: Long,
        to: Long
    ): List<Series> {
        val matchers = matchers.map { label -> Matcher.Equals(label.name, label.value) }.toList()
        val result = mutableListOf<Series>()

        return withContext(Dispatchers.IO) {
            db.querier(TimeRange(from, to)).use { querier ->
                querier.select(matchers).use { set ->
                    while (set.next()) {
                        val series = set.at()
                        val samples = mutableListOf<Sample>()

                        series.iterator().use { iterator ->
                            while (iterator.next()) {
                                val sample = iterator.at()
                                samples.add(Sample(sample.timestamp, sample.value))
                            }
                        }

                        val labels = series.labels.map { label -> Label(label.name, label.value) }.toTypedArray()
                        result.add(Series(labels, samples))
                    }
                }
            }

            return@withContext result
        }
    }


    @PreDestroy
    fun flush() {
        db.flush()
    }
}
