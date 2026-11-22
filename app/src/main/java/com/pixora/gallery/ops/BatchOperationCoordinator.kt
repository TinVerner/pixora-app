package com.pixora.gallery.ops

data class NoteDraft(
    val mediaId: Long,
    val category: String,
    val title: String,
    val year: Int?,
    val body: String,
    val sourceText: String,
    val tags: String,
    val dedupKey: String,
    val allowDuplicate: Boolean,
)

data class ReviewCommitItem(
    val mediaId: Long,
    val uri: String,
    val save: Boolean,
    val trash: Boolean,
    val discardWithoutSaveConfirmed: Boolean,
    val draft: NoteDraft?,
)

data class WriteResult(
    val ids: List<Long>,
    val savedMediaIds: List<Long>,
    val duplicateCount: Int,
)

interface NoteWriter {
    suspend fun commit(drafts: List<NoteDraft>): WriteResult
    suspend fun allExist(ids: List<Long>): Boolean
}

sealed interface CommitResult {
    data class Failed(val message: String) : CommitResult
    data class NeedDiscardConfirmation(val count: Int) : CommitResult
    data class Ready(
        val savedIds: List<Long>,
        val trashUris: List<String>,
        val duplicateCount: Int,
    ) : CommitResult
}

class BatchOperationCoordinator(
    private val notes: NoteWriter,
) {
    suspend fun commit(items: List<ReviewCommitItem>): CommitResult {
        val unsafe = items.count { it.trash && !it.save && !it.discardWithoutSaveConfirmed }
        if (unsafe > 0) return CommitResult.NeedDiscardConfirmation(unsafe)
        val drafts = ArrayList<NoteDraft>(items.size)
        for (item in items) {
            if (!item.save) continue
            val draft = item.draft ?: return CommitResult.Failed("missing-draft")
            drafts += draft
        }
        val written = try {
            notes.commit(drafts)
        } catch (error: Exception) {
            return CommitResult.Failed(error.message ?: "save-failed")
        }
        if (!notes.allExist(written.ids)) {
            return CommitResult.Failed("verify-failed")
        }
        val savedMedia = written.savedMediaIds.toSet()
        val trash = items.mapNotNull { item ->
            val allowed = item.trash && (item.mediaId in savedMedia || (!item.save && item.discardWithoutSaveConfirmed))
            if (allowed) item.uri else null
        }
        return CommitResult.Ready(
            savedIds = written.ids,
            trashUris = trash,
            duplicateCount = written.duplicateCount,
        )
    }
}
