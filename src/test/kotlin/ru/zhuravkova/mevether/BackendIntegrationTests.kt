package ru.zhuravkova.mevether

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.zhuravkova.mevether.sync.application.SyncService
import ru.zhuravkova.mevether.task.domain.MutationCommand
import ru.zhuravkova.mevether.task.domain.MutationStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendIntegrationTests {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            postgres.start()
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("mevether.jwt.secret") { "integration-tests-only-secret-32-bytes-minimum" }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var sync: SyncService
    private data class Actor(val id: UUID, val token: String, val email: String)
    private data class Fixture(val project: UUID, val task: UUID)
    private lateinit var admin: Actor
    private lateinit var member: Actor
    private lateinit var outsider: Actor

    @BeforeAll
    fun users() {
        admin = actor()
        member = actor()
        outsider = actor()
    }

    private fun call(method: String, path: String, actor: Actor? = null, body: Any? = null, expected: Int = 200): JsonNode {
        val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON)
        actor?.let { request.header("Authorization", "Bearer ${it.token}") }
        body?.let { request.content(mapper.writeValueAsString(it)) }
        val response = mvc.perform(request).andReturn().response
        assertEquals(expected, response.status, response.contentAsString)
        return mapper.readTree(response.contentAsString)
    }

    private fun actor(): Actor {
        val name = "u" + UUID.randomUUID().toString().replace("-", "")
        val email = "$name@example.test"
        val user = call("POST", "/api/auth/registrations", body = mapOf("username" to name, "email" to email, "password" to "SecurePassword1!"), expected = 201)
        val token = call("POST", "/api/auth/tokens", body = mapOf("email" to email, "password" to "SecurePassword1!"))["accessToken"].asString()
        return Actor(UUID.fromString(user["id"].asString()), token, email)
    }

    private fun fixture(policy: String = "ADMIN_APPROVAL", title: String = "Hello world", description: String? = null): Fixture {
        val project = call("POST", "/api/projects", admin, mapOf("name" to "Lab project", "conflictPolicy" to policy), 201)["id"].asString()
        call("POST", "/api/projects/$project/members", admin, mapOf("email" to member.email), 201)
        val task = call("POST", "/api/projects/$project/tasks", member, mapOf("title" to title, "description" to description), 201)["id"].asString()
        return Fixture(UUID.fromString(project), UUID.fromString(task))
    }

    private fun patch(f: Fixture, changes: Map<String, Any?>, version: Long = 0, actor: Actor = member, operationId: UUID = UUID.randomUUID(), expected: Int = 200) =
        call("PATCH", "/api/tasks/${f.task}", actor, mapOf("operationId" to operationId, "baseVersion" to version, "changes" to changes), expected)

    private fun get(f: Fixture) = call("GET", "/api/tasks/${f.task}", member)
    private fun pending(f: Fixture): UUID {
        patch(f, mapOf("title" to "server"))
        return UUID.fromString(patch(f, mapOf("title" to "client"))["conflictIds"][0].asString())
    }

    private fun <T> parallel(count: Int, action: (Int) -> T): List<T> {
        val executor = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        return try {
            val futures = (0 until count).map { index -> executor.submit<T> { start.await(); action(index) } }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun registrationNormalizesIdentityAndHashesPassword() {
        val username = "User" + UUID.randomUUID().toString().replace("-", "")
        val user = call("POST", "/api/auth/registrations", body = mapOf("username" to username, "email" to "$username@EXAMPLE.TEST", "password" to "Password123"), expected = 201)
        assertEquals(username.lowercase(), user["username"].asString())
        assertFalse(user.has("passwordHash"))
        val hash = jdbc.queryForObject("select password_hash from users where id = ?", String::class.java, UUID.fromString(user["id"].asString()))!!
        assertTrue(hash.startsWith("$2"))
        assertNotEquals("Password123", hash)
        call("POST", "/api/auth/registrations", body = mapOf("username" to username, "email" to "$username@EXAMPLE.TEST", "password" to "Password123"), expected = 409)
    }

    @Test
    fun loginAndCurrentUserUseRealJwt() {
        val current = call("GET", "/api/users/me", admin)
        assertEquals(admin.id.toString(), current["id"].asString())
        call("POST", "/api/auth/tokens", body = mapOf("email" to admin.email, "password" to "WrongPassword"), expected = 401)
        call("GET", "/api/users/me", expected = 401)
        call("GET", "/api/users/me", admin.copy(token = admin.token.dropLast(10) + "invalid"), expected = 401)
    }

    @Test
    fun validatesRequestsWithoutLeakingInternals() {
        val error = call("POST", "/api/auth/registrations", body = mapOf("username" to "x", "email" to "invalid", "password" to "short"), expected = 400)
        assertEquals("VALIDATION_FAILED", error["code"].asString())
        assertFalse(error.has("trace"))
        call("POST", "/api/projects", admin, mapOf("name" to "   "), 400)
    }

    @Test
    fun projectCreatorIsAdminAndMemberCanRead() {
        val f = fixture()
        val members = call("GET", "/api/projects/${f.project}/members", member)
        assertTrue(members.any { it["userId"].asString() == admin.id.toString() && it["role"].asString() == "ADMIN" })
        assertTrue(members.any { it["userId"].asString() == member.id.toString() && it["role"].asString() == "MEMBER" })
        assertTrue(call("GET", "/api/projects", member).any { it["id"].asString() == f.project.toString() })
        call("GET", "/api/projects/${f.project}", member)
        call("POST", "/api/projects/${f.project}/members", member, mapOf("email" to outsider.email), 403)
        call("POST", "/api/projects/${f.project}/members", admin, mapOf("email" to member.email), 409)
    }

    @Test
    fun onlyAdminCanChangePolicy() {
        val f = fixture()
        call("PATCH", "/api/projects/${f.project}/conflict-policy", member, mapOf("conflictPolicy" to "SMART_MERGE"), 403)
        val project = call("PATCH", "/api/projects/${f.project}/conflict-policy", admin, mapOf("conflictPolicy" to "SMART_MERGE"))
        assertEquals("SMART_MERGE", project["conflictPolicy"].asString())
    }

    @Test
    fun outsidersCannotAccessProjectTaskHistoryConflictsOrSync() {
        val f = fixture()
        call("GET", "/api/projects/${f.project}", outsider, expected = 403)
        call("GET", "/api/projects/${f.project}/tasks", outsider, expected = 403)
        call("GET", "/api/tasks/${f.task}", outsider, expected = 403)
        call("GET", "/api/tasks/${f.task}/history", outsider, expected = 403)
        call("GET", "/api/projects/${f.project}/conflicts", outsider, expected = 403)
        patch(f, mapOf("title" to "intrusion"), actor = outsider, expected = 403)
        val batch = call("POST", "/api/sync/operations", outsider, mapOf("operations" to listOf(MutationCommand(UUID.randomUUID(), f.task, 0, mapOf("title" to "intrusion")))))
        assertEquals("FORBIDDEN", batch["results"][0]["error"]["code"].asString())
    }

    @Test
    fun taskCreationAndUpdatesPersistVersionAndHistory() {
        val f = fixture()
        assertEquals(0, get(f)["version"].asLong())
        val result = patch(f, mapOf("title" to "Updated", "status" to "IN_PROGRESS", "priority" to "HIGH", "assigneeId" to admin.id.toString(), "dueDate" to "2027-01-15"))
        assertEquals(1, result["currentVersion"].asLong())
        val task = get(f)
        assertEquals("Updated", task["title"].asString())
        assertEquals("IN_PROGRESS", task["status"].asString())
        assertEquals("HIGH", task["priority"].asString())
        assertEquals(admin.id.toString(), task["assigneeId"].asString())
        assertEquals("2027-01-15", task["dueDate"].asString())
        val history = call("GET", "/api/tasks/${f.task}/history", member)
        assertTrue(history.any { it["fieldName"].asString() == "title" && it["oldValue"].asString() == "Hello world" && it["newValue"].asString() == "Updated" && it["affectsState"].asBoolean() })
        assertEquals(5, history.count { it["taskVersion"].asLong() == 1L && it["affectsState"].asBoolean() })
    }

    @Test
    fun validatesAssigneeFieldsAndBaseVersionAtomically() {
        val f = fixture()
        patch(f, mapOf("title" to "Must rollback", "assigneeId" to outsider.id.toString()), expected = 422)
        patch(f, mapOf("unknown" to "value"), expected = 422)
        patch(f, mapOf("status" to "INVALID"), expected = 422)
        patch(f, mapOf("title" to null), expected = 422)
        patch(f, mapOf("title" to "Future"), version = 99, expected = 422)
        assertEquals("Hello world", get(f)["title"].asString())
        assertEquals(0, get(f)["version"].asLong())
    }

    @Test
    fun nullableFieldsCanBeCleared() {
        val f = fixture(description = "description")
        patch(f, mapOf("description" to null, "dueDate" to null, "assigneeId" to null))
        assertTrue(get(f)["description"].isNull)
        assertEquals(1, get(f)["version"].asLong())
    }

    @Test
    fun differentFieldsMergeFromCommonBaseUnderEveryPolicy() {
        for (policy in listOf("LAST_WRITE_WINS", "SMART_MERGE", "ADMIN_APPROVAL")) {
            val f = fixture(policy)
            patch(f, mapOf("title" to "Server title"))
            val result = patch(f, mapOf("dueDate" to "2027-02-01"))
            assertEquals("MERGED", result["status"].asString())
            assertEquals("Server title", get(f)["title"].asString())
            assertEquals("2027-02-01", get(f)["dueDate"].asString())
            assertEquals(2, get(f)["version"].asLong())
        }
    }

    @Test
    fun lastWriteWinsUsesServerAcceptanceOrderAndAuditsAllProposals() {
        val f = fixture("LAST_WRITE_WINS")
        patch(f, mapOf("title" to "First"))
        patch(f, mapOf("title" to "Second"))
        assertEquals("Second", get(f)["title"].asString())
        val history = call("GET", "/api/tasks/${f.task}/history", admin)
        assertTrue(history.any { it["newValue"].asString() == "First" && it["operationType"].asString() == "PROPOSED" })
        assertTrue(history.any { it["newValue"].asString() == "Second" && it["operationType"].asString() == "LAST_WRITE_WINS" })
    }

    @Test
    fun smartMergeCombinesCompatibleTextEdits() {
        val f = fixture("SMART_MERGE")
        patch(f, mapOf("title" to "Hello brave world"))
        val result = patch(f, mapOf("title" to "Hello world!"))
        assertEquals("MERGED", result["status"].asString())
        assertEquals("Hello brave world!", get(f)["title"].asString())
        assertEquals(0, result["conflictIds"].size())
    }

    @Test
    fun smartMergeEscalatesAmbiguousTextAndScalars() {
        val f = fixture("SMART_MERGE", title = "abc")
        patch(f, mapOf("title" to "axc", "status" to "DONE"))
        val result = patch(f, mapOf("title" to "ayc", "status" to "IN_PROGRESS"))
        assertEquals("CONFLICT_PENDING", result["status"].asString())
        assertEquals(2, result["conflictIds"].size())
        assertEquals("axc", get(f)["title"].asString())
        assertEquals("DONE", get(f)["status"].asString())
    }

    @Test
    fun adminApprovalDoesNotMergeCompatibleText() {
        val f = fixture()
        patch(f, mapOf("title" to "Hello brave world"))
        val result = patch(f, mapOf("title" to "Hello world!"))
        assertEquals("CONFLICT_PENDING", result["status"].asString())
        assertEquals("Hello brave world", get(f)["title"].asString())
    }

    @Test
    fun activeConflictCollectsVariantsAndOtherFieldsRemainEditable() {
        val f = fixture()
        val id = pending(f)
        val result = patch(f, mapOf("title" to "third", "priority" to "HIGH"), version = 1)
        assertEquals(id.toString(), result["conflictIds"][0].asString())
        assertEquals(2, call("GET", "/api/conflicts/$id/variants", member).size())
        assertEquals("server", get(f)["title"].asString())
        assertEquals("HIGH", get(f)["priority"].asString())
        assertEquals(1, call("GET", "/api/projects/${f.project}/conflicts", admin).size())
    }

    @Test
    fun fiveVariantLimitRejectsWholeOperationAndPreservesOtherOperations() {
        val f = fixture()
        val id = pending(f)
        repeat(4) { patch(f, mapOf("title" to "variant-$it")) }
        patch(f, mapOf("title" to "overflow", "priority" to "HIGH"), expected = 409)
        assertEquals(5, call("GET", "/api/conflicts/$id/variants", admin).size())
        assertEquals("MEDIUM", get(f)["priority"].asString())
        patch(f, mapOf("priority" to "LOW"), version = 1)
        assertEquals("LOW", get(f)["priority"].asString())
    }

    @Test
    fun duplicateOperationReturnsStoredResultAndDoesNotAddVariant() {
        val f = fixture()
        val id = pending(f)
        val operationId = UUID.randomUUID()
        val first = patch(f, mapOf("title" to "repeat"), operationId = operationId)
        repeat(3) { assertEquals(first, patch(f, mapOf("title" to "repeat"), operationId = operationId)) }
        assertEquals(2, call("GET", "/api/conflicts/$id/variants", admin).size())
        patch(f, mapOf("title" to "different"), operationId = operationId, expected = 409)
    }

    @Test
    fun appliedOperationReplayReturnsOriginalVersion() {
        val f = fixture()
        val id = UUID.randomUUID()
        val first = patch(f, mapOf("title" to "changed"), operationId = id)
        patch(f, mapOf("priority" to "HIGH"), version = 1)
        assertEquals(first, patch(f, mapOf("title" to "changed"), operationId = id))
        assertEquals(2, get(f)["version"].asLong())
    }

    @Test
    fun adminResolvesVariantAndPreservesHistory() {
        val f = fixture()
        val id = pending(f)
        val variant = call("GET", "/api/conflicts/$id/variants", admin)[0]["id"].asString()
        val result = call("POST", "/api/conflicts/$id/resolutions", admin, mapOf("choice" to "VARIANT", "variantId" to variant))
        assertEquals("RESOLVED", result["status"].asString())
        assertEquals("client", get(f)["title"].asString())
        assertEquals(2, get(f)["version"].asLong())
        assertEquals(1, call("GET", "/api/conflicts/$id/variants", admin).size())
        assertTrue(call("GET", "/api/tasks/${f.task}/history", admin).any { it["operationType"].asString() == "RESOLUTION" })
        call("POST", "/api/conflicts/$id/resolutions", admin, mapOf("choice" to "CURRENT"), 409)
    }

    @Test
    fun memberAndOutsiderCannotResolveConflict() {
        val f = fixture()
        val id = pending(f)
        call("POST", "/api/conflicts/$id/resolutions", member, mapOf("choice" to "CURRENT"), 403)
        call("POST", "/api/conflicts/$id/resolutions", outsider, mapOf("choice" to "CURRENT"), 403)
        call("GET", "/api/conflicts/$id/variants", outsider, expected = 403)
    }

    @Test
    fun currentResolutionDoesNotIncrementStateVersion() {
        val f = fixture()
        val id = pending(f)
        call("POST", "/api/conflicts/$id/resolutions", admin, mapOf("choice" to "CURRENT"))
        assertEquals(1, get(f)["version"].asLong())
        assertEquals("server", get(f)["title"].asString())
    }

    @Test
    fun customResolutionValidatesAndAppliesValue() {
        val f = fixture()
        val id = pending(f)
        call("POST", "/api/conflicts/$id/resolutions", admin, mapOf("choice" to "CUSTOM", "value" to ""), 422)
        call("POST", "/api/conflicts/$id/resolutions", admin, mapOf("choice" to "CUSTOM", "value" to "final"))
        assertEquals("final", get(f)["title"].asString())
    }

    @Test
    fun batchHasIndependentTransactionsAndStoredRejections() {
        val f = fixture()
        val operations = listOf(
            MutationCommand(UUID.randomUUID(), f.task, 0, mapOf("title" to "Batch title")),
            MutationCommand(UUID.randomUUID(), f.task, 0, mapOf("status" to "INVALID")),
            MutationCommand(UUID.randomUUID(), f.task, 0, mapOf("priority" to "HIGH")),
            MutationCommand(UUID.randomUUID(), UUID.randomUUID(), 0, mapOf("title" to "missing"))
        )
        val result = call("POST", "/api/sync/operations", member, mapOf("operations" to operations))["results"]
        assertEquals(listOf("APPLIED", "REJECTED", "MERGED", "REJECTED"), result.asSequence().map { it["status"].asString() }.toList())
        assertEquals("Batch title", get(f)["title"].asString())
        assertEquals("HIGH", get(f)["priority"].asString())
        assertEquals(result, call("POST", "/api/sync/operations", member, mapOf("operations" to operations))["results"])
    }

    @Test
    fun concurrentDifferentFieldsNeverLoseUpdates() {
        val f = fixture()
        val changes = listOf(mapOf("title" to "concurrent title"), mapOf("dueDate" to "2027-03-01"), mapOf("priority" to "HIGH"))
        val results = parallel(3) { sync.mutate(member.id, MutationCommand(UUID.randomUUID(), f.task, 0, changes[it])) }
        assertTrue(results.none { it.status == MutationStatus.REJECTED || it.status == MutationStatus.CONFLICT_PENDING })
        assertEquals("concurrent title", get(f)["title"].asString())
        assertEquals("2027-03-01", get(f)["dueDate"].asString())
        assertEquals("HIGH", get(f)["priority"].asString())
        assertEquals(3, get(f)["version"].asLong())
    }

    @Test
    fun concurrentVariantsCannotExceedLimitOrCreateDuplicateConflicts() {
        val f = fixture()
        patch(f, mapOf("title" to "server"))
        val results = parallel(10) { sync.mutate(member.id, MutationCommand(UUID.randomUUID(), f.task, 0, mapOf("title" to "proposal-$it"))) }
        assertEquals(5, results.count { it.status == MutationStatus.CONFLICT_PENDING })
        assertEquals(5, results.count { it.status == MutationStatus.REJECTED && it.httpStatus == 409 })
        val conflicts = call("GET", "/api/projects/${f.project}/conflicts", admin)
        assertEquals(1, conflicts.size())
        assertEquals(5, call("GET", "/api/conflicts/${conflicts[0]["id"].asString()}/variants", admin).size())
        assertEquals("server", get(f)["title"].asString())
    }

    @Test
    fun concurrentDuplicateOperationChangesTaskExactlyOnce() {
        val f = fixture()
        val command = MutationCommand(UUID.randomUUID(), f.task, 0, mapOf("title" to "once"))
        val results = parallel(8) { sync.mutate(member.id, command) }
        assertEquals(1, results.distinct().size)
        assertEquals(1, get(f)["version"].asLong())
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_operations where operation_id = ?", Int::class.java, command.operationId))
        assertEquals(1, jdbc.queryForObject("select count(*) from task_change_history where operation_id = ? and operation_type = 'PROPOSED'", Int::class.java, command.operationId))
    }

    @Test
    fun concurrentResolutionSucceedsExactlyOnce() {
        val f = fixture()
        val id = pending(f)
        val statuses = parallel(2) {
            val request = MockMvcRequestBuilders.post("/api/conflicts/$id/resolutions").header("Authorization", "Bearer ${admin.token}")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("choice" to "CUSTOM", "value" to "final-$it")))
            mvc.perform(request).andReturn().response.status
        }
        assertEquals(listOf(200, 409), statuses.sorted())
        assertEquals(2, get(f)["version"].asLong())
    }

    @Test
    fun unchangedStaleFieldsDoNotUndoServerChanges() {
        val f = fixture("LAST_WRITE_WINS")
        patch(f, mapOf("title" to "server"))
        patch(f, mapOf("title" to "Hello world", "priority" to "HIGH"))
        assertEquals("server", get(f)["title"].asString())
        assertEquals("HIGH", get(f)["priority"].asString())
    }

    @Test
    fun baseValuesAreReconstructedAcrossSeveralVersions() {
        val f = fixture("SMART_MERGE", description = "abcdef")
        patch(f, mapOf("description" to "aXcdef"))
        patch(f, mapOf("priority" to "HIGH"), version = 1)
        patch(f, mapOf("description" to "abcdeY"))
        assertEquals("aXcdeY", get(f)["description"].asString())
    }

    @Test
    fun concurrentLastWriteWinsMatchesPersistedAcceptanceOrder() {
        val f = fixture("LAST_WRITE_WINS")
        val results = parallel(8) { sync.mutate(member.id, MutationCommand(UUID.randomUUID(), f.task, 0, mapOf("title" to "title-$it"))) }
        assertTrue(results.none { it.status == MutationStatus.REJECTED })
        val last = jdbc.queryForObject("select new_value from task_change_history where task_id = ? and affects_state and field_name = 'title' order by operation_order desc limit 1", String::class.java, f.task)
        assertEquals(last, get(f)["title"].asString())
        assertEquals(8, get(f)["version"].asLong())
    }

    @Test
    fun batchContinuesAfterVariantLimitRejection() {
        val f = fixture()
        val id = pending(f)
        repeat(4) { patch(f, mapOf("title" to "proposal-$it")) }
        val operations = listOf(
            MutationCommand(UUID.randomUUID(), f.task, 1, mapOf("title" to "overflow", "priority" to "HIGH")),
            MutationCommand(UUID.randomUUID(), f.task, 1, mapOf("dueDate" to "2027-01-01"))
        )
        val result = call("POST", "/api/sync/operations", member, mapOf("operations" to operations))["results"]
        assertEquals("VARIANT_LIMIT", result[0]["error"]["code"].asString())
        assertEquals("APPLIED", result[1]["status"].asString())
        assertEquals(5, call("GET", "/api/conflicts/$id/variants", admin).size())
        assertEquals("MEDIUM", get(f)["priority"].asString())
        assertEquals("2027-01-01", get(f)["dueDate"].asString())
    }

    @Test
    fun operationIdCannotBeReusedByAnotherUser() {
        val f = fixture()
        val id = UUID.randomUUID()
        patch(f, mapOf("title" to "member change"), operationId = id)
        patch(f, mapOf("title" to "member change"), operationId = id, actor = admin, expected = 409)
        assertEquals(1, get(f)["version"].asLong())
    }

    @Test
    fun duplicateDoesNotConsumeVariantCapacityEvenWhenFull() {
        val f = fixture()
        val id = pending(f)
        val operationId = UUID.randomUUID()
        val first = patch(f, mapOf("title" to "repeat"), operationId = operationId)
        repeat(3) { patch(f, mapOf("title" to "proposal-$it")) }
        assertEquals(first, patch(f, mapOf("title" to "repeat"), operationId = operationId))
        assertEquals(5, call("GET", "/api/conflicts/$id/variants", admin).size())
    }

    @Test
    fun sameProposedValueDoesNotCreateConflictOrNewVersion() {
        val f = fixture()
        patch(f, mapOf("title" to "same"))
        val result = patch(f, mapOf("title" to "same"))
        assertEquals("MERGED", result["status"].asString())
        assertEquals(0, result["conflictIds"].size())
        assertEquals(1, get(f)["version"].asLong())
    }

    @Test
    fun resolvedConflictAllowsNewIndependentConflictForSameField() {
        val f = fixture()
        val firstId = pending(f)
        call("POST", "/api/conflicts/$firstId/resolutions", admin, mapOf("choice" to "CUSTOM", "value" to "resolved"))
        val result = patch(f, mapOf("title" to "new proposal"))
        val secondId = result["conflictIds"][0].asString()
        assertNotEquals(firstId.toString(), secondId)
        assertEquals(2, call("GET", "/api/projects/${f.project}/conflicts", admin).size())
        assertEquals(1, call("GET", "/api/projects/${f.project}/conflicts?status=PENDING", admin).size())
    }
}
