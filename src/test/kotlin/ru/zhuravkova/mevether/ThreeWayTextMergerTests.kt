package ru.zhuravkova.mevether

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.zhuravkova.mevether.sync.domain.*
import ru.zhuravkova.mevether.task.domain.TaskFields
import ru.zhuravkova.mevether.common.BusinessException

class ThreeWayTextMergerTests {
    private val merger = ThreeWayTextMerger()

    @Test
    fun mergesNonOverlappingEdits() {
        assertEquals("Hello brave world!", merger.merge("Hello world", "Hello brave world", "Hello world!"))
    }

    @Test
    fun mergesSeveralIndependentEdits() {
        assertEquals("aXcdeYghiZ", merger.merge("abcdefghij", "aXcdefghiZ", "abcdeYghij"))
    }

    @Test
    fun rejectsOverlappingReplacements() {
        assertNull(merger.merge("abc", "axc", "ayc"))
    }

    @Test
    fun rejectsCompetingInsertionsAtSamePosition() {
        assertNull(merger.merge("ab", "aXb", "aYb"))
    }

    @Test
    fun acceptsIdenticalEditsOnce() {
        assertEquals("axc", merger.merge("abc", "axc", "axc"))
    }

    @Test
    fun unchangedProposalPreservesServerValue() {
        assertEquals("server", merger.merge("base", "server", "base"))
    }

    @Test
    fun unchangedServerAcceptsProposal() {
        assertEquals("client", merger.merge("base", "base", "client"))
    }

    @Test
    fun mergesDeletionWithDistantInsertion() {
        assertEquals("acde!", merger.merge("abcde", "acde", "abcde!"))
    }

    @Test
    fun largeInputsUseBoundedFallback() {
        val base = "a".repeat(6000)
        assertNull(merger.merge(base, "b".repeat(6000), "c".repeat(6000)))
    }

    @Test
    fun smartMergeRequiresApprovalForScalarAndNullableValues() {
        val strategy = SmartMergeStrategy()
        assertEquals(FieldResolution.RequiresApproval, strategy.resolve("status", "TODO", "DONE", "IN_PROGRESS"))
        assertEquals(FieldResolution.RequiresApproval, strategy.resolve("description", null, "a", "b"))
    }

    @Test
    fun adminApprovalNeverMergesText() {
        assertEquals(FieldResolution.RequiresApproval, AdminApprovalStrategy().resolve("title", "Hello world", "Hello brave world", "Hello world!"))
    }

    @Test
    fun lastWriteWinsAcceptsIncomingProposal() {
        assertEquals(FieldResolution.Accepted("new"), LastWriteWinsStrategy().resolve("title", "base", "current", "new"))
    }

    @Test
    fun validatesTaskFieldTypesAndNames() {
        assertThrows(BusinessException::class.java) { TaskFields.normalize(mapOf("version" to 99)) }
        assertThrows(BusinessException::class.java) { TaskFields.normalize(mapOf("title" to null)) }
        assertThrows(BusinessException::class.java) { TaskFields.normalize(mapOf("status" to "UNKNOWN")) }
        assertThrows(BusinessException::class.java) { TaskFields.normalize(mapOf("dueDate" to "tomorrow")) }
        assertThrows(BusinessException::class.java) { TaskFields.normalize(mapOf("priority" to 1)) }
        assertEquals(mapOf("description" to null), TaskFields.normalize(mapOf("description" to null)))
    }

    @Test
    fun mergesUnicodeWithoutSplittingSurrogatePairs() {
        assertEquals("Привет 🚀 мир!", merger.merge("Привет мир", "Привет 🚀 мир", "Привет мир!"))
        val base = String(Character.toChars(0x1F600))
        val server = String(Character.toChars(0x1FA00))
        val client = String(Character.toChars(0x1F601))
        assertNull(merger.merge(base, server, client))
    }
}
