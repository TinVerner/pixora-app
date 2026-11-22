package com.pixora.gallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_items",
    indices = [
        Index("sha256"),
        Index("isScreenshot"),
        Index("bucketName"),
    ],
)
data class MediaItemEntity(
    @PrimaryKey val id: Long,
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
    val sha256: String? = null,
    val dHash: Long? = null,
    val sharpness: Double? = null,
    val exposureScore: Double? = null,
    val qualityScore: Double? = null,
    val histogram: String? = null,
    val labels: String = "",
    val rawLabel: String? = null,
    val rawLabelScore: Float? = null,
)

@Entity(tableName = "ocr_cache")
data class OcrCacheEntity(
    @PrimaryKey val mediaId: Long,
    val dateModified: Long,
    val sizeBytes: Long,
    val text: String,
    val meanConfidence: Int,
    val engine: String,
    val processedAt: Long,
)

@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey val mediaId: Long,
    val category: String,
    val title: String?,
    val year: Int?,
    val fieldsJson: String,
    val candidatesJson: String,
    val confidence: Int,
    val explainCode: String,
    val fullText: String,
    val ocrConfidence: Int,
    val status: String,
    val userTitle: String?,
    val userCategory: String?,
    val userYear: Int?,
    val userEdited: Boolean,
    val saveChecked: Boolean,
    val trashChecked: Boolean,
    val allowDuplicate: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "notes",
    indices = [Index("dedupKey"), Index("category")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val title: String,
    val year: Int?,
    val body: String,
    val sourceText: String,
    val sourceMediaId: Long?,
    val sourceLabel: String,
    val watched: Boolean,
    val tags: String,
    val dedupKey: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "exclusions")
data class ExclusionEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val createdAt: Long,
)

@Entity(tableName = "smart_albums")
data class SmartAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "album_items", primaryKeys = ["albumId", "mediaId"])
data class AlbumItemEntity(
    val albumId: Long,
    val mediaId: Long,
    val similarity: Float,
    val state: String,
    val updatedAt: Long,
)

@Entity(tableName = "exemplars", primaryKeys = ["albumId", "mediaId"])
data class ExemplarEntity(
    val albumId: Long,
    val mediaId: Long,
    val dHash: Long,
    val histogram: String,
    val createdAt: Long,
)

@Entity(tableName = "suggestions")
data class SuggestionEntity(
    @PrimaryKey val id: String,
    val reason: String,
    val mediaIds: String,
    val keepIds: String,
    val reclaimBytes: Long,
    val createdAt: Long,
)

@Entity(tableName = "similar_groups")
data class SimilarGroupEntity(
    @PrimaryKey val groupId: String,
    val mediaIds: String,
    val keepIds: String,
    val scores: String,
)

data class HashGroupStat(
    val sha256: String,
    val c: Int,
    val s: Long,
    val largest: Long,
)
