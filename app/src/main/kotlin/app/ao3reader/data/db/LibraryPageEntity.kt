package app.ao3reader.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Page 1 of a library section as JSON (a serialized LibraryPage), for instant display. */
@Entity(primaryKeys = ["section", "page"])
data class LibraryPageEntity(val section: String, val page: Int, val json: String, val fetchedAt: Long)

@Dao
interface LibraryPageDao {
    @Query("SELECT * FROM LibraryPageEntity WHERE section = :section AND page = :page")
    fun observe(section: String, page: Int): Flow<LibraryPageEntity?>

    @Query("SELECT * FROM LibraryPageEntity WHERE section = :section AND page = :page")
    suspend fun get(section: String, page: Int): LibraryPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: LibraryPageEntity)

    @Query("DELETE FROM LibraryPageEntity WHERE section = :section AND page = :page")
    suspend fun delete(section: String, page: Int)

    @Query("DELETE FROM LibraryPageEntity") suspend fun clear()
}
