package ru.zhuravkova.mevether.identity.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import ru.zhuravkova.mevether.identity.domain.User
import ru.zhuravkova.mevether.identity.domain.UserRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity(
    @Id var id: UUID,
    @Column(nullable = false, length = 50) var username: String,
    @Column(nullable = false, length = 254) var email: String,
    @Column(name = "password_hash", nullable = false, length = 100) var passwordHash: String,
    @Column(name = "created_at", nullable = false) var createdAt: Instant
) {
    fun domain() = User(id, username, email, passwordHash, createdAt)
}

interface UserJpaRepository : JpaRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?
    fun existsByUsername(username: String): Boolean
}

@Repository
class JpaUserRepository(private val repository: UserJpaRepository) : UserRepository {
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.domain()
    override fun findByEmail(email: String) = repository.findByEmail(email)?.domain()
    override fun usernameExists(username: String) = repository.existsByUsername(username)
    override fun save(user: User) = repository.saveAndFlush(UserEntity(user.id, user.username, user.email, user.passwordHash, user.createdAt)).domain()
}
