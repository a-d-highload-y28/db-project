package org.pirsbd.tsdb.web.service

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.pirsbd.tsdb.api.TSDB
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import org.pirsbd.tsdb.web.service.configuration.TsdbConfig
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class DbProvider(private val config: TsdbConfig) {
    private lateinit var database: TSDB

    val db: TSDB
        get() = database

    @PostConstruct
    fun initialize() {
        val directory = Path(config.directory)
        if (directory.exists() && !directory.isDirectory()) {
            throw IllegalStateException("The path at ${directory.toAbsolutePath()} is already taken by a file.")
        }

        directory.createDirectories()
        database = DefaultTSDB(directory)
    }

    @PreDestroy
    fun close() {
        if (::database.isInitialized) {
            database.close()
        }
    }
}
