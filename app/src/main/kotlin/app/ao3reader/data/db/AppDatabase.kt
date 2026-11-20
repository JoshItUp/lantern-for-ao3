package app.ao3reader.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PresetEntity::class, ProgressEntity::class, ReadMarkEntity::class, LibraryPageEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao
    abstract fun progressDao(): ProgressDao
    abstract fun readMarkDao(): ReadMarkDao
    abstract fun libraryPageDao(): LibraryPageDao
}
