package com.pixora.gallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        OcrCacheEntity::class,
        AnalysisEntity::class,
        NoteEntity::class,
        ExclusionEntity::class,
        SmartAlbumEntity::class,
        AlbumItemEntity::class,
        ExemplarEntity::class,
        SuggestionEntity::class,
        SimilarGroupEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PixoraDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun ocr(): OcrDao
    abstract fun analyses(): AnalysisDao
    abstract fun notes(): NoteDao
    abstract fun exclusions(): ExclusionDao
    abstract fun albums(): AlbumDao
    abstract fun suggestions(): SuggestionDao

    companion object {
        fun create(context: Context): PixoraDatabase =
            Room.databaseBuilder(context.applicationContext, PixoraDatabase::class.java, "pixora.db")
                .build()
    }
}
