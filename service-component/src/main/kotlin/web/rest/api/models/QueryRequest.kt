package org.pirsbd.tsdb.web.rest.api.models

import org.pirsbd.tsdb.web.models.Label

data class QueryRequest(
    val from: Long,
    val to: Long,
    val labels: List<Label> = emptyList(),
)
