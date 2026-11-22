package com.pixora.gallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT COUNT(*) FROM media_items WHERE isVideo = 0")
    fun observePhotoCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE isVideo = 1")
    fun observeVideoCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE isScreenshot = 1 AND isVideo = 0")
    fun observeScreenshotCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM media_items m
        WHERE m.isScreenshot = 1 AND m.isVideo = 0 AND NOT EXISTS (
            SELECT 1 FROM ocr_cache o
            WHERE o.mediaId = m.id AND o.dateModified = m.dateModified AND o.sizeBytes = m.sizeBytes
        )
        """,
    )
    fun observeUnprocessedScreenshots(): Flow<Int>

    @Query("SELECT * FROM media_items WHERE isScreenshot = 1 AND isVideo = 0 AND dateModified >= :from ORDER BY dateModified DESC")
    fun observeScreenshots(from: Long): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE isVideo = 0 ORDER BY dateModified DESC")
    fun observeImages(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE sha256 IS NOT NULL")
    fun observeHashed(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE isVideo = 0 AND labels LIKE '%' || :token || '%'")
    fun observeByLabel(token: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items")
    suspend fun getAll(): List<MediaItemEntity>

    @Query("SELECT id FROM media_items")
    suspend fun ids(): List<Long>

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun get(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("SELECT DISTINCT bucketName FROM media_items WHERE bucketName != '' ORDER BY bucketName")
    suspend fun buckets(): List<String>

    @Query("SELECT DISTINCT bucketName FROM media_items WHERE bucketName != '' ORDER BY bucketName")
    fun observeBuckets(): Flow<List<String>>

    @Query(
        """
        UPDATE media_items
        SET sha256 = NULL, dHash = NULL, sharpness = NULL, exposureScore = NULL,
            qualityScore = NULL, histogram = NULL, rawLabel = NULL, rawLabelScore = NULL
        """,
    )
    suspend fun clearAnalysisCache()

    @Query("SELECT COUNT(*) FROM media_items WHERE sha256 IS NOT NULL")
    suspend fun hashedCount(): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE dHash IS NOT NULL AND isVideo = 0")
    suspend fun visualCount(): Int
}

@Dao
interface OcrDao {
    @Query("SELECT * FROM ocr_cache WHERE mediaId = :id")
    suspend fun get(id: Long): OcrCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OcrCacheEntity)

    @Query("DELETE FROM ocr_cache")
    suspend fun clear()
}

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM analyses")
    fun observeAll(): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analyses WHERE mediaId = :id")
    suspend fun get(id: Long): AnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AnalysisEntity)

    @Query("UPDATE analyses SET status = :status, updatedAt = :updated WHERE mediaId IN (:ids)")
    suspend fun setStatus(ids: List<Long>, status: String, updated: Long)

    @Query("DELETE FROM analyses WHERE userEdited = 0")
    suspend fun clearAutomatic()

    @Query("DELETE FROM analyses")
    suspend fun clearAll()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observe(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeOne(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes")
    suspend fun all(): List<NoteEntity>

    @Insert
    suspend fun insert(entity: NoteEntity): Long

    @Update
    suspend fun update(entity: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT id FROM notes WHERE dedupKey = :key LIMIT 1")
    suspend fun idByKey(key: String): Long?

    @Query("SELECT COUNT(*) FROM notes WHERE id IN (:ids)")
    suspend fun countIds(ids: List<Long>): Int

    @Query("SELECT sourceMediaId FROM notes WHERE sourceMediaId IS NOT NULL")
    suspend fun sourceMediaIds(): List<Long>

    @Query("SELECT title FROM notes WHERE category = :category AND title != ''")
    suspend fun titles(category: String): List<String>

    @Query("DELETE FROM notes")
    suspend fun clear()
}

@Dao
interface ExclusionDao {
    @Query("SELECT * FROM exclusions")
    fun observe(): Flow<List<ExclusionEntity>>

    @Query("SELECT * FROM exclusions")
    suspend fun all(): List<ExclusionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExclusionEntity)

    @Query("DELETE FROM exclusions WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM exclusions")
    suspend fun clear()
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM smart_albums ORDER BY createdAt DESC")
    fun observe(): Flow<List<SmartAlbumEntity>>

    @Insert
    suspend fun insert(entity: SmartAlbumEntity): Long

    @Query("DELETE FROM smart_albums WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM smart_albums WHERE id = :id")
    suspend fun get(id: Long): SmartAlbumEntity?

    @Query("SELECT * FROM exemplars WHERE albumId = :albumId")
    suspend fun exemplars(albumId: Long): List<ExemplarEntity>

    @Query("SELECT * FROM exemplars WHERE albumId = :albumId")
    fun observeExemplars(albumId: Long): Flow<List<ExemplarEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExemplar(entity: ExemplarEntity)

    @Query("DELETE FROM exemplars WHERE albumId = :albumId AND mediaId = :mediaId")
    suspend fun deleteExemplar(albumId: Long, mediaId: Long)

    @Query("SELECT * FROM album_items WHERE albumId = :albumId ORDER BY similarity DESC")
    fun observeItems(albumId: Long): Flow<List<AlbumItemEntity>>

    @Query("SELECT * FROM album_items WHERE albumId = :albumId")
    suspend fun items(albumId: Long): List<AlbumItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(entity: AlbumItemEntity)

    @Query("DELETE FROM album_items WHERE albumId = :albumId AND state = 'SUGGESTED'")
    suspend fun clearSuggestions(albumId: Long)

    @Query("DELETE FROM album_items WHERE albumId = :albumId")
    suspend fun clearItems(albumId: Long)

    @Query("DELETE FROM exemplars WHERE albumId = :albumId")
    suspend fun clearExemplars(albumId: Long)
}

@Dao
interface SuggestionDao {
    @Query("SELECT * FROM suggestions ORDER BY reclaimBytes DESC")
    fun observe(): Flow<List<SuggestionEntity>>

    @Query("DELETE FROM suggestions")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SuggestionEntity>)

    @Query("SELECT * FROM similar_groups")
    fun observeGroups(): Flow<List<SimilarGroupEntity>>

    @Query("SELECT * FROM similar_groups")
    suspend fun groups(): List<SimilarGroupEntity>

    @Query("DELETE FROM similar_groups")
    suspend fun clearGroups()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(items: List<SimilarGroupEntity>)
}
