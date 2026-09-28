package org.pirsbd.tsdb.index

interface Postings {
    fun next(): Boolean
    fun seek(target: SeriesRef): Boolean
    fun at(): SeriesRef
}

object EmptyPostings : Postings {
    override fun next(): Boolean = false
    override fun seek(target: SeriesRef): Boolean = false
    override fun at(): SeriesRef = error("empty postings")
}
