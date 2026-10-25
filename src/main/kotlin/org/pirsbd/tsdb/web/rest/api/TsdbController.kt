package org.pirsbd.tsdb.web.rest.api

import org.pirsbd.tsdb.web.models.Label
import org.pirsbd.tsdb.web.models.Series
import org.pirsbd.tsdb.web.rest.api.models.LabelConst
import org.pirsbd.tsdb.web.rest.api.models.QueryMatcher
import org.pirsbd.tsdb.web.service.DbService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class TsdbController(private val dbService: DbService) {
    @GetMapping("{name}/query")
    suspend fun query(
        @PathVariable("name") name: String,
              @RequestParam(value = "from") from: Long,
              @RequestParam(value = "to") to: Long,
              @RequestParam(value = "labels", required = false) labels: List<QueryMatcher>
    ) : List<Series>{

        val matchers = arrayListOf(Label(name = LabelConst.NameLabel, value = name))

        for (label in labels) {
            matchers.add(Label(name = label.name, value = label.value))
        }

        val result = dbService.query(matchers, from, to)

        return result
    }

    @DeleteMapping("{name}")
    suspend fun delete(
        @PathVariable("name") name: String,
        @RequestParam(value = "from") from: Long,
        @RequestParam(value = "to") to: Long,
        @RequestParam(value = "labels", required = false) labels: List<QueryMatcher>
    ) {

        val matchers = arrayListOf(Label(name = LabelConst.NameLabel, value = name))

        for (label in labels) {
            matchers.add(Label(name = label.name, value = label.value))
        }

        dbService.delete(matchers, from, to)
    }

    @PutMapping("{name}")
    suspend fun insert(
        @RequestParam(value = "at") at: Long,
        @RequestParam(value = "value") value: Double,
        @PathVariable("name") name: String,
        @RequestParam(value = "labels", required = false) labels: List<QueryMatcher>
    ) {

        val matchers = arrayListOf(Label(name = LabelConst.NameLabel, value = name))

        for (label in labels) {
            matchers.add(Label(name = label.name, value = label.value))
        }

        dbService.insert(matchers, at, value)
    }
}