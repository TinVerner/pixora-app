package com.pixora.gallery.duplicates

data class HashedFile(
    val id: Long,
    val sha256: String,
    val sizeBytes: Long,
    val favorite: Boolean,
    val excluded: Boolean,
)

data class ExactGroup(
    val hash: String,
    val ids: List<Long>,
    val keepId: Long,
    val deleteIds: List<Long>,
    val reclaimBytes: Long,
)

object DuplicateFinder {
    fun group(files: List<HashedFile>): List<ExactGroup> {
        return files
            .groupBy { it.sha256 }
            .values
            .filter { it.size > 1 }
            .map { group ->
                val keep = group.firstOrNull { it.favorite } ?: group.maxBy { it.sizeBytes }
                val deletable = group.filter { it.id != keep.id && !it.favorite && !it.excluded }
                ExactGroup(
                    hash = group.first().sha256,
                    ids = group.map { it.id },
                    keepId = keep.id,
                    deleteIds = deletable.map { it.id },
                    reclaimBytes = deletable.sumOf { it.sizeBytes },
                )
            }
            .filter { it.deleteIds.isNotEmpty() }
            .sortedByDescending { it.reclaimBytes }
    }
}
