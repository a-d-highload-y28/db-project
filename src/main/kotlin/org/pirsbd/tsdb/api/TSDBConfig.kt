package org.pirsbd.tsdb.api

/**
 * headWindow сравнивается с диапазоном таймстемпов сэмплов в Head, а не со
 * стенными часами. Иначе тест на автофлаш пришлось бы писать через
 * Thread.sleep, и он был бы недетерминированным.
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
