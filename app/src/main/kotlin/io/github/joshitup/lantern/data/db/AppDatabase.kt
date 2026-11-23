package io.github.joshitup.lantern.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PresetEntity::class, ProgressEntity::class, ReadMarkEntity::class, LibraryPageEntity::class, DownloadEntity::class, DownloadChapterEntity::class],
    version = 5,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao
    abstract fun progressDao(): ProgressDao
    abstract fun readMarkDao(): ReadMarkDao
    abstract fun libraryPageDao(): LibraryPageDao
    abstract fun downloadDao(): DownloadDao
}

/** The cache is keyed by account now; it holds nothing worth keeping, so it is dropped and recreated. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `LibraryPageEntity`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `LibraryPageEntity` (`username` TEXT NOT NULL, `section` TEXT NOT NULL, " +
                "`page` INTEGER NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`username`, `section`, `page`))"
        )
    }
}
