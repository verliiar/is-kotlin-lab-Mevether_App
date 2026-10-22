package ru.zhuravkova.mevether.identity.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.zhuravkova.mevether.common.BusinessException
import ru.zhuravkova.mevether.common.conflict
import ru.zhuravkova.mevether.common.missing
import ru.zhuravkova.mevether.common.invalid
import ru.zhuravkova.mevether.identity.domain.*
import java.time.Clock
import java.util.Locale
import java.util.UUID

@Service
class IdentityService(
    private val users: UserRepository,
    private val passwords: PasswordHasher,
    private val tokens: TokenIssuer,
    private val clock: Clock
) : IdentityDirectory {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun register(username: String, email: String, password: String): UserProfile {
        if (password.toByteArray(Charsets.UTF_8).size > 72) invalid("Password must not exceed 72 UTF-8 bytes")
        val normalizedUsername = username.trim().lowercase(Locale.ROOT)
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        if (users.usernameExists(normalizedUsername) || users.findByEmail(normalizedEmail) != null) {
            conflict("USER_EXISTS", "Username or email already registered")
        }
        return users.save(User(UUID.randomUUID(), normalizedUsername, normalizedEmail, passwords.hash(password), clock.instant())).profile()
    }

    @Transactional(readOnly = true)
    fun login(email: String, password: String): AccessToken {
        val user = users.findByEmail(email.trim().lowercase(Locale.ROOT))
        if (user == null || password.toByteArray(Charsets.UTF_8).size > 72 || !passwords.matches(password, user.passwordHash)) {
            logger.warn("Authentication rejected")
            throw BusinessException(401, "INVALID_CREDENTIALS", "Invalid email or password")
        }
        return tokens.issue(user.id)
    }

    @Transactional(readOnly = true)
    override fun requireUser(id: UUID): UserProfile = users.findById(id)?.profile() ?: missing("User")

    @Transactional(readOnly = true)
    override fun requireByEmail(email: String): UserProfile =
        users.findByEmail(email.trim().lowercase(Locale.ROOT))?.profile() ?: missing("User")
}
