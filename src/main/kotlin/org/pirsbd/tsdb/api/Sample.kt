package org.pirsbd.tsdb.api

data class Sample(val timestamp: Long, val value: Double)

data class TimeRange(val minTime: Long, val maxTime: Long) {
    init {
        require(minTime <= maxTime) { "minTime > maxTime: $minTime > $maxTime" }
    }

    fun overlaps(other: TimeRange): Boolean =
        minTime <= other.maxTime && other.minTime <= maxTime

    fun overlaps(other: Long) : Boolean = other in minTime..maxTime

    fun contains(t: Long): Boolean = t in minTime..maxTime
}
