package ru.zhuravkova.mevether.task.domain

import ru.zhuravkova.mevether.common.invalid
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class TaskStatus { TODO, IN_PROGRESS, DONE }
enum class TaskPriority { LOW, MEDIUM, HIGH }

data class Task(
    val id: UUID,
    val projectId: UUID,
    val title: String,
    val description: String?,
    val status: TaskStatus,
    val priority: TaskPriority,
    val assigneeId: UUID?,
    val dueDate: LocalDate?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID
) {
    fun values(): Map<String, String?> = linkedMapOf(
        "title" to title, "description" to description, "status" to status.name,
        "priority" to priority.name, "assigneeId" to assigneeId?.toString(), "dueDate" to dueDate?.toString()
    )

    fun withValues(values: Map<String, String?>, now: Instant): Task = copy(
        title = values["title"]!!,
        description = values["description"],
        status = TaskStatus.valueOf(values["status"]!!),
        priority = TaskPriority.valueOf(values["priority"]!!),
        assigneeId = values["assigneeId"]?.let(UUID::fromString),
        dueDate = values["dueDate"]?.let(LocalDate::parse),
        version = version + 1,
        updatedAt = now
    )
}

object TaskFields {
    val names = setOf("title", "description", "status", "priority", "assigneeId", "dueDate")

    fun normalize(changes: Map<String, Any?>): Map<String, String?> {
        if (changes.isEmpty() || changes.size > names.size || !names.containsAll(changes.keys)) invalid("Provide 1 to 6 supported task fields")
        return changes.toSortedMap().mapValues { (field, raw) ->
            if (raw != null && raw !is String) invalid("$field must be a string or null")
            val value = raw
            when (field) {
                "title" -> value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: invalid("title must contain 1 to 200 characters")
                "description" -> value?.also { if (it.length > 10000) invalid("description must not exceed 10000 characters") }
                "status" -> value?.takeIf { candidate -> TaskStatus.entries.any { it.name == candidate } } ?: invalid("Invalid status")
                "priority" -> value?.takeIf { candidate -> TaskPriority.entries.any { it.name == candidate } } ?: invalid("Invalid priority")
                "assigneeId" -> value?.let { runCatching { UUID.fromString(it).toString() }.getOrElse { invalid("Invalid assigneeId") } }
                "dueDate" -> value?.let { runCatching { LocalDate.parse(it).toString() }.getOrElse { invalid("Invalid dueDate") } }
                else -> invalid("Unsupported field")
            }
        }
    }
}

data class HistoryEntry(
    val id: UUID,
    val taskId: UUID,
    val actorId: UUID,
    val operationId: UUID,
    val operationOrder: Long,
    val operationType: String,
    val fieldName: String,
    val oldValue: String?,
    val newValue: String?,
    val taskVersion: Long,
    val affectsState: Boolean,
    val createdAt: Instant
)

interface TaskRepository {
    fun find(id: UUID): Task?
    fun lock(id: UUID): Task?
    fun list(projectId: UUID): List<Task>
    fun save(task: Task): Task
    fun history(taskId: UUID): List<HistoryEntry>
    fun append(entries: List<HistoryEntry>)
    fun nextOperationOrder(): Long
}

interface TaskWorkspace {
    fun lock(taskId: UUID): Task
    fun validateChanges(projectId: UUID, changes: Map<String, Any?>): Map<String, String?>
    fun baseValues(task: Task, baseVersion: Long): Map<String, String?>
    fun apply(task: Task, actorId: UUID, operationId: UUID, order: Long, changes: Map<String, String?>, operationType: String): Task
    fun record(task: Task, actorId: UUID, operationId: UUID, order: Long, type: String, field: String, old: String?, proposed: String?)
    fun nextOrder(): Long
}

data class MutationCommand(val operationId: UUID, val taskId: UUID, val baseVersion: Long, val changes: Map<String, Any?>)
enum class MutationStatus { APPLIED, MERGED, CONFLICT_PENDING, REJECTED }
data class OperationError(val code: String, val message: String)
data class MutationResult(
    val operationId: UUID,
    val status: MutationStatus,
    val taskId: UUID,
    val currentVersion: Long? = null,
    val conflictIds: List<UUID> = emptyList(),
    val error: OperationError? = null,
    val httpStatus: Int = 200
)

interface TaskMutation {
    fun mutate(actorId: UUID, command: MutationCommand): MutationResult
}
