package ru.zhuravkova.mevether.task.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.zhuravkova.mevether.common.invalid
import ru.zhuravkova.mevether.common.missing
import ru.zhuravkova.mevether.project.domain.ProjectAccess
import ru.zhuravkova.mevether.task.domain.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Service
class TaskService(private val tasks: TaskRepository, private val projects: ProjectAccess, private val clock: Clock) : TaskWorkspace {
    @Transactional
    fun create(projectId: UUID, actorId: UUID, title: String, description: String?, status: TaskStatus, priority: TaskPriority, assigneeId: UUID?, dueDate: LocalDate?): Task {
        projects.requireMember(projectId, actorId)
        val values = validateChanges(projectId, linkedMapOf("title" to title, "description" to description,
            "status" to status.name, "priority" to priority.name, "assigneeId" to assigneeId?.toString(), "dueDate" to dueDate?.toString()))
        val now = clock.instant()
        val task = tasks.save(Task(UUID.randomUUID(), projectId, values["title"]!!, description, status, priority, assigneeId, dueDate, 0, now, now, actorId))
        val operationId = UUID.randomUUID()
        val order = tasks.nextOperationOrder()
        tasks.append(task.values().map { (field, value) ->
            HistoryEntry(UUID.randomUUID(), task.id, actorId, operationId, order, "CREATED", field, null, value, 0, true, now)
        })
        return task
    }

    @Transactional(readOnly = true)
    fun get(taskId: UUID, actorId: UUID): Task {
        val task = tasks.find(taskId) ?: missing("Task")
        projects.requireMember(task.projectId, actorId)
        return task
    }

    @Transactional(readOnly = true)
    fun list(projectId: UUID, actorId: UUID): List<Task> {
        projects.requireMember(projectId, actorId)
        return tasks.list(projectId)
    }

    @Transactional(readOnly = true)
    fun history(taskId: UUID, actorId: UUID): List<HistoryEntry> {
        get(taskId, actorId)
        return tasks.history(taskId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(taskId: UUID) = tasks.lock(taskId) ?: missing("Task")

    override fun validateChanges(projectId: UUID, changes: Map<String, Any?>): Map<String, String?> {
        val normalized = TaskFields.normalize(changes)
        normalized["assigneeId"]?.let {
            if (!projects.isMember(projectId, UUID.fromString(it))) invalid("Assignee must be a project member")
        }
        return normalized
    }

    override fun baseValues(task: Task, baseVersion: Long): Map<String, String?> {
        if (baseVersion < 0 || baseVersion > task.version) invalid("baseVersion must be between zero and currentVersion")
        val values = task.values().toMutableMap()
        tasks.history(task.id).asReversed().filter { it.affectsState && it.taskVersion > baseVersion }.forEach {
            values[it.fieldName] = it.oldValue
        }
        return values
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun apply(task: Task, actorId: UUID, operationId: UUID, order: Long, changes: Map<String, String?>, operationType: String): Task {
        val oldValues = task.values()
        val effective = changes.filter { (field, value) -> oldValues[field] != value }
        if (effective.isEmpty()) return task
        val now = clock.instant()
        val updated = tasks.save(task.withValues(oldValues + effective, now))
        tasks.append(effective.map { (field, value) ->
            HistoryEntry(UUID.randomUUID(), task.id, actorId, operationId, order, operationType, field, oldValues[field], value, updated.version, true, now)
        })
        return updated
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun record(task: Task, actorId: UUID, operationId: UUID, order: Long, type: String, field: String, old: String?, proposed: String?) {
        tasks.append(listOf(HistoryEntry(UUID.randomUUID(), task.id, actorId, operationId, order, type, field, old, proposed, task.version, false, clock.instant())))
    }

    override fun nextOrder() = tasks.nextOperationOrder()
}
