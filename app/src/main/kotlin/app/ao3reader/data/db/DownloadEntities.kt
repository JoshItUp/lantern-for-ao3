package app.ao3reader.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

enum class SourceKind { FULL_WORK_PAGE, EPUB }
enum class DownloadJob { NONE, QUEUED, DOWNLOADING, FAILED }

@Entity
data class DownloadEntity(
    @PrimaryKey val workId: Long,
    val title: String,
    /** Joined ", ". */
    val authors: String,
    val blurbJson: String?,
    val chaptersStored: Int,
    val chaptersPostedRemote: Int?,
    val source: SourceKind?,
    val formatVersion: Int,
    val downloadedAt: Long?,
    val sizeBytes: Long,
    val hasCopy: Boolean,
    val job: DownloadJob,
    val failure: String?,
    val requestedAt: Long,
)

@Entity(primaryKeys = ["workId", "index"])
data class DownloadChapterEntity(val workId: Long, val index: Int, val chapterId: Long?, val number: Int, val title: String)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM DownloadEntity ORDER BY downloadedAt DESC, requestedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM DownloadEntity WHERE workId = :workId")
    fun observe(workId: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM DownloadEntity WHERE workId = :workId")
    suspend fun get(workId: Long): DownloadEntity?

    @Query("SELECT * FROM DownloadChapterEntity WHERE workId = :workId ORDER BY `index`")
    suspend fun chapters(workId: Long): List<DownloadChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChapters(chapters: List<DownloadChapterEntity>)

    @Query("UPDATE DownloadEntity SET job = :job, failure = :failure WHERE workId = :workId")
    suspend fun setJob(workId: Long, job: DownloadJob, failure: String?)

    @Query("UPDATE DownloadEntity SET chaptersPostedRemote = :posted WHERE workId = :workId")
    suspend fun setPostedRemote(workId: Long, posted: Int)

    @Query("DELETE FROM DownloadChapterEntity WHERE workId = :workId") suspend fun deleteChapters(workId: Long)

    @Query("DELETE FROM DownloadEntity WHERE workId = :workId") suspend fun deleteRow(workId: Long)

    @Transaction
    suspend fun replaceCopy(e: DownloadEntity, chapters: List<DownloadChapterEntity>) {
        deleteChapters(e.workId)
        upsert(e)
        insertChapters(chapters)
    }

    /** The row and its chapters. */
    @Transaction
    suspend fun delete(workId: Long) {
        deleteChapters(workId)
        deleteRow(workId)
    }

    @Query("SELECT * FROM DownloadEntity WHERE job = 'QUEUED' ORDER BY requestedAt LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("SELECT workId FROM DownloadEntity WHERE hasCopy = 1")
    fun observeCopyIds(): Flow<List<Long>>
}
