package app.ao3reader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
    val queryJson: String,
)

@Entity
data class ProgressEntity(
    @PrimaryKey val workId: Long,
    val workTitle: String,
    val chapterId: Long?,
    val chapterNumber: Int,
    val chaptersTotal: Int?,
    val blockIndex: Int,
    val blockOffsetPx: Int,
    val updatedAt: Long,
)

/** Filled by History loads ([app.ao3reader.data.LibraryRepository.load]). */
@Entity
data class ReadMarkEntity(
    @PrimaryKey val workId: Long,
    val seenAt: Long,
)
