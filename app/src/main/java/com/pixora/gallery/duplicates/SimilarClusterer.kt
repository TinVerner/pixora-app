package com.pixora.gallery.duplicates

data class VisualItem(
    val id: Long,
    val hash: Long,
    val timeMillis: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val quality: Double,
    val sharpness: Double,
)

data class SimilarGroup(
    val ids: List<Long>,
    val keepIds: List<Long>,
    val scores: Map<Long, Double>,
)

object SimilarClusterer {
    fun candidatePairs(items: List<VisualItem>, burstMillis: Long, window: Int = 25): List<Pair<Long, Long>> {
        val pairs = LinkedHashSet<Pair<Long, Long>>()
        val byTime = items.sortedBy { it.timeMillis }
        for (i in byTime.indices) {
            var j = i + 1
            var steps = 0
            while (j < byTime.size && steps < window && byTime[j].timeMillis - byTime[i].timeMillis <= burstMillis) {
                pairs += ordered(byTime[i].id, byTime[j].id)
                j++
                steps++
            }
        }
        items.groupBy { it.width to it.height }.values.forEach { bucket ->
            if (bucket.size < 2 || bucket.size > 24) return@forEach
            for (a in bucket.indices) {
                for (b in a + 1 until bucket.size) {
                    val left = bucket[a]
                    val right = bucket[b]
                    val max = maxOf(left.sizeBytes, right.sizeBytes).coerceAtLeast(1L)
                    val min = minOf(left.sizeBytes, right.sizeBytes)
                    if (min.toDouble() / max >= 0.45) {
                        pairs += ordered(left.id, right.id)
                    }
                }
            }
        }
        return pairs.toList()
    }

    fun cluster(
        items: List<VisualItem>,
        pairs: List<Pair<Long, Long>>,
        threshold: Int,
        strict: Int,
        burstMillis: Long,
    ): List<SimilarGroup> {
        if (items.isEmpty()) return emptyList()
        val byId = items.associateBy { it.id }
        val parent = HashMap<Long, Long>()
        fun find(id: Long): Long {
            var current = id
            while (parent.getOrDefault(current, current) != current) {
                current = parent.getValue(current)
            }
            var cursor = id
            while (parent.getOrDefault(cursor, cursor) != current) {
                val next = parent.getValue(cursor)
                parent[cursor] = current
                cursor = next
            }
            return current
        }
        fun unite(a: Long, b: Long) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        items.forEach { parent.putIfAbsent(it.id, it.id) }
        pairs.forEach { (leftId, rightId) ->
            val left = byId[leftId] ?: return@forEach
            val right = byId[rightId] ?: return@forEach
            val distance = DHash.hamming(left.hash, right.hash)
            val closeInTime = kotlin.math.abs(left.timeMillis - right.timeMillis) <= burstMillis
            val link = distance <= strict || (distance <= threshold && closeInTime)
            if (link) unite(left.id, right.id)
        }
        return parent.keys
            .groupBy { find(it) }
            .values
            .filter { it.size > 1 }
            .map { ids ->
                val members = ids.mapNotNull { byId[it] }
                val scores = members.associate { it.id to it.quality }
                val ranked = members.sortedByDescending { it.quality }
                val keep = recommendKeep(ranked)
                SimilarGroup(
                    ids = ranked.map { it.id },
                    keepIds = keep,
                    scores = scores,
                )
            }
            .sortedByDescending { it.ids.size }
    }

    fun recommendKeep(ranked: List<VisualItem>): List<Long> {
        if (ranked.isEmpty()) return emptyList()
        if (ranked.size == 1) return listOf(ranked.first().id)
        val best = ranked[0].quality
        val second = ranked[1].quality
        val close = best - second < 0.08
        return if (ranked.size >= 5 && close) ranked.take(2).map { it.id } else listOf(ranked.first().id)
    }

    private fun ordered(a: Long, b: Long): Pair<Long, Long> = if (a < b) a to b else b to a
}
