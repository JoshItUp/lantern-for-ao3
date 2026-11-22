package io.github.joshitup.lantern.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    // The full path: Room 2.8's driver wrapper compares the requested path with the configured name.
    private val name = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("mig").path

    private fun seed(version: Int) {
        helper.createDatabase(name, version).apply {
            execSQL("INSERT INTO PresetEntity (id, name, position, queryJson) VALUES (1, 'p', 0, '{}')")
            execSQL("INSERT INTO ProgressEntity VALUES (7, 'T', 3, 2, 5, 4, 10, 99)")
            execSQL("INSERT INTO ReadMarkEntity VALUES (7, 55)")
            close()
        }
    }

    private fun check(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT name FROM PresetEntity").use { assertEquals(1, it.count) }
        db.query("SELECT workTitle, blockIndex FROM ProgressEntity WHERE workId = 7").use {
            it.moveToFirst(); assertEquals("T", it.getString(0)); assertEquals(4, it.getInt(1))
        }
        db.query("SELECT seenAt FROM ReadMarkEntity WHERE workId = 7").use { it.moveToFirst(); assertEquals(55L, it.getLong(0)) }
        db.query("SELECT COUNT(*) FROM LibraryPageEntity").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.close()
    }

    @Test fun `1 to 3 keeps presets progress and read marks`() {
        seed(1)
        check(helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3))
    }

    @Test fun `2 to 3 keeps presets progress and read marks and recreates the cache`() {
        seed(2)
        helper.runMigrationsAndValidate(name, 2, true).apply {
            execSQL("INSERT INTO LibraryPageEntity VALUES ('S', 1, '{}', 1)"); close()
        }
        check(helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3))
    }

    @Test fun `3 to 4 adds the download tables and keeps existing data`() {
        seed(3)
        val db = helper.runMigrationsAndValidate(name, 4, true)
        db.query("SELECT COUNT(*) FROM DownloadEntity").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM DownloadChapterEntity").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("SELECT workTitle FROM ProgressEntity WHERE workId = 7").use { it.moveToFirst(); assertEquals("T", it.getString(0)) }
        db.query("SELECT seenAt FROM ReadMarkEntity WHERE workId = 7").use { it.moveToFirst(); assertEquals(55L, it.getLong(0)) }
        db.close()
    }
}
