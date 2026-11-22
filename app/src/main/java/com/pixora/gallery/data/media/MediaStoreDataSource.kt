package com.pixora.gallery.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.pixora.gallery.core.AppLog
import com.pixora.gallery.media.ScreenshotDetector
import java.io.InputStream
import java.security.MessageDigest

enum class AccessLevel { NONE, PARTIAL, FULL }

data class MediaRecord(
    val id: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val bucketName: String,
    val mimeType: String,
    val dateAdded: Long,
    val dateModified: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val isScreenshot: Boolean,
    val isFavorite: Boolean,
    val isVideo: Boolean,
)

class MediaStoreDataSource(
    context: Context,
) {
    private val resolver = context.applicationContext.contentResolver
    private val appContext = context.applicationContext

    fun access(): AccessLevel {
        val fullImages = granted(android.Manifest.permission.READ_MEDIA_IMAGES)
        val fullVideo = granted(android.Manifest.permission.READ_MEDIA_VIDEO)
        val legacy = granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (fullImages || fullVideo || legacy) return AccessLevel.FULL
        if (Build.VERSION.SDK_INT >= 34 && granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
            return AccessLevel.PARTIAL
        }
        return AccessLevel.NONE
    }

    fun loadAll(): List<MediaRecord> {
        if (access() == AccessLevel.NONE) return emptyList()
        val images = query(imagesUri(), video = false)
        val videos = query(videosUri(), video = true)
        return images + videos
    }

    fun thumbnail(uri: Uri, size: Int = 96): Bitmap? {
        return try {
            resolver.loadThumbnail(uri, Size(size, size), null)
        } catch (error: Exception) {
            AppLog.e("thumbnail failed $uri", error)
            decodeSampled(uri, size)
        }
    }

    fun decodeSampled(uri: Uri, maxEdge: Int): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(resolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val edge = maxOf(width, height)
                if (edge > maxEdge && edge > 0) {
                    val scale = maxEdge.toDouble() / edge
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
            if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, false)?.also { decoded.recycle() } ?: decoded
            }
        } catch (error: Exception) {
            AppLog.e("decode failed $uri", error)
            null
        }
    }

    fun sha256(uri: Uri): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        resolver.openInputStream(uri)?.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return null
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun open(uri: Uri): InputStream? = resolver.openInputStream(uri)

    fun isTrashedOrMissing(uri: Uri, video: Boolean): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns.IS_TRASHED)
        return try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return true
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                index >= 0 && cursor.getInt(index) == 1
            } ?: true
        } catch (error: Exception) {
            AppLog.e("trash check failed $uri video=$video", error)
            true
        }
    }

    private fun query(collection: Uri, video: Boolean): List<MediaRecord> {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.IS_TRASHED,
        )
        val selection = "${MediaStore.MediaColumns.IS_TRASHED}=0"
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val result = ArrayList<MediaRecord>(256)
        try {
            resolver.query(collection, projection.toTypedArray(), selection, null, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val favCol = cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol).orEmpty()
                    val path = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                    val bucket = if (bucketCol >= 0) cursor.getString(bucketCol).orEmpty() else ""
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    result += MediaRecord(
                        id = id,
                        uri = contentUri.toString(),
                        displayName = name,
                        relativePath = path,
                        bucketName = bucket,
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else "",
                        dateAdded = cursor.getLong(addedCol),
                        dateModified = cursor.getLong(modifiedCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                        isScreenshot = !video && ScreenshotDetector.isScreenshot(path, name, bucket),
                        isFavorite = favCol >= 0 && cursor.getInt(favCol) == 1,
                        isVideo = video,
                    )
                }
            }
        } catch (error: SecurityException) {
            AppLog.e("mediastore query denied", error)
        }
        return result
    }

    private fun imagesUri(): Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun videosUri(): Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
