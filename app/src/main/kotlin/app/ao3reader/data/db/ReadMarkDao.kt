package app.ao3reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadMarkDao {
    @Query("SELECT * FROM ReadMarkEntity WHERE workId IN (:workIds)")
    fun observeFor(workIds: List<Long>): Flow<List<ReadMarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(marks: List<ReadMarkEntity>)
}
