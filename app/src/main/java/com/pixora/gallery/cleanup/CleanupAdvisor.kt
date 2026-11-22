package com.pixora.gallery.cleanup

import com.pixora.gallery.duplicates.ExactGroup
import com.pixora.gallery.duplicates.QualityScorer
import com.pixora.gallery.duplicates.SimilarGroup

enum class CleanupReason {
    EXACT_DUPLICATE,
    SIMILAR_GROUP,
    BLURRY,
    OLD_SCREENSHOT,
    ALREADY_SAVED,
}

data class PhotoFact(
    val id: Long,
    val sizeBytes: Long,
    val sharpness: Double?,
    val screenshot: Boolean,
    val favorite: Boolean,
    val bucket: String,
    val dateModifiedMillis: Long,
)

data class Suggestion(
    val id: String,
    val reason: CleanupReason,
    val mediaIds: List<Long>,
    val keepIds: List<Long>,
    val reclaimBytes: Long,
)

object CleanupAdvisor {
    fun advise(
        photos: List<PhotoFact>,
        exact: List<ExactGroup>,
        similar: List<SimilarGroup>,
        savedMediaIds: Set<Long>,
        excludedIds: Set<Long>,
        protectedBuckets: Set<String>,
        nowMillis: Long,
        oldScreenshotDays: Int,
    ): List<Suggestion> {
        val byId = photos.associateBy { it.id }
        fun blocked(id: Long): Boolean {
            val photo = byId[id] ?: return true
            return photo.favorite || id in excludedIds || photo.bucket in protectedBuckets
        }

        val suggestions = mutableListOf<Suggestion>()
        val used = mutableSetOf<Long>()

        exact.forEach { group ->
            val keep = group.keepId
            val deleteIds = group.deleteIds.filter { !blocked(it) }
            if (deleteIds.isEmpty()) return@forEach
            suggestions += Suggestion(
                id = "exact-${group.hash.take(12)}",
                reason = CleanupReason.EXACT_DUPLICATE,
                mediaIds = deleteIds,
                keepIds = listOf(keep),
                reclaimBytes = deleteIds.sumOf { byId[it]?.sizeBytes ?: 0L },
            )
            used += deleteIds
            used += keep
        }

        similar.forEachIndexed { index, group ->
            val keep = group.keepIds.filter { byId[it] != null }
            val deleteIds = group.ids.filter { it !in keep && !blocked(it) }
            if (deleteIds.isEmpty()) return@forEachIndexed
            suggestions += Suggestion(
                id = "similar-$index-${group.ids.minOrNull()}",
                reason = CleanupReason.SIMILAR_GROUP,
                mediaIds = deleteIds,
                keepIds = keep,
                reclaimBytes = deleteIds.sumOf { byId[it]?.sizeBytes ?: 0L },
            )
            used += group.ids
        }

        val blurry = photos.filter { photo ->
            photo.id !in used &&
                !photo.screenshot &&
                !blocked(photo.id) &&
                QualityScorer.isProbablyBlurry(photo.sharpness)
        }
        if (blurry.isNotEmpty()) {
            suggestions += Suggestion(
                id = "blurry",
                reason = CleanupReason.BLURRY,
                mediaIds = blurry.map { it.id },
                keepIds = emptyList(),
                reclaimBytes = blurry.sumOf { it.sizeBytes },
            )
            used += blurry.map { it.id }
        }

        val saved = photos.filter { it.screenshot && it.id in savedMediaIds && it.id !in used && !blocked(it.id) }
        if (saved.isNotEmpty()) {
            suggestions += Suggestion(
                id = "saved-shots",
                reason = CleanupReason.ALREADY_SAVED,
                mediaIds = saved.map { it.id },
                keepIds = emptyList(),
                reclaimBytes = saved.sumOf { it.sizeBytes },
            )
            used += saved.map { it.id }
        }

        val cutoff = nowMillis - oldScreenshotDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        val old = photos.filter { it.screenshot && it.dateModifiedMillis in 1 until cutoff && it.id !in used && !blocked(it.id) }
        if (old.isNotEmpty()) {
            suggestions += Suggestion(
                id = "old-shots",
                reason = CleanupReason.OLD_SCREENSHOT,
                mediaIds = old.map { it.id },
                keepIds = emptyList(),
                reclaimBytes = old.sumOf { it.sizeBytes },
            )
        }
        return suggestions.sortedByDescending { it.reclaimBytes }
    }
}
