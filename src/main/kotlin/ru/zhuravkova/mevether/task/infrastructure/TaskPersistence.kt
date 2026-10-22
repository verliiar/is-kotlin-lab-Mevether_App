package ru.zhuravkova.mevether.task.infrastructure

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import ru.zhuravkova.mevether.task.domain.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "tasks")
class TaskEntity(
    @Id var id: UUID,
    @Column(name = "project_id", nullable = false) var projectId: UUID,
    @Column(nullable = false, length = 200) var title: String,
    @Column(columnDefinition = "text") var description: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: TaskStatus,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) var priority: TaskPriority,
    @Column(name = "assignee_id") var assigneeId: UUID?,
    @Column(name = "due_date") var dueDate: LocalDate?,
    @Column(nullable = false) var version: Long,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "created_by", nullable = false) var createdBy: UUID,
    @Version @Column(name = "lock_version", nullable = false) var lockVersion: Long? = null
) {
    fun domain() = Task(id, projectId, title, description, status, priority, assigneeId, dueDate, version, createdAt, updatedAt, createdBy)
}

@Entity
@Table(name = "task_change_history")
class HistoryEntity(
    @Id var id: UUID,
    @Column(name = "task_id", nullable = false) var taskId: UUID,
    @Column(name = "actor_id", nullable = false) var actorId: UUID,
    @Column(name = "operation_id", nullable = false) var operationId: UUID,
    @Column(name = "operation_order", nullable = false) var operationOrder: Long,
    @Column(name = "operation_type", nullable = false, length = 30) var operationType: String,
    @Column(name = "field_name", nullable = false, length = 30) var fieldName: String,
    @Column(name = "old_value", columnDefinition = "text") var oldValue: String?,
    @Column(name = "new_value", columnDefinition = "text") var newValue: String?,
    @Column(name = "task_version", nullable = false) var taskVersion: Long,
    @Column(name = "affects_state", nullable = false) var affectsState: Boolean,
    @Column(name = "created_at", nullable = false) var createdAt: Instant
) {
    fun domain() = HistoryEntry(id, taskId, actorId, operationId, operationOrder, operationType, fieldName, oldValue, newValue, taskVersion, affectsState, createdAt)
}

interface TaskJpaRepository : JpaRepository<TaskEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskEntity t where t.id = :id")
    fun lockById(id: UUID): TaskEntity?
    fun findAllByProjectIdOrderByCreatedAtAscIdAsc(projectId: UUID): List<TaskEntity>
}

interface HistoryJpaRepository : JpaRepository<HistoryEntity, UUID> {
    fun findAllByTaskIdOrderByOperationOrderAscFieldNameAsc(taskId: UUID): List<HistoryEntity>
}

@Repository
class JpaTaskRepository(private val tasks: TaskJpaRepository, private val history: HistoryJpaRepository, private val entityManager: EntityManager) : TaskRepository {
    override fun find(id: UUID) = tasks.findById(id).orElse(null)?.domain()
    override fun lock(id: UUID) = tasks.lockById(id)?.domain()
    override fun list(projectId: UUID) = tasks.findAllByProjectIdOrderByCreatedAtAscIdAsc(projectId).map { it.domain() }

    override fun save(task: Task): Task {
        val entity = tasks.findById(task.id).orElse(null)
        if (entity == null) return tasks.saveAndFlush(TaskEntity(task.id, task.projectId, task.title, task.description, task.status, task.priority,
            task.assigneeId, task.dueDate, task.version, task.createdAt, task.updatedAt, task.createdBy)).domain()
        entity.title = task.title
        entity.description = task.description
        entity.status = task.status
        entity.priority = task.priority
        entity.assigneeId = task.assigneeId
        entity.dueDate = task.dueDate
        entity.version = task.version
        entity.updatedAt = task.updatedAt
        return tasks.saveAndFlush(entity).domain()
    }

    override fun history(taskId: UUID) = history.findAllByTaskIdOrderByOperationOrderAscFieldNameAsc(taskId).map { it.domain() }
    override fun append(entries: List<HistoryEntry>) {
        history.saveAllAndFlush(entries.map { HistoryEntity(it.id, it.taskId, it.actorId, it.operationId, it.operationOrder,
            it.operationType, it.fieldName, it.oldValue, it.newValue, it.taskVersion, it.affectsState, it.createdAt) })
    }
    override fun nextOperationOrder() = (entityManager.createNativeQuery("select nextval('accepted_operation_order')", Long::class.javaObjectType).singleResult as Number).toLong()
}
