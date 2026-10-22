package ru.zhuravkova.mevether.sync.api

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import ru.zhuravkova.mevether.sync.application.SyncService
import ru.zhuravkova.mevether.task.domain.*
import java.util.UUID

data class SyncOperationRequest(val operationId: UUID, val taskId: UUID, val baseVersion: Long, val changes: Map<String, Any?>)
data class SyncBatchRequest(@field:Size(min = 1, max = 100) val operations: List<SyncOperationRequest>)
data class SyncOperationResponse(val operationId: UUID, val status: MutationStatus, val taskId: UUID, val currentVersion: Long?, val conflictIds: List<UUID>, val error: OperationError?)
data class SyncBatchResponse(val results: List<SyncOperationResponse>)

@RestController
@RequestMapping("/api/sync")
class SyncController(private val sync: SyncService) {
    @PostMapping("/operations")
    fun synchronize(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: SyncBatchRequest) =
        SyncBatchResponse(sync.batch(UUID.fromString(jwt.subject), request.operations.map {
            MutationCommand(it.operationId, it.taskId, it.baseVersion, it.changes)
        }).map { SyncOperationResponse(it.operationId, it.status, it.taskId, it.currentVersion, it.conflictIds, it.error) })
}
