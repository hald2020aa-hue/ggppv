package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        MangaEntity::class,
        ChapterEntity::class,
        PageEntity::class,
        FavoriteEntity::class,
        ReadingHistoryEntity::class,
        DownloadTaskEntity::class,
        SourceConfigurationEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao

    companion object {
        @Volatile
        private var INSTANCE: MangaDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Safe schema evolution example preserving user data (Rule 18)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapters_mangaId` ON `chapters` (`mangaId`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `source_configs` (
                    `sourceId` TEXT NOT NULL, `domain` TEXT NOT NULL,
                    `configurationJson` TEXT NOT NULL, `lastValidatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceId`))""")
                db.execSQL("ALTER TABLE `manga` ADD COLUMN `contentRating` TEXT NOT NULL DEFAULT 'GENERAL'")
            }
        }

        fun getInstance(context: Context): MangaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MangaDatabase::class.java,
                    "manga_reader.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
