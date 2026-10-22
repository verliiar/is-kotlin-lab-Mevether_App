package ru.zhuravkova.mevether.project.domain

import java.time.Instant
import java.util.UUID

enum class ProjectRole { ADMIN, MEMBER }
enum class ConflictPolicy { LAST_WRITE_WINS, SMART_MERGE, ADMIN_APPROVAL }

data class Project(val id: UUID, val name: String, val description: String?, val createdAt: Instant, val createdBy: UUID, val conflictPolicy: ConflictPolicy)
data class ProjectMember(val projectId: UUID, val userId: UUID, val role: ProjectRole)

interface ProjectRepository {
    fun save(project: Project): Project
    fun find(id: UUID): Project?
    fun listFor(userId: UUID): List<Project>
    fun member(projectId: UUID, userId: UUID): ProjectMember?
    fun members(projectId: UUID): List<ProjectMember>
    fun addMember(member: ProjectMember): ProjectMember
}

interface ProjectAccess {
    fun requireMember(projectId: UUID, userId: UUID): Project
    fun requireAdmin(projectId: UUID, userId: UUID): Project
    fun isMember(projectId: UUID, userId: UUID): Boolean
}
