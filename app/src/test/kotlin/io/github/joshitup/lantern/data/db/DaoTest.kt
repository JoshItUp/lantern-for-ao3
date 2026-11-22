package io.github.joshitup.lantern.data.db

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import io.github.joshitup.lantern.ao3.model.*
import io.github.joshitup.lantern.data.searchQueryFromJson
import io.github.joshitup.lantern.data.toJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun progress(workId: Long, updatedAt: Long) = ProgressEntity(
        workId = workId, workTitle = "W$workId", chapterId = 5L, chapterNumber = 2,
        chaptersTotal = null, blockIndex = 7, blockOffsetPx = 33, updatedAt = updatedAt,
    )

    @Test fun `presets ordered by position and reorder persists`() = runTest {
        val dao = db.presetDao()
        val a = dao.insert(PresetEntity(name = "a", position = 0, queryJson = "{}"))
        val b = dao.insert(PresetEntity(name = "b", position = 1, queryJson = "{}"))
        val c = dao.insert(PresetEntity(name = "c", position = 2, queryJson = "{}"))
        assertEquals(listOf("a", "b", "c"), dao.observeAll().first().map { it.name })
        dao.reorder(listOf(c, a, b))
        assertEquals(listOf("c", "a", "b"), dao.observeAll().first().map { it.name })
        dao.delete(a)
        assertEquals(listOf("c", "b"), dao.observeAll().first().map { it.name })
    }

    @Test fun `query json round trips`() {
        val q = SearchQuery(
            anyField = "x", title = "t", creators = "c",
            fandoms = listOf("f1", "f2"), characters = listOf("ch"), relationships = listOf("r"),
            freeforms = listOf("ff"), excludedTags = listOf("ex"),
            ratings = setOf(Rating.TEEN, Rating.MATURE), warnings = setOf(Warning.NONE),
            categories = setOf(Category.MM, Category.FF),
            completion = Completion.COMPLETE, crossover = Crossover.EXCLUDE,
            wordsFrom = 100, wordsTo = 5000, language = "en", updatedWithin = "< 7 days",
            sort = SortColumn.KUDOS, sortDescending = false,
        )
        assertEquals(q, searchQueryFromJson(q.toJson()))
    }

    @Test fun `query json ignores unknown keys`() {
        assertEquals(SearchQuery(title = "t"), searchQueryFromJson("""{"title":"t","future":1}"""))
    }

    @Test fun `latest progress is most recent`() = runTest {
        val dao = db.progressDao()
        dao.upsert(progress(1, 100)); dao.upsert(progress(2, 300)); dao.upsert(progress(3, 200))
        assertEquals(2L, dao.observeLatest().first()?.workId)
        assertEquals(setOf(1L, 3L), dao.observeFor(listOf(1, 3)).first().map { it.workId }.toSet())
    }

    @Test fun `read marks upsert and observe`() = runTest {
        val dao = db.readMarkDao()
        dao.upsertAll(listOf(ReadMarkEntity(1, 10), ReadMarkEntity(2, 20)))
        dao.upsertAll(listOf(ReadMarkEntity(1, 30)))
        assertEquals(listOf(30L), dao.observeFor(listOf(1)).first().map { it.seenAt })
    }

    @Test fun `progress survives reopen`() = runTest {
        val file = context.getDatabasePath("reopen.db")
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries().build()
        val p = progress(9, 123)
        val first = open()
        first.progressDao().upsert(p)
        first.close()
        val second = open()
        assertEquals(p, second.progressDao().get(9))
        second.close()
    }
}
