package io.github.joshitup.lantern.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UpdateDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var downloader: UpdateDownloader

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = File(tmp.root, "updates")
        downloader = UpdateDownloader(client(), dir, requireHttps = false) // MockWebServer is plain http
    }

    private fun client() = UpdateClient(OkHttpClient(), server.url("/"), "Lantern/1.0.0", "Owner/repo")

    @After fun tearDown() = server.shutdown()

    private fun release(size: Long) =
        ReleaseInfo(ReleaseVersion(1, 2, 3), "Lantern v1.2.3", "", server.url("/lantern.apk").toString(), size, "")

    private val bytes = ByteArray(200_000) { (it % 251).toByte() }

    @Test fun downloads_reportsProgress_renamesPart() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val progress = mutableListOf<Float>()
        val file = downloader.download(release(bytes.size.toLong())) { progress += it }
        assertEquals(File(dir, "lantern-1.2.3.apk"), file)
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(File(dir, "lantern-1.2.3.apk.part").exists())
        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last(), 0.0001f)
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(progress.all { it in 0f..1f })
    }

    @Test fun sizeMismatch_throws_andDeletesPart() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        try {
            downloader.download(release(bytes.size + 1L)) {}
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
    }

    @Test fun httpError_throws() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            downloader.download(release(bytes.size.toLong())) {}
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
    }

    @Test fun removesOlderApks() = runTest {
        dir.mkdirs()
        File(dir, "lantern-1.2.2.apk").writeText("old")
        File(dir, "lantern-1.2.1.apk.part").writeText("old part")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        downloader.download(release(bytes.size.toLong())) {}
        assertEquals(listOf("lantern-1.2.3.apk"), dir.list()?.toList())
    }

    @Test fun nonHttpsFinalUrl_throws_andDeletesPart() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        try {
            UpdateDownloader(client(), dir).download(release(bytes.size.toLong())) {}
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
    }
}
