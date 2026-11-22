package com.pixora.gallery.notes

import com.pixora.gallery.classification.HeuristicContentInterpreter
import kotlin.math.max

object NoteDeduplicator {
    fun dedupKey(category: String, title: String?, year: Int?): String {
        val normalized = HeuristicContentInterpreter.normalizeKey(title.orEmpty())
        if (normalized.length < 2) return ""
        return "${category.lowercase()}|$normalized|${year ?: ""}"
    }

    fun similarity(left: String, right: String): Double {
        val a = HeuristicContentInterpreter.normalizeKey(left)
        val b = HeuristicContentInterpreter.normalizeKey(right)
        if (a == b) return if (a.isEmpty()) 0.0 else 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / max(a.length, b.length)
    }

    fun looksDuplicate(title: String, others: List<String>, threshold: Double = 0.88): Boolean {
        val key = HeuristicContentInterpreter.normalizeKey(title)
        if (key.length < 3) return false
        return others.any { similarity(key, it) >= threshold }
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            for (j in prev.indices) prev[j] = cur[j]
        }
        return prev[b.length]
    }
}
