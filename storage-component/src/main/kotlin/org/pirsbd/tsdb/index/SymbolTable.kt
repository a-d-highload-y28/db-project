package org.pirsbd.tsdb.index

interface SymbolTable {
    val size: Int
    fun lookup(id: Int): String
    fun idOf(symbol: String): Int?
}
