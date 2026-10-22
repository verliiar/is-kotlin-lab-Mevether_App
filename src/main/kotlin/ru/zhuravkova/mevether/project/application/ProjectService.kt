package ru.zhuravkova.mevether.project.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.zhuravkova.mevether.common.conflict
import ru.zhuravkova.mevether.common.forbidden
import ru.zhuravkova.mevether.common.missing
import ru.zhuravkova.mevether.identity.domain.IdentityDirectory
import ru.zhuravkova.mevether.project.domain.*
import java.time.Clock
import java.util.UUID

@Service
class ProjectService(private val projects: ProjectRepository, private val identity: IdentityDirectory, private val clock: Clock) : ProjectAccess {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(actorId: UUID, name: String, description: String?, policy: ConflictPolicy): Project {
        identity.requireUser(actorId)
        val project = projects.save(Project(UUID.randomUUID(), name.trim(), description, clock.instant(), actorId, policy))
        projects.addMember(ProjectMember(project.id, actorId, ProjectRole.ADMIN))
        logger.info("Project created: {}", project.id)
        return project
    }

    @Transactional(readOnly = true)
    fun list(actorId: UUID) = projects.listFor(actorId)

    @Transactional(readOnly = true)
    override fun requireMember(projectId: UUID, userId: UUID): Project {
        val project = projects.find(projectId) ?: missing("Project")
        if (!isMember(projectId, userId)) forbidden()
        return project
    }

    @Transactional(readOnly = true)
    override fun requireAdmin(projectId: UUID, userId: UUID): Project {
        val project = requireMember(projectId, userId)
        if (projects.member(projectId, userId)?.role != ProjectRole.ADMIN) forbidden()
        return project
    }

    override fun isMember(projectId: UUID, userId: UUID) = projects.member(projectId, userId) != null

    @Transactional
    fun addMember(projectId: UUID, actorId: UUID, email: String, role: ProjectRole): ProjectMember {
        requireAdmin(projectId, actorId)
        val user = identity.requireByEmail(email)
        if (isMember(projectId, user.id)) conflict("MEMBER_EXISTS", "User is already a project member")
        return projects.addMember(ProjectMember(projectId, user.id, role))
    }

    @Transactional(readOnly = true)
    fun members(projectId: UUID, actorId: UUID): List<ProjectMember> {
        requireMember(projectId, actorId)
        return projects.members(projectId)
    }

    @Transactional
    fun changePolicy(projectId: UUID, actorId: UUID, policy: ConflictPolicy): Project {
        val project = requireAdmin(projectId, actorId)
        return projects.save(project.copy(conflictPolicy = policy))
    }
}
