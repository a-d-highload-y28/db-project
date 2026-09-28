package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.storage.Block

interface Head : AutoCloseable {
    val range: TimeRange
    fun append(labels: Labels, timestamp: Long, value: Double)
    fun query(range: TimeRange, matchers: List<Matcher>): SeriesSet
    fun delete(range: TimeRange, matchers: List<Matcher>)
    fun truncate(minTime: Long): Block?
}
