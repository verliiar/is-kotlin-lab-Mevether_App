package ru.zhuravkova.mevether.identity.domain

import java.time.Instant
import java.util.UUID

data class User(val id: UUID, val username: String, val email: String, val passwordHash: String, val createdAt: Instant)
data class UserProfile(val id: UUID, val username: String, val email: String, val createdAt: Instant)

fun User.profile() = UserProfile(id, username, email, createdAt)

interface UserRepository {
    fun findById(id: UUID): User?
    fun findByEmail(email: String): User?
    fun usernameExists(username: String): Boolean
    fun save(user: User): User
}

interface IdentityDirectory {
    fun requireUser(id: UUID): UserProfile
    fun requireByEmail(email: String): UserProfile
}

interface PasswordHasher {
    fun hash(password: String): String
    fun matches(password: String, hash: String): Boolean
}

data class AccessToken(val accessToken: String, val tokenType: String = "Bearer", val expiresIn: Long)

interface TokenIssuer {
    fun issue(userId: UUID): AccessToken
}
