package io.github.joshitup.lantern.data.db

import androidx.room.ColumnInfo
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
    /**
     * The reader layout [blockIndex] counts items in. New rows use the current one; rows saved before
     * blank-line splitting get [LAYOUT_LEGACY] from the column default and are translated on restore.
     */
    @ColumnInfo(defaultValue = "1") val layout: Int = LAYOUT_SPLIT,
)

/** Saved before the reader split text at blank lines. */
const val LAYOUT_LEGACY = 1

/** Each blank-line-separated paragraph is its own reader item. */
const val LAYOUT_SPLIT = 2

/** Filled by History loads ([io.github.joshitup.lantern.data.LibraryRepository.load]). */
@Entity
data class ReadMarkEntity(
    @PrimaryKey val workId: Long,
    val seenAt: Long,
)
