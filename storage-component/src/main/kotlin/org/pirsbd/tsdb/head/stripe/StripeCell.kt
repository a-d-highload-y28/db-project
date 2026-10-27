package org.pirsbd.tsdb.head.stripe

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.head.memseries.MemorySeries
import org.pirsbd.tsdb.index.SeriesRef
import java.util.concurrent.locks.ReentrantReadWriteLock

internal class StripeCell {
    val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()
    val labelHashMap: HashMap<Labels, MemorySeries> = HashMap()
    val refMap: HashMap<SeriesRef, MemorySeries> = HashMap()
}