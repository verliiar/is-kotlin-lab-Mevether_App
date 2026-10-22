package ru.zhuravkova.mevether.common

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated
import java.time.Clock
import java.time.Duration

@Validated
@ConfigurationProperties("mevether.jwt")
data class JwtProperties(@field:NotBlank val secret: String, val lifetime: Duration = Duration.ofHours(1)) {
    init {
        require(secret.toByteArray(Charsets.UTF_8).size >= 32) { "JWT_SECRET must contain at least 32 UTF-8 bytes" }
        require(!lifetime.isNegative && !lifetime.isZero) { "JWT_LIFETIME must be positive" }
    }
}

@Validated
@ConfigurationProperties("mevether.sync")
data class SyncProperties(@field:Min(1) @field:Max(100) val maxConflictVariants: Int = 5)

@Configuration
@EnableConfigurationProperties(JwtProperties::class, SyncProperties::class)
class RuntimeConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun startupLog() = ApplicationRunner {
        LoggerFactory.getLogger("Mevether").info("Mevether modular monolith started")
    }
}
