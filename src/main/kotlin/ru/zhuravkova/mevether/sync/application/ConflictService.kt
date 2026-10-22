package ru.zhuravkova.mevether.sync.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.zhuravkova.mevether.common.conflict
import ru.zhuravkova.mevether.common.invalid
import ru.zhuravkova.mevether.common.missing
import ru.zhuravkova.mevether.project.domain.ProjectAccess
import ru.zhuravkova.mevether.sync.domain.*
import ru.zhuravkova.mevether.task.domain.TaskWorkspace
import java.time.Clock
import java.util.UUID

@Service
class ConflictService(private val conflicts: ConflictRepository, private val tasks: TaskWorkspace, private val projects: ProjectAccess, private val clock: Clock) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(projectId: UUID, actorId: UUID, status: ConflictStatus?): List<Conflict> {
        projects.requireMember(projectId, actorId)
        return conflicts.list(projectId, status)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID, actorId: UUID): Conflict {
        val item = conflicts.find(id) ?: missing("Conflict")
        projects.requireMember(item.projectId, actorId)
        return item
    }

    @Transactional(readOnly = true)
    fun variants(id: UUID, actorId: UUID): List<ConflictVariant> {
        get(id, actorId)
        return conflicts.variants(id)
    }

    @Transactional
    fun resolve(id: UUID, actorId: UUID, command: ResolutionCommand): Conflict {
        val initial = conflicts.find(id) ?: missing("Conflict")
        projects.requireAdmin(initial.projectId, actorId)
        val task = tasks.lock(initial.taskId)
        val item = conflicts.lock(id) ?: missing("Conflict")
        if (item.status != ConflictStatus.PENDING) conflict("ALREADY_RESOLVED", "Conflict has already been resolved")
        val value = when (command.choice) {
            ResolutionChoice.CURRENT -> {
                if (command.variantId != null || command.value != null) invalid("CURRENT does not accept variantId or value")
                task.values()[item.fieldName]
            }
            ResolutionChoice.VARIANT -> {
                if (command.variantId == null || command.value != null) invalid("VARIANT requires variantId and no value")
                (conflicts.variants(id).find { it.id == command.variantId } ?: missing("Conflict variant")).proposedValue
            }
            ResolutionChoice.CUSTOM -> {
                if (command.variantId != null) invalid("CUSTOM does not accept variantId")
                command.value
            }
        }
        val normalized = tasks.validateChanges(task.projectId, mapOf(item.fieldName to value))
        val order = tasks.nextOrder()
        val operationId = UUID.randomUUID()
        val updated = tasks.apply(task, actorId, operationId, order, normalized, "CONFLICT_RESOLVED")
        tasks.record(updated, actorId, operationId, order, "RESOLUTION", item.fieldName, item.currentValue, normalized[item.fieldName])
        val resolved = conflicts.save(item.copy(status = ConflictStatus.RESOLVED, resolvedAt = clock.instant(), resolvedBy = actorId))
        logger.info("Conflict resolved: conflict={}, task={}, version={}", id, task.id, updated.version)
        return resolved
    }
}
