package app.ao3reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PresetEntity::class, ProgressEntity::class, ReadMarkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao
    abstract fun progressDao(): ProgressDao
    abstract fun readMarkDao(): ReadMarkDao
}
