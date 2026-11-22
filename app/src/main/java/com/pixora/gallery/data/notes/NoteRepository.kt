package com.pixora.gallery.data.notes

import androidx.room.withTransaction
import com.pixora.gallery.data.db.NoteEntity
import com.pixora.gallery.data.db.PixoraDatabase
import com.pixora.gallery.classification.ContentCategory
import com.pixora.gallery.notes.NoteDeduplicator
import com.pixora.gallery.notes.NoteTransfer
import com.pixora.gallery.notes.NotesBackup
import com.pixora.gallery.notes.NotesCodec
import com.pixora.gallery.ops.NoteDraft
import com.pixora.gallery.ops.NoteWriter
import com.pixora.gallery.ops.WriteResult
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

class NoteRepository(
    private val db: PixoraDatabase,
) : NoteWriter {
    private val dao = db.notes()

    fun observe(): Flow<List<NoteEntity>> = dao.observe()

    fun observeOne(id: Long): Flow<NoteEntity?> = dao.observeOne(id)

    suspend fun titles(category: String): List<String> = dao.titles(category)

    suspend fun sourceMediaIds(): Set<Long> = dao.sourceMediaIds().toSet()

    override suspend fun commit(drafts: List<NoteDraft>): WriteResult = db.withTransaction {
        val ids = ArrayList<Long>(drafts.size)
        val media = ArrayList<Long>(drafts.size)
        var duplicates = 0
        val now = System.currentTimeMillis()
        drafts.forEach { draft ->
            val title = draft.title.trim()
            val category = ContentCategory.fromName(draft.category)
            if (NoteDeduplicator.isCategoryLabel(title)) return@forEach
            if (title.isBlank() && category.watchable) return@forEach
            val existing = if (draft.dedupKey.isNotBlank()) dao.idByKey(draft.dedupKey) else null
            if (existing != null && !draft.allowDuplicate) {
                ids += existing
                media += draft.mediaId
                duplicates++
                return@forEach
            }
            val id = dao.insert(
                NoteEntity(
                    category = draft.category,
                    title = title,
                    year = draft.year,
                    body = draft.body,
                    sourceText = draft.sourceText,
                    sourceMediaId = draft.mediaId,
                    sourceLabel = "screenshot",
                    watched = false,
                    tags = draft.tags,
                    dedupKey = draft.dedupKey,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            ids += id
            media += draft.mediaId
        }
        val found = if (ids.isEmpty()) 0 else dao.countIds(ids)
        if (found != ids.size) error("verify-failed")
        if (media.isNotEmpty()) {
            db.analyses().setStatus(media.distinct(), "SAVED", now)
        }
        WriteResult(ids, media, duplicates)
    }

    override suspend fun allExist(ids: List<Long>): Boolean {
        if (ids.isEmpty()) return true
        return dao.countIds(ids.distinct()) == ids.distinct().size
    }

    suspend fun update(note: NoteEntity) {
        dao.update(note.copy(updatedAt = System.currentTimeMillis(), dedupKey = NoteDeduplicator.dedupKey(note.category, note.title, note.year)))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clear() = dao.clear()

    suspend fun dropCategoryLabels() {
        val ids = dao.all()
            .filter { NoteDeduplicator.isCategoryLabel(it.title) }
            .map { it.id }
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.forEach { dao.delete(it) }
        }
    }

    suspend fun exportBackup(): NotesBackup {
        val notes = dao.all().map { it.toTransfer() }
        return NotesBackup(version = 1, exportedAt = System.currentTimeMillis(), notes = notes)
    }

    suspend fun writeExport(stream: OutputStream, format: String) {
        val notes = dao.all().map { it.toTransfer() }
        val text = when (format) {
            "csv" -> NotesCodec.toCsv(notes)
            "md" -> NotesCodec.toMarkdown(notes)
            "txt" -> NotesCodec.toText(notes)
            else -> NotesCodec.toJson(NotesBackup(1, System.currentTimeMillis(), notes))
        }
        stream.bufferedWriter().use { it.write(text) }
    }

    suspend fun importStream(stream: InputStream): Pair<Int, Int> {
        val text = stream.bufferedReader().use { it.readText() }
        val backup = NotesCodec.fromJson(text)
        if (backup.version != 1) error("backup-version")
        var added = 0
        var skipped = 0
        db.withTransaction {
            backup.notes.forEach { item ->
                val key = item.dedupKey.ifBlank { NoteDeduplicator.dedupKey(item.category, item.title, item.year) }
                if (key.isNotBlank() && dao.idByKey(key) != null) {
                    skipped++
                    return@forEach
                }
                val now = System.currentTimeMillis()
                dao.insert(
                    NoteEntity(
                        category = item.category,
                        title = item.title,
                        year = item.year,
                        body = item.body,
                        sourceText = item.sourceText,
                        sourceMediaId = null,
                        sourceLabel = "import",
                        watched = item.watched,
                        tags = item.tags,
                        dedupKey = key,
                        createdAt = item.createdAt.takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    ),
                )
                added++
            }
        }
        return added to skipped
    }

    private fun NoteEntity.toTransfer() = NoteTransfer(
        category = category,
        title = title,
        year = year,
        body = body,
        sourceText = sourceText,
        tags = tags,
        watched = watched,
        dedupKey = dedupKey,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
