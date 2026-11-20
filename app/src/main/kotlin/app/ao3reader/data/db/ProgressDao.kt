package app.ao3reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Query("SELECT * FROM ProgressEntity ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(): Flow<ProgressEntity?>

    @Query("SELECT * FROM ProgressEntity WHERE workId = :workId")
    suspend fun get(workId: Long): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: ProgressEntity)

    @Query("SELECT * FROM ProgressEntity WHERE workId IN (:workIds)")
    fun observeFor(workIds: List<Long>): Flow<List<ProgressEntity>>
}
