package com.opendroid.ai.core.memory

import com.opendroid.ai.data.models.Memory
import java.util.Locale
import kotlin.math.min

/**
 * Lightweight on-device retrieval ranking. Avoids a separate embedding model/runtime until
 * measured against lexical retrieval on representative user data.
 *
 * Score combines normalized lexical overlap, exact phrase/key matches and recency. This is a
 * retrieval aid, not a permission gate: memories may contain stale or untrusted content.
 */
object MemoryRecallRanker {
    fun rank(query: String, memories: List<Memory>, nowMillis: Long = System.currentTimeMillis()): List<Memory> {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return emptyList()
        return memories.mapNotNull { memory ->
            val keyTokens = tokens(memory.key)
            val valueTokens = tokens(memory.value)
            val allTokens = (keyTokens + valueTokens).toSet()
            val overlap = queryTokens.count { it in allTokens }.toDouble() / queryTokens.size
            val phraseBoost = when {
                memory.key.contains(query, ignoreCase = true) -> 0.45
                memory.value.contains(query, ignoreCase = true) -> 0.25
                else -> 0.0
            }
            val ageMillis = (nowMillis - memory.timestamp).coerceAtLeast(0L)
            val ageDays = ageMillis / 86_400_000.0
            val recencyBoost = 0.15 * (1.0 / (1.0 + ageDays / 30.0))
            val score = overlap * 0.7 + phraseBoost + recencyBoost
            if (overlap > 0 || phraseBoost > 0) memory to score else null
        }.sortedWith(
            compareByDescending<Pair<Memory, Double>> { it.second }
                .thenByDescending { it.first.timestamp }
                .thenBy { it.first.key.lowercase(Locale.ROOT) }
        ).map { it.first }
    }

    private fun tokens(text: String): Set<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .filter { it.length >= 2 }
            .take(256)
            .toSet()
}
