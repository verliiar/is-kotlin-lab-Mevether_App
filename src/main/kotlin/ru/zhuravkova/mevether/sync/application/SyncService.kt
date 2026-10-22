package ru.zhuravkova.mevether.sync.application

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.zhuravkova.mevether.common.BusinessException
import ru.zhuravkova.mevether.common.conflict
import ru.zhuravkova.mevether.project.domain.ProjectAccess
import ru.zhuravkova.mevether.sync.domain.*
import ru.zhuravkova.mevether.task.domain.*
import java.time.Clock
import java.util.UUID

@Service
class SyncService(private val transactions: OperationTransactions, private val fingerprints: OperationFingerprint) : TaskMutation {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun mutate(actorId: UUID, command: MutationCommand): MutationResult {
        val hash = fingerprints.hash(command.taskId, command.baseVersion, command.changes)
        return try {
            transactions.execute(actorId, command, hash).also {
                logger.info("Sync operation processed: operation={}, status={}", command.operationId, it.status)
            }
        } catch (error: BusinessException) {
            try {
                transactions.reject(actorId, command, hash, error)
            } catch (failure: DataAccessException) {
                databaseFailure(command, failure)
            }
        } catch (error: DataAccessException) {
            databaseFailure(command, error)
        } catch (error: RuntimeException) {
            logger.error("Sync operation failed: operation={}, type={}", command.operationId, error.javaClass.simpleName)
            MutationResult(command.operationId, MutationStatus.REJECTED, command.taskId,
                error = OperationError("INTERNAL_ERROR", "Operation could not be processed; retry the same operationId"), httpStatus = 500)
        }
    }

    private fun databaseFailure(command: MutationCommand, error: DataAccessException): MutationResult {
        logger.error("Sync database operation failed: operation={}, type={}", command.operationId, error.javaClass.simpleName)
        return MutationResult(command.operationId, MutationStatus.REJECTED, command.taskId,
            error = OperationError("DATABASE_UNAVAILABLE", "Database operation temporarily unavailable; retry the same operationId"), httpStatus = 503)
    }

    fun batch(actorId: UUID, commands: List<MutationCommand>) = commands.map { mutate(actorId, it) }
}

@Service
class OperationTransactions(
    private val operations: OperationRepository,
    private val tasks: TaskWorkspace,
    private val projects: ProjectAccess,
    private val engine: SyncEngine,
    private val clock: Clock
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun execute(actorId: UUID, command: MutationCommand, hash: String): MutationResult {
        operations.lock(command.operationId)
        val stored = operations.find(command.operationId)
        if (stored != null) {
            ensureSame(stored, actorId, hash)
            if (stored.result.status == MutationStatus.REJECTED) return stored.result
        }
        val task = tasks.lock(command.taskId)
        val project = projects.requireMember(task.projectId, actorId)
        if (stored != null) return stored.result
        val result = engine.apply(actorId, command, task, project.conflictPolicy)
        operations.save(StoredOperation(command.operationId, actorId, command.taskId, hash, result, clock.instant()))
        return result
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reject(actorId: UUID, command: MutationCommand, hash: String, error: BusinessException): MutationResult {
        operations.lock(command.operationId)
        val result = MutationResult(command.operationId, MutationStatus.REJECTED, command.taskId,
            error = OperationError(error.code, error.message), httpStatus = error.status)
        val stored = operations.find(command.operationId)
        if (stored != null) {
            return if (stored.actorId == actorId && stored.requestHash == hash) stored.result else result
        }
        operations.save(StoredOperation(command.operationId, actorId, command.taskId, hash, result, clock.instant()))
        return result
    }

    private fun ensureSame(stored: StoredOperation, actorId: UUID, hash: String) {
        if (stored.actorId != actorId || stored.requestHash != hash) conflict("OPERATION_ID_REUSED", "operationId already belongs to another request")
    }
}
