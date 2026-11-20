package app.ao3reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM PresetEntity ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Insert suspend fun insert(p: PresetEntity): Long
    @Update suspend fun update(p: PresetEntity)
    @Query("DELETE FROM PresetEntity WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE PresetEntity SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id -> setPosition(id, index) }
    }
}
