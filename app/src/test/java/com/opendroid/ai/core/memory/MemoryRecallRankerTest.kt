package com.opendroid.ai.core.memory

import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryRecallRankerTest {
    @Test
    fun ranksExactKeyAndLexicalOverlapAheadOfWeakMatch() {
        val now = 1_800_000_000_000L
        val exact = Memory("favorite_editor", "Uses Android Studio", MemoryType.SEMANTIC, now - 86_400_000)
        val partial = Memory("editor_note", "Has a note about editors", MemoryType.EPISODIC, now)
        val ranked = MemoryRecallRanker.rank("favorite editor", listOf(partial, exact), now)
        assertEquals(exact, ranked.first())
    }

    @Test
    fun emptyOrUnrelatedQueryReturnsNoMemories() {
        val memory = Memory("work", "Builds Android apps", MemoryType.SEMANTIC)
        assertEquals(emptyList<Memory>(), MemoryRecallRanker.rank("   ", listOf(memory)))
        assertEquals(emptyList<Memory>(), MemoryRecallRanker.rank("solar system", listOf(memory)))
    }
}
