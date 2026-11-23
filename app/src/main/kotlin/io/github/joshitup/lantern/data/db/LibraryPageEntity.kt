package io.github.joshitup.lantern.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Page 1 of a library section of one account ([username], lowercased) as JSON (a serialized LibraryPage). */
@Entity(primaryKeys = ["username", "section", "page"])
data class LibraryPageEntity(val username: String, val section: String, val page: Int, val json: String, val fetchedAt: Long)

@Dao
interface LibraryPageDao {
    @Query("SELECT * FROM LibraryPageEntity WHERE username = :username AND section = :section AND page = :page")
    fun observe(username: String, section: String, page: Int): Flow<LibraryPageEntity?>

    @Query("SELECT * FROM LibraryPageEntity WHERE username = :username AND section = :section AND page = :page")
    suspend fun get(username: String, section: String, page: Int): LibraryPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: LibraryPageEntity)

    @Query("DELETE FROM LibraryPageEntity WHERE username = :username AND section = :section AND page = :page")
    suspend fun delete(username: String, section: String, page: Int)

    @Query("DELETE FROM LibraryPageEntity") suspend fun clear()
}
