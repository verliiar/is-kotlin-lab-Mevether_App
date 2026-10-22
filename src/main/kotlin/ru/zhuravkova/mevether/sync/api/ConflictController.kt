package ru.zhuravkova.mevether.sync.api

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import ru.zhuravkova.mevether.sync.application.ConflictService
import ru.zhuravkova.mevether.sync.domain.ConflictStatus
import ru.zhuravkova.mevether.sync.domain.ResolutionChoice
import ru.zhuravkova.mevether.sync.domain.ResolutionCommand
import java.util.UUID

data class ResolveConflictRequest(val choice: ResolutionChoice, val variantId: UUID? = null, val value: Any? = null)

@RestController
@RequestMapping("/api")
class ConflictController(private val conflicts: ConflictService) {
    @GetMapping("/projects/{projectId}/conflicts")
    fun list(@PathVariable projectId: UUID, @AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) status: ConflictStatus?) =
        conflicts.list(projectId, UUID.fromString(jwt.subject), status)

    @GetMapping("/conflicts/{conflictId}")
    fun get(@PathVariable conflictId: UUID, @AuthenticationPrincipal jwt: Jwt) = conflicts.get(conflictId, UUID.fromString(jwt.subject))

    @GetMapping("/conflicts/{conflictId}/variants")
    fun variants(@PathVariable conflictId: UUID, @AuthenticationPrincipal jwt: Jwt) = conflicts.variants(conflictId, UUID.fromString(jwt.subject))

    @PostMapping("/conflicts/{conflictId}/resolutions")
    fun resolve(@PathVariable conflictId: UUID, @AuthenticationPrincipal jwt: Jwt, @RequestBody request: ResolveConflictRequest) =
        conflicts.resolve(conflictId, UUID.fromString(jwt.subject), ResolutionCommand(request.choice, request.variantId, request.value))
}
