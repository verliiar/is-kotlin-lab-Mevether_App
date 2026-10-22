package ru.zhuravkova.mevether.sync.infrastructure

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import ru.zhuravkova.mevether.sync.domain.*
import ru.zhuravkova.mevether.task.domain.*
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "conflicts")
class ConflictEntity(
    @Id var id: UUID,
    @Column(name = "project_id", nullable = false) var projectId: UUID,
    @Column(name = "task_id", nullable = false) var taskId: UUID,
    @Column(name = "field_name", nullable = false, length = 30) var fieldName: String,
    @Column(name = "base_value", columnDefinition = "text") var baseValue: String?,
    @Column(name = "current_value", columnDefinition = "text") var currentValue: String?,
    @Column(name = "base_version", nullable = false) var baseVersion: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) var status: ConflictStatus,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "resolved_at") var resolvedAt: Instant?,
    @Column(name = "resolved_by") var resolvedBy: UUID?
) {
    fun domain() = Conflict(id, projectId, taskId, fieldName, baseValue, currentValue, baseVersion, status, createdAt, resolvedAt, resolvedBy)
}

@Entity
@Table(name = "conflict_variants")
class VariantEntity(
    @Id var id: UUID,
    @Column(name = "conflict_id", nullable = false) var conflictId: UUID,
    @Column(name = "operation_id", nullable = false) var operationId: UUID,
    @Column(name = "author_id", nullable = false) var authorId: UUID,
    @Column(name = "proposed_value", columnDefinition = "text") var proposedValue: String?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant
) {
    fun domain() = ConflictVariant(id, conflictId, operationId, authorId, proposedValue, createdAt)
}

@Entity
@Table(name = "sync_operations")
class OperationEntity(
    @Id @Column(name = "operation_id") var operationId: UUID,
    @Column(name = "actor_id", nullable = false) var actorId: UUID,
    @Column(name = "task_id", nullable = false) var taskId: UUID,
    @Column(name = "request_hash", nullable = false, length = 64) var requestHash: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: MutationStatus,
    @Column(name = "current_version") var currentVersion: Long?,
    @Column(name = "conflict_ids", nullable = false, columnDefinition = "text") var conflictIds: String,
    @Column(name = "error_code", length = 50) var errorCode: String?,
    @Column(name = "error_message", length = 300) var errorMessage: String?,
    @Column(name = "http_status", nullable = false) var httpStatus: Int,
    @Column(name = "created_at", nullable = false) var createdAt: Instant
) {
    fun domain() = StoredOperation(operationId, actorId, taskId, requestHash,
        MutationResult(operationId, status, taskId, currentVersion,
            conflictIds.split(',').filter { it.isNotEmpty() }.map(UUID::fromString),
            errorCode?.let { OperationError(it, errorMessage ?: "Operation rejected") }, httpStatus), createdAt)
}

interface ConflictJpaRepository : JpaRepository<ConflictEntity, UUID> {
    fun findByTaskIdAndFieldNameAndStatus(taskId: UUID, fieldName: String, status: ConflictStatus): ConflictEntity?
    fun findAllByProjectIdOrderByCreatedAtAscIdAsc(projectId: UUID): List<ConflictEntity>
}

interface VariantJpaRepository : JpaRepository<VariantEntity, UUID> {
    fun findAllByConflictIdOrderByCreatedAtAscIdAsc(conflictId: UUID): List<VariantEntity>
}

interface OperationJpaRepository : JpaRepository<OperationEntity, UUID>

@Repository
class JpaConflictRepository(private val conflicts: ConflictJpaRepository, private val variants: VariantJpaRepository, private val entityManager: EntityManager) : ConflictRepository {
    override fun find(id: UUID) = conflicts.findById(id).orElse(null)?.domain()
    override fun lock(id: UUID): Conflict? {
        val entity = conflicts.findById(id).orElse(null) ?: return null
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)
        return entity.domain()
    }
    override fun pending(taskId: UUID, fieldName: String) = conflicts.findByTaskIdAndFieldNameAndStatus(taskId, fieldName, ConflictStatus.PENDING)?.domain()
    override fun list(projectId: UUID, status: ConflictStatus?) = conflicts.findAllByProjectIdOrderByCreatedAtAscIdAsc(projectId)
        .filter { status == null || it.status == status }.map { it.domain() }
    override fun save(conflict: Conflict) = conflicts.saveAndFlush(ConflictEntity(conflict.id, conflict.projectId, conflict.taskId, conflict.fieldName,
        conflict.baseValue, conflict.currentValue, conflict.baseVersion, conflict.status, conflict.createdAt, conflict.resolvedAt, conflict.resolvedBy)).domain()
    override fun variants(conflictId: UUID) = variants.findAllByConflictIdOrderByCreatedAtAscIdAsc(conflictId).map { it.domain() }
    override fun addVariant(variant: ConflictVariant) {
        variants.saveAndFlush(VariantEntity(variant.id, variant.conflictId, variant.operationId, variant.authorId, variant.proposedValue, variant.createdAt))
    }
}

@Repository
class JpaOperationRepository(private val operations: OperationJpaRepository, private val jdbc: JdbcTemplate) : OperationRepository {
    override fun lock(operationId: UUID) {
        jdbc.execute(ConnectionCallback<Unit> { connection ->
            connection.prepareStatement("select pg_advisory_xact_lock(?)").use { statement ->
                statement.setLong(1, operationId.mostSignificantBits xor operationId.leastSignificantBits)
                statement.execute()
            }
        })
    }
    override fun find(operationId: UUID) = operations.findById(operationId).orElse(null)?.domain()
    override fun save(operation: StoredOperation) {
        val result = operation.result
        operations.saveAndFlush(OperationEntity(operation.operationId, operation.actorId, operation.taskId, operation.requestHash, result.status,
            result.currentVersion, result.conflictIds.joinToString(","), result.error?.code, result.error?.message, result.httpStatus, operation.createdAt))
    }
}

@Component
class JsonOperationFingerprint(private val mapper: ObjectMapper) : OperationFingerprint {
    override fun hash(taskId: UUID, baseVersion: Long, changes: Map<String, Any?>): String {
        val payload = mapper.writeValueAsBytes(listOf(taskId.toString(), baseVersion, changes.toSortedMap()))
        return MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    }
}
