package org.pirsbd.tsdb.api

sealed interface Matcher {
    val name: String

    data class Equals(override val name: String, val value: String) : Matcher
}
