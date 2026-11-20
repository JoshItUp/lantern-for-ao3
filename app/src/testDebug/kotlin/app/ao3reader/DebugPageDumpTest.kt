package app.ao3reader

import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.net.Fetcher
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugPageDumpTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Stub(val result: FetchResult) : Fetcher {
        var lastAccept: String? = "unset"
        var lastInteractive: Boolean? = null
        override suspend fun get(url: HttpUrl, accept: String?, interactive: Boolean, anonymous: Boolean): FetchResult {
            lastAccept = accept; lastInteractive = interactive; return result
        }
        override suspend fun post(url: HttpUrl, fields: List<Pair<String, String>>, accept: String?) = result
    }

    @Test fun passesAcceptThrough() = runBlocking {
        val stub = Stub(FetchResult.NotFound)
        val f = DumpingFetcher(stub, DebugPageDump(tmp.newFolder("a")))
        f.get("https://archiveofourown.org/autocomplete/tag?term=ab".toHttpUrl(), accept = "application/json")
        assertEquals("application/json", stub.lastAccept)
        f.get("https://archiveofourown.org/works/1".toHttpUrl())
        assertEquals(null, stub.lastAccept)
        assertEquals(true, stub.lastInteractive)
        f.get("https://archiveofourown.org/works/1".toHttpUrl(), interactive = false)
        assertEquals(false, stub.lastInteractive)
    }

    private fun fetch(dir: java.io.File, result: FetchResult, url: String) = runBlocking {
        DumpingFetcher(Stub(result), DebugPageDump(dir)).get(url.toHttpUrl())
    }

    @Test fun writesFileForOk() {
        val dir = tmp.newFolder("d")
        val u = "https://archiveofourown.org/works/search?work_search[query]=a/b&page=2".toHttpUrl()
        fetch(dir, FetchResult.Ok("<html>x</html>", u), u.toString())
        val files = dir.listFiles()!!
        assertEquals(1, files.size)
        assertEquals("<html>x</html>", files[0].readText())
        assertTrue(files[0].name.endsWith(".html"))
    }

    @Test fun nothingForNonOk() {
        val dir = tmp.newFolder("d")
        fetch(dir, FetchResult.NotFound, "https://archiveofourown.org/works/1")
        fetch(dir, FetchResult.Blocked, "https://archiveofourown.org/works/1")
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun keepsAtMost30AndNamesAreSafe() {
        val dir = tmp.newFolder("d")
        val dump = DebugPageDump(dir)
        repeat(40) { dump.dump("https://archiveofourown.org/works/$it/chapters/../x?a=b/c&d=e%2Ff".toHttpUrl(), "p$it") }
        val files = dir.listFiles()!!
        assertEquals(30, files.size)
        assertTrue(files.all { f -> f.name.none { it == '/' || it == '\\' } && f.name.length <= 140 })
        assertTrue(files.any { it.readText() == "p39" })
        assertFalse(files.any { it.readText() == "p0" })
    }
}
