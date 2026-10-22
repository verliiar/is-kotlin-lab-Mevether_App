package ru.zhuravkova.mevether.sync.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.zhuravkova.mevether.common.SyncProperties
import ru.zhuravkova.mevether.common.conflict
import ru.zhuravkova.mevether.project.domain.ConflictPolicy
import ru.zhuravkova.mevether.sync.domain.*
import ru.zhuravkova.mevether.task.domain.*
import java.time.Clock
import java.util.UUID

@Service
class SyncEngine(private val tasks: TaskWorkspace, private val conflicts: ConflictRepository, private val properties: SyncProperties, private val clock: Clock) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val strategies = listOf(LastWriteWinsStrategy(), SmartMergeStrategy(), AdminApprovalStrategy()).associateBy { it.policy }
    private data class PendingField(val field: String, val proposed: String?, val conflict: Conflict?)

    @Transactional(propagation = Propagation.MANDATORY)
    fun apply(actorId: UUID, command: MutationCommand, task: Task, policy: ConflictPolicy): MutationResult {
        val changes = tasks.validateChanges(task.projectId, command.changes)
        val base = tasks.baseValues(task, command.baseVersion)
        val current = task.values()
        val accepted = mutableMapOf<String, String?>()
        val pending = mutableListOf<PendingField>()
        val strategy = strategies.getValue(policy)
        for ((field, proposed) in changes) {
            val active = conflicts.pending(task.id, field)
            if (active != null) {
                if (conflicts.variants(active.id).size >= properties.maxConflictVariants) conflict("VARIANT_LIMIT", "Conflict variant limit reached")
                pending.add(PendingField(field, proposed, active))
            } else if (proposed == base[field]) {
                continue
            } else if (current[field] == base[field] || current[field] == proposed) {
                accepted[field] = proposed
            } else {
                when (val resolution = strategy.resolve(field, base[field], current[field], proposed)) {
                    is FieldResolution.Accepted -> accepted[field] = resolution.value
                    FieldResolution.RequiresApproval -> pending.add(PendingField(field, proposed, null))
                }
            }
        }
        val order = tasks.nextOrder()
        for ((field, proposed) in changes) tasks.record(task, actorId, command.operationId, order, "PROPOSED", field, base[field], proposed)
        val conflictIds = pending.map { item ->
            val active = item.conflict ?: conflicts.save(Conflict(UUID.randomUUID(), task.projectId, task.id, item.field,
                base[item.field], current[item.field], command.baseVersion, ConflictStatus.PENDING, clock.instant()))
            conflicts.addVariant(ConflictVariant(UUID.randomUUID(), active.id, command.operationId, actorId, item.proposed, clock.instant()))
            logger.info("Conflict proposal accepted: conflict={}, operation={}", active.id, command.operationId)
            active.id
        }
        val stale = command.baseVersion < task.version
        val type = if (stale && policy == ConflictPolicy.LAST_WRITE_WINS) "LAST_WRITE_WINS" else if (stale) "MERGED" else "UPDATED"
        val updated = tasks.apply(task, actorId, command.operationId, order, accepted, type)
        val status = when {
            conflictIds.isNotEmpty() -> MutationStatus.CONFLICT_PENDING
            stale -> MutationStatus.MERGED
            else -> MutationStatus.APPLIED
        }
        return MutationResult(command.operationId, status, task.id, updated.version, conflictIds)
    }
}
