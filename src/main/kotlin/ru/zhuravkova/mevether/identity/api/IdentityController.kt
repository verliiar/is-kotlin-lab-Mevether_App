package ru.zhuravkova.mevether.identity.api

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import ru.zhuravkova.mevether.identity.application.IdentityService
import java.util.UUID

data class RegisterRequest(
    @field:Pattern(regexp = "[A-Za-z0-9.-]{3,50}") val username: String,
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(min = 8, max = 72) val password: String
)

data class LoginRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(max = 72) val password: String
)

@RestController
@RequestMapping("/api")
class IdentityController(private val identity: IdentityService) {
    @PostMapping("/auth/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest) = identity.register(request.username, request.email, request.password)

    @PostMapping("/auth/tokens")
    fun login(@Valid @RequestBody request: LoginRequest) = identity.login(request.email, request.password)

    @GetMapping("/users/me")
    fun current(@AuthenticationPrincipal jwt: Jwt) = identity.requireUser(UUID.fromString(jwt.subject))
}
