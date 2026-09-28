package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.index.SeriesRef

interface TombstoneReader : AutoCloseable {
    fun get(ref: SeriesRef): List<TimeRange>
}

interface TombstoneWriter : AutoCloseable {
    fun add(ref: SeriesRef, range: TimeRange)
    fun finish()
}
