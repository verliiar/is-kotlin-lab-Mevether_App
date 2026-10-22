package ru.zhuravkova.mevether.identity.infrastructure

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.*
import org.springframework.security.web.SecurityFilterChain
import org.springframework.stereotype.Component
import ru.zhuravkova.mevether.common.ApiError
import ru.zhuravkova.mevether.common.JwtProperties
import ru.zhuravkova.mevether.identity.domain.AccessToken
import ru.zhuravkova.mevether.identity.domain.PasswordHasher
import ru.zhuravkova.mevether.identity.domain.TokenIssuer
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@Configuration
class SecurityConfiguration(private val properties: JwtProperties, private val mapper: ObjectMapper) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/auth/registrations", "/api/auth/tokens").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth ->
                oauth.jwt { }
                oauth.authenticationEntryPoint { request, response, _ -> unauthorized(request, response) }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ -> unauthorized(request, response) }
                it.accessDeniedHandler { request, response, _ -> writeError(request, response, 403, "FORBIDDEN", "Access denied") }
            }
        return http.build()
    }

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder.withSecretKey(key()).algorithm(MacAlgorithm.HS256).build()

    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withSecretKey(key()).macAlgorithm(MacAlgorithm.HS256).build().also {
        it.setJwtValidator(JwtValidators.createDefaultWithIssuer("Mevether"))
    }

    private fun key() = SecretKeySpec(properties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    private fun unauthorized(request: HttpServletRequest, response: HttpServletResponse) {
        logger.warn("Unauthenticated request rejected")
        response.setHeader("WWW-Authenticate", "Bearer")
        writeError(request, response, 401, "UNAUTHORIZED", "Authentication required")
    }

    private fun writeError(request: HttpServletRequest, response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = "application/json"
        mapper.writeValue(response.outputStream, ApiError(status, code, message, request.requestURI))
    }
}

@Component
class BCryptPasswordHasher : PasswordHasher {
    private val encoder = BCryptPasswordEncoder(12)
    override fun hash(password: String): String = requireNotNull(encoder.encode(password))
    override fun matches(password: String, hash: String) = encoder.matches(password, hash)
}

@Component
class JwtTokenIssuer(private val encoder: JwtEncoder, private val properties: JwtProperties, private val clock: Clock) : TokenIssuer {
    override fun issue(userId: UUID): AccessToken {
        val now = clock.instant()
        val claims = JwtClaimsSet.builder().issuer("Mevether").subject(userId.toString())
            .issuedAt(now).expiresAt(now.plus(properties.lifetime)).id(UUID.randomUUID().toString()).build()
        val token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        return AccessToken(token.tokenValue, expiresIn = properties.lifetime.seconds)
    }
}
