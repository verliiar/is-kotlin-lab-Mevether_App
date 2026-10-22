package ru.zhuravkova.mevether.sync.domain

import ru.zhuravkova.mevether.project.domain.ConflictPolicy
import ru.zhuravkova.mevether.task.domain.MutationResult
import java.time.Instant
import java.util.UUID

enum class ConflictStatus { PENDING, RESOLVED }

data class Conflict(
    val id: UUID,
    val projectId: UUID,
    val taskId: UUID,
    val fieldName: String,
    val baseValue: String?,
    val currentValue: String?,
    val baseVersion: Long,
    val status: ConflictStatus,
    val createdAt: Instant,
    val resolvedAt: Instant? = null,
    val resolvedBy: UUID? = null
)

data class ConflictVariant(val id: UUID, val conflictId: UUID, val operationId: UUID, val authorId: UUID, val proposedValue: String?, val createdAt: Instant)
data class StoredOperation(val operationId: UUID, val actorId: UUID, val taskId: UUID, val requestHash: String, val result: MutationResult, val createdAt: Instant)

interface ConflictRepository {
    fun find(id: UUID): Conflict?
    fun lock(id: UUID): Conflict?
    fun pending(taskId: UUID, fieldName: String): Conflict?
    fun list(projectId: UUID, status: ConflictStatus?): List<Conflict>
    fun save(conflict: Conflict): Conflict
    fun variants(conflictId: UUID): List<ConflictVariant>
    fun addVariant(variant: ConflictVariant)
}

interface OperationRepository {
    fun lock(operationId: UUID)
    fun find(operationId: UUID): StoredOperation?
    fun save(operation: StoredOperation)
}

interface OperationFingerprint {
    fun hash(taskId: UUID, baseVersion: Long, changes: Map<String, Any?>): String
}

enum class ResolutionChoice { CURRENT, VARIANT, CUSTOM }
data class ResolutionCommand(val choice: ResolutionChoice, val variantId: UUID? = null, val value: Any? = null)

sealed interface FieldResolution {
    data class Accepted(val value: String?) : FieldResolution
    data object RequiresApproval : FieldResolution
}

interface ConflictResolutionStrategy {
    val policy: ConflictPolicy
    fun resolve(fieldName: String, base: String?, current: String?, proposed: String?): FieldResolution
}

class LastWriteWinsStrategy : ConflictResolutionStrategy {
    override val policy = ConflictPolicy.LAST_WRITE_WINS
    override fun resolve(fieldName: String, base: String?, current: String?, proposed: String?) = FieldResolution.Accepted(proposed)
}

class AdminApprovalStrategy : ConflictResolutionStrategy {
    override val policy = ConflictPolicy.ADMIN_APPROVAL
    override fun resolve(fieldName: String, base: String?, current: String?, proposed: String?) = FieldResolution.RequiresApproval
}

class SmartMergeStrategy(private val merger: ThreeWayTextMerger = ThreeWayTextMerger()) : ConflictResolutionStrategy {
    override val policy = ConflictPolicy.SMART_MERGE
    override fun resolve(fieldName: String, base: String?, current: String?, proposed: String?): FieldResolution {
        if (fieldName !in setOf("title", "description") || base == null || current == null || proposed == null) return FieldResolution.RequiresApproval
        val merged = merger.merge(base, current, proposed) ?: return FieldResolution.RequiresApproval
        val maxLength = if (fieldName == "title") 200 else 10000
        return if (merged.length > maxLength || (fieldName == "title" && merged.isBlank())) FieldResolution.RequiresApproval else FieldResolution.Accepted(merged)
    }
}
