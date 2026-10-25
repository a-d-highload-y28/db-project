package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels

/** Единый порядок Labels: лексикографический по парам (name, value). */
object LabelsOrder {
    fun compare(a: Labels, b: Labels): Int {
        val common = minOf(a.size, b.size)
        for (i in 0 until common) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    val COMPARATOR: Comparator<Labels> = Comparator { a, b -> compare(a, b) }
}
