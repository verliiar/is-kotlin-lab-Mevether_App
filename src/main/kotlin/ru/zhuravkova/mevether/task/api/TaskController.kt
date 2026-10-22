package ru.zhuravkova.mevether.task.api

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import ru.zhuravkova.mevether.task.application.TaskService
import ru.zhuravkova.mevether.common.BusinessException
import ru.zhuravkova.mevether.task.domain.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateTaskRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:Size(max = 10000) val description: String? = null,
    val status: TaskStatus = TaskStatus.TODO,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val assigneeId: UUID? = null,
    val dueDate: LocalDate? = null
)

data class UpdateTaskRequest(val operationId: UUID, @field:Min(0) val baseVersion: Long, @field:Size(min = 1, max = 6) val changes: Map<String, Any?>)
data class TaskResponse(val id: UUID, val projectId: UUID, val title: String, val description: String?, val status: TaskStatus,
    val priority: TaskPriority, val assigneeId: UUID?, val dueDate: LocalDate?, val version: Long,
    val createdAt: Instant, val updatedAt: Instant, val createdBy: UUID)

fun Task.response() = TaskResponse(id, projectId, title, description, status, priority, assigneeId, dueDate, version, createdAt, updatedAt, createdBy)

data class MutationResponse(val operationId: UUID, val status: MutationStatus, val taskId: UUID, val currentVersion: Long?, val conflictIds: List<UUID>, val error: OperationError?)
fun MutationResult.response() = MutationResponse(operationId, status, taskId, currentVersion, conflictIds, error)

@RestController
@RequestMapping("/api")
class TaskController(private val tasks: TaskService, private val mutations: TaskMutation) {
    @PostMapping("/projects/{projectId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable projectId: UUID, @AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CreateTaskRequest) =
        tasks.create(projectId, UUID.fromString(jwt.subject), request.title, request.description, request.status, request.priority, request.assigneeId, request.dueDate).response()

    @GetMapping("/projects/{projectId}/tasks")
    fun list(@PathVariable projectId: UUID, @AuthenticationPrincipal jwt: Jwt) = tasks.list(projectId, UUID.fromString(jwt.subject)).map { it.response() }

    @GetMapping("/tasks/{taskId}")
    fun get(@PathVariable taskId: UUID, @AuthenticationPrincipal jwt: Jwt) = tasks.get(taskId, UUID.fromString(jwt.subject)).response()

    @PatchMapping("/tasks/{taskId}")
    fun update(@PathVariable taskId: UUID, @AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: UpdateTaskRequest): ResponseEntity<MutationResponse> {
        val result = mutations.mutate(UUID.fromString(jwt.subject), MutationCommand(request.operationId, taskId, request.baseVersion, request.changes))
        result.error?.let { throw BusinessException(result.httpStatus, it.code, it.message) }
        return ResponseEntity.status(result.httpStatus).body(result.response())
    }

    @GetMapping("/tasks/{taskId}/history")
    fun history(@PathVariable taskId: UUID, @AuthenticationPrincipal jwt: Jwt) = tasks.history(taskId, UUID.fromString(jwt.subject))
}
