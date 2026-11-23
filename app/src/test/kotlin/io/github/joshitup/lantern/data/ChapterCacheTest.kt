package io.github.joshitup.lantern.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChapterCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private fun cache(maxBytes: Long = 64L shl 20, maxAgeMs: Long = 30L * 24 * 3600 * 1000) =
        ChapterCache(File(tmp.root, "chapters"), maxBytes, maxAgeMs) { now }

    private val kb = "x".repeat(1024)

    @Test fun `put then get returns html`() {
        val c = cache()
        c.put(1L, "10", "<p>hi</p>")
        assertEquals("<p>hi</p>", c.get(1L, "10"))
    }

    @Test fun `clear removes every entry`() {
        val c = cache()
        c.put(1L, "10", "<p>a</p>")
        c.put(2L, "single", "<p>b</p>")
        c.clear()
        assertNull(c.get(1L, "10"))
        assertNull(c.get(2L, "single"))
        c.put(3L, "11", "<p>c</p>")   // still usable afterwards
        assertEquals("<p>c</p>", c.get(3L, "11"))
    }

    @Test fun `missing returns null`() {
        assertNull(cache().get(1L, "single"))
    }

    @Test fun `entries older than max age are dropped on trim`() {
        val c = cache(maxAgeMs = 10_000)
        c.put(1L, "1", "old")
        now += 20_000
        c.put(1L, "2", "new")
        assertNull(c.get(1L, "1"))
        assertEquals("new", c.get(1L, "2"))
    }

    @Test fun `get drops expired entry without intervening put`() {
        val c = cache(maxAgeMs = 10_000)
        c.put(1L, "1", "old")
        now += 20_000
        assertNull(c.get(1L, "1"))
        assertEquals(false, File(tmp.root, "chapters/1/1.html").exists())
    }

    @Test fun `lru eviction when over max bytes`() {
        val c = cache(maxBytes = 3 * 1024)
        c.put(1L, "1", kb); now += 1000
        c.put(1L, "2", kb); now += 1000
        c.put(1L, "3", kb); now += 1000
        assertEquals(kb, c.get(1L, "1")); now += 1000
        c.put(1L, "4", kb)
        assertNull(c.get(1L, "2"))
        assertEquals(kb, c.get(1L, "1"))
        assertEquals(kb, c.get(1L, "3"))
        assertEquals(kb, c.get(1L, "4"))
    }

    @Test fun `get refreshes recency`() {
        val c = cache(maxBytes = 2 * 1024)
        c.put(1L, "1", kb); now += 1000
        c.put(1L, "2", kb); now += 1000
        c.get(1L, "1"); now += 1000
        c.put(1L, "3", kb)
        assertEquals(kb, c.get(1L, "1"))
        assertNull(c.get(1L, "2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects path traversal keys`() {
        cache().put(1L, "../x", "bad")
    }

    @Test fun `remove deletes entry`() {
        val c = ChapterCache(tmp.newFolder())
        c.put(1, "10", "x")
        c.remove(1, "10")
        assertEquals(null, c.get(1, "10"))
        c.remove(1, "10")
    }
}
