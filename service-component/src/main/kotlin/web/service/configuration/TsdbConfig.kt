package org.pirsbd.tsdb.web.service.configuration

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "tsdb.config")
@EnableConfigurationProperties
class TsdbConfig {
    @Value("DB_DIRECTORY:/var/lib/tsdb")
    lateinit var directory: String
}