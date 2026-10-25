package org.pirsbd.tsdb.web.rest.api

import org.pirsbd.tsdb.web.service.DbDebugService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class DebugController(private val dbDebugService: DbDebugService) {
    @PostMapping("debug/flush")
    suspend fun flush() {
        dbDebugService.flush()
    }
}
