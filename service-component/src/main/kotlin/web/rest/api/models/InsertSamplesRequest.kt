package org.pirsbd.tsdb.web.rest.api.models

import org.pirsbd.tsdb.web.models.Label

data class InsertSamplesRequest(
    val at: Long,
    val value: Double,
    val labels: List<Label> = emptyList(),
)
