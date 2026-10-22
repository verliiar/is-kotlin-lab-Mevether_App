package ru.zhuravkova.mevether.project.api

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import ru.zhuravkova.mevether.project.application.ProjectService
import ru.zhuravkova.mevether.project.domain.ConflictPolicy
import ru.zhuravkova.mevether.project.domain.ProjectRole
import java.util.UUID

data class CreateProjectRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Size(max = 10000) val description: String? = null,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ADMIN_APPROVAL
)

data class AddMemberRequest(@field:NotBlank @field:Email @field:Size(max = 254) val email: String, val role: ProjectRole = ProjectRole.MEMBER)
data class ChangePolicyRequest(val conflictPolicy: ConflictPolicy)

@RestController
@RequestMapping("/api/projects")
class ProjectController(private val projects: ProjectService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CreateProjectRequest) =
        projects.create(UUID.fromString(jwt.subject), request.name, request.description, request.conflictPolicy)

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt) = projects.list(UUID.fromString(jwt.subject))

    @GetMapping("/{projectId}")
    fun get(@PathVariable projectId: UUID, @AuthenticationPrincipal jwt: Jwt) = projects.requireMember(projectId, UUID.fromString(jwt.subject))

    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMember(@PathVariable projectId: UUID, @AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: AddMemberRequest) =
        projects.addMember(projectId, UUID.fromString(jwt.subject), request.email, request.role)

    @GetMapping("/{projectId}/members")
    fun members(@PathVariable projectId: UUID, @AuthenticationPrincipal jwt: Jwt) = projects.members(projectId, UUID.fromString(jwt.subject))

    @PatchMapping("/{projectId}/conflict-policy")
    fun changePolicy(@PathVariable projectId: UUID, @AuthenticationPrincipal jwt: Jwt, @RequestBody request: ChangePolicyRequest) =
        projects.changePolicy(projectId, UUID.fromString(jwt.subject), request.conflictPolicy)
}
