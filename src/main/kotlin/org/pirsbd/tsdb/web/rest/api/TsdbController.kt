package org.pirsbd.tsdb.web.rest.api

import org.pirsbd.tsdb.web.models.Label
import org.pirsbd.tsdb.web.models.Series
import org.pirsbd.tsdb.web.rest.api.models.DeleteRequest
import org.pirsbd.tsdb.web.rest.api.models.InsertRequest
import org.pirsbd.tsdb.web.rest.api.models.LabelConst
import org.pirsbd.tsdb.web.rest.api.models.QueryRequest
import org.pirsbd.tsdb.web.service.DbService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class TsdbController(private val dbService: DbService) {
    @PostMapping("{name}/query")
    suspend fun query(
        @PathVariable("name") name: String,
        @RequestBody request: QueryRequest,
    ) : List<Series>{

        val matchers = arrayListOf(Label(name = LabelConst.NameLabel, value = name))

        matchers.addAll(request.labels)

        val result = dbService.query(matchers, request.from, request.to)

        return result
    }

    @DeleteMapping("{name}")
    suspend fun delete(
        @PathVariable("name") name: String,
        @RequestBody request: DeleteRequest,
    ) {

        val matchers = arrayListOf(Label(name = LabelConst.NameLabel, value = name))

        matchers.addAll(request.labels)

        dbService.delete(matchers, request.from, request.to)
    }

    @PutMapping("{name}")
    suspend fun insert(
        @PathVariable("name") name: String,
        @RequestBody request: InsertRequest,
    ) {

        val matchers = arrayListOf(Label(name = LabelConst.NameLabel, value = name))

        matchers.addAll(request.labels)

        dbService.insert(matchers, request.at, request.value)
    }
}
