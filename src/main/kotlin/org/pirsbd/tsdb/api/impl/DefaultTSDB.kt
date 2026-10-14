package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Appender
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Querier
import org.pirsbd.tsdb.api.TSDB
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.Head
import org.pirsbd.tsdb.head.MemoryHead
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class DefaultTSDB : TSDB {
    @OptIn(ExperimentalAtomicApi::class)
    private val headList: AtomicArray<Head> = AtomicArray(1, { _ -> MemoryHead() })

    override fun appender(): Appender {
        TODO("Not yet implemented")
    }

    override fun querier(range: TimeRange): Querier {
        TODO("Not yet implemented")
    }

    override fun delete(
        range: TimeRange,
        matchers: List<Matcher>
    ) {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }
}