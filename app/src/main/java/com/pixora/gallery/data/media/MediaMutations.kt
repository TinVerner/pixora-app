package com.pixora.gallery.data.media

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore

class TrashGateway(
    private val resolver: ContentResolver,
) {
    fun trashRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createTrashRequest(resolver, uris, true)
}

sealed interface PathUpdate {
    data object Updated : PathUpdate
    data class Failed(val message: String) : PathUpdate
}

class PhysicalAlbumGateway(
    private val resolver: ContentResolver,
) {
    fun relativePath(albumName: String): String {
        val clean = albumName.trim()
            .replace(Regex("[\\\\/]+"), " ")
            .replace(Regex("[^\\p{L}\\p{N} ._\\-]"), "")
            .trim()
            .take(48)
            .ifBlank { "Pixora" }
        return "Pictures/Pixora/$clean/"
    }

    fun writeRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createWriteRequest(resolver, uris)

    fun updatePath(uri: Uri, relativePath: String): PathUpdate {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        return try {
            val updated = resolver.update(uri, values, null, null)
            if (updated > 0) PathUpdate.Updated else PathUpdate.Failed("unchanged")
        } catch (error: Exception) {
            PathUpdate.Failed(error.message ?: "update-failed")
        }
    }
}
