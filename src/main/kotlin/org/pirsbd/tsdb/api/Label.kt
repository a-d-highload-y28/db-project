package org.pirsbd.tsdb.api

data class  Label(val name: String, val value: String) : Comparable<Label> {
    override fun compareTo(other: Label): Int {
        val n = name.compareTo(other.name)
        return if (n != 0) n else value.compareTo(other.value)
    }
}

@JvmInline
value class Labels(private val sorted: List<Label>) : List<Label> by sorted {
    fun get(name: String): String? = sorted.firstOrNull { it.name == name }?.value

    companion object {
        val METRIC_NAME = "__name__"

        fun of(vararg labels: Label): Labels = Labels(labels.toList().sorted())

        fun of(pairs: Map<String, String>): Labels =
            Labels(pairs.map { Label(it.key, it.value) }.sorted())
    }
}
