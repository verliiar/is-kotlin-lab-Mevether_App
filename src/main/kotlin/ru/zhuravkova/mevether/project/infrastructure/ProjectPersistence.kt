package ru.zhuravkova.mevether.project.infrastructure

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import ru.zhuravkova.mevether.project.domain.*
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "projects")
class ProjectEntity(
    @Id var id: UUID,
    @Column(nullable = false, length = 200) var name: String,
    @Column(columnDefinition = "text") var description: String?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "created_by", nullable = false) var createdBy: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "conflict_policy", nullable = false, length = 30) var conflictPolicy: ConflictPolicy
) {
    fun domain() = Project(id, name, description, createdAt, createdBy, conflictPolicy)
}

data class MemberKey(var projectId: UUID = UUID(0, 0), var userId: UUID = UUID(0, 0)) : Serializable

@Entity
@Table(name = "project_members")
@IdClass(MemberKey::class)
class MemberEntity(
    @Id @Column(name = "project_id") var projectId: UUID,
    @Id @Column(name = "user_id") var userId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) var role: ProjectRole
) {
    fun domain() = ProjectMember(projectId, userId, role)
}

interface ProjectJpaRepository : JpaRepository<ProjectEntity, UUID> {
    @Query("select p from ProjectEntity p where p.id in (select m.projectId from MemberEntity m where m.userId = :userId) order by p.createdAt, p.id")
    fun listFor(userId: UUID): List<ProjectEntity>
}

interface MemberJpaRepository : JpaRepository<MemberEntity, MemberKey> {
    fun findAllByProjectIdOrderByUserId(projectId: UUID): List<MemberEntity>
}

@Repository
class JpaProjectRepository(private val projects: ProjectJpaRepository, private val members: MemberJpaRepository) : ProjectRepository {
    override fun save(project: Project) = projects.saveAndFlush(ProjectEntity(project.id, project.name, project.description, project.createdAt, project.createdBy, project.conflictPolicy)).domain()
    override fun find(id: UUID) = projects.findById(id).orElse(null)?.domain()
    override fun listFor(userId: UUID) = projects.listFor(userId).map { it.domain() }
    override fun member(projectId: UUID, userId: UUID) = members.findById(MemberKey(projectId, userId)).orElse(null)?.domain()
    override fun members(projectId: UUID) = members.findAllByProjectIdOrderByUserId(projectId).map { it.domain() }
    override fun addMember(member: ProjectMember) = members.saveAndFlush(MemberEntity(member.projectId, member.userId, member.role)).domain()
}
