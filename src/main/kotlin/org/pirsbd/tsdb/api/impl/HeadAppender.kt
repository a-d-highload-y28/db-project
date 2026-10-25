package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Appender
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.head.Head

/**
 * Буферизует батч и применяет его одним commit().
 *
 * Валидация идёт в add(), а не в commit(), чтобы исключение прилетело рядом
 * с вызовом, который его заслужил.
 */
internal class HeadAppender(
    private val head: Head,
    private val afterCommit: () -> Unit,
) : Appender {

    private class Pending(val labels: Labels, val timestamp: Long, val value: Double)

    private val pending = ArrayList<Pending>()

    override fun add(labels: Labels, timestamp: Long, value: Double) {
        require(labels.isNotEmpty()) { "labels must not be empty" }
        require(labels.get(Labels.METRIC_NAME) != null) {
            "labels must contain ${Labels.METRIC_NAME}: $labels"
        }
        pending.add(Pending(labels, timestamp, value))
    }

    override fun commit() {
        for (sample in pending) head.append(sample.labels, sample.timestamp, sample.value)
        pending.clear()
        afterCommit()
    }

    override fun rollback() {
        pending.clear()
    }

    /** Без commit() батч отбрасывается: close() это rollback. */
    override fun close() {
        pending.clear()
    }
}
