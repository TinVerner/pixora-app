package com.pixora.gallery.notes

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NoteTransfer(
    val category: String,
    val title: String,
    val year: Int? = null,
    val body: String = "",
    val sourceText: String = "",
    val tags: String = "",
    val watched: Boolean = false,
    val dedupKey: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class NotesBackup(
    val version: Int = 1,
    val exportedAt: Long,
    val notes: List<NoteTransfer>,
)

object NotesCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun toJson(backup: NotesBackup): String = json.encodeToString(backup)

    fun fromJson(text: String): NotesBackup = json.decodeFromString(text)

    fun toCsv(notes: List<NoteTransfer>): String = buildString {
        appendLine("category,title,year,watched,tags,body,sourceText")
        notes.forEach { note ->
            appendLine(
                listOf(
                    note.category,
                    note.title,
                    note.year?.toString().orEmpty(),
                    note.watched.toString(),
                    note.tags,
                    note.body,
                    note.sourceText,
                ).joinToString(",") { csv(it) },
            )
        }
    }

    fun toMarkdown(notes: List<NoteTransfer>): String = buildString {
        appendLine("# Pixora")
        notes.forEach { note ->
            appendLine()
            append("## ")
            appendLine(note.title.ifBlank { note.category })
            append("- Категория: ")
            appendLine(note.category)
            note.year?.let { append("- Год: ").appendLine(it) }
            if (note.watched) appendLine("- Просмотрено")
            if (note.tags.isNotBlank()) append("- Теги: ").appendLine(note.tags)
            if (note.body.isNotBlank()) {
                appendLine()
                appendLine(note.body)
            }
            if (note.sourceText.isNotBlank() && note.sourceText != note.body) {
                appendLine()
                appendLine("```")
                appendLine(note.sourceText)
                appendLine("```")
            }
        }
    }

    fun toText(notes: List<NoteTransfer>): String = notes.joinToString(separator = "\n\n----\n\n") { note ->
        buildString {
            appendLine(note.title.ifBlank { note.category })
            appendLine(note.category)
            note.year?.let { appendLine(it) }
            if (note.tags.isNotBlank()) appendLine(note.tags)
            if (note.body.isNotBlank()) appendLine(note.body)
            if (note.sourceText.isNotBlank()) {
                appendLine()
                appendLine(note.sourceText)
            }
        }.trim()
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
