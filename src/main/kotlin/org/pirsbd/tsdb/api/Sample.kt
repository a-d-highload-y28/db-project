package org.pirsbd.tsdb.api

data class Sample(val timestamp: Long, val value: Double)

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
         * блок не попадают в выборку.
         *
         * Нужен потому, что пустой Head держит minTime = Long.MAX_VALUE и
         * maxTime = Long.MIN_VALUE, а TimeRange(MAX, MIN) падает на require.
         */
        val EMPTY = TimeRange(Long.MAX_VALUE, Long.MAX_VALUE)
    }
}
