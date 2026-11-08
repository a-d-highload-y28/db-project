package org.pirsbd.tsdb.web.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

@Component
class DbDebugService(private val dbProvider: DbProvider) {
    suspend fun flush() = withContext(Dispatchers.IO) {
        dbProvider.db.flush()
    }
}
