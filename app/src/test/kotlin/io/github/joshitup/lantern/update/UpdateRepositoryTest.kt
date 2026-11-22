package io.github.joshitup.lantern.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.joshitup.lantern.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

private fun rel(code: Int, notes: String = "notes $code") = ReleaseInfo(
    ReleaseVersion(code / 10000, code / 100 % 100, code % 100), "v$code", notes, "https://example.test/$code.apk", 1234L, "https://example.test/r/$code",
)

private class FakeSource(var latest: ReleaseInfo? = null, var fail: Boolean = false, var failTag: Boolean = false) : ReleaseSource {
    var latestCalls = 0
    val tags = mutableMapOf<String, ReleaseInfo>()
    override suspend fun latest(): ReleaseInfo? {
        latestCalls++
        if (fail) throw IOException("offline")
        return latest
    }
    override suspend fun byTag(tag: String): ReleaseInfo? {
        if (failTag) throw IOException("offline")
        return tags[tag]
    }
}

@RunWith(RobolectricTestRunner::class)
class UpdateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_000_000_000L
    private val settings by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb").also { it.delete() } })
    }

    @After fun tearDown() = scope.cancel()

    private fun repo(src: ReleaseSource, installed: Int = 10000) = UpdateRepository(src, settings, installed, { now }, scope)

    private suspend fun awaitStrip(expected: Boolean, r: UpdateRepository) =
        withContext(Dispatchers.Default) { withTimeout(5000) { r.showStrip.first { it == expected } } }

    @Test fun maybeCheck_skipsWithin24h() = runTest {
        val src = FakeSource(rel(10001)); val r = repo(src)
        r.maybeCheck(); now += 23 * 3600_000L; r.maybeCheck()
        assertEquals(1, src.latestCalls)
    }

    @Test fun maybeCheck_checksAfter24h() = runTest {
        val src = FakeSource(rel(10001)); val r = repo(src)
        r.maybeCheck(); now += 24 * 3600_000L; r.maybeCheck()
        assertEquals(2, src.latestCalls)
        assertEquals(10001, r.available.value?.version?.code)
    }

    @Test fun maybeCheck_failure_recordsTime_noCrash() = runTest {
        val src = FakeSource(fail = true); val r = repo(src)
        r.maybeCheck(); r.maybeCheck()
        assertEquals(1, src.latestCalls)
        assertEquals(now, settings.updateLastCheck.first())
        assertNull(r.available.value)
    }

    @Test fun olderOrEqualRelease_notAvailable() = runTest {
        val src = FakeSource(rel(10000)); val r = repo(src, installed = 10000)
        assertEquals(CheckResult.UpToDate, r.checkNow())
        src.latest = rel(9999)
        assertEquals(CheckResult.UpToDate, r.checkNow())
        assertNull(r.available.value)
    }

    @Test fun checkNow_failureAndNull() = runTest {
        val src = FakeSource(fail = true); val r = repo(src)
        assertEquals(CheckResult.Failed("Couldn't check for updates"), r.checkNow())
        src.fail = false
        assertEquals(CheckResult.UpToDate, r.checkNow())
    }

    @Test fun dismiss_hidesStrip_untilNewerVersion() = runTest {
        val src = FakeSource(rel(10001)); val r = repo(src)
        r.checkNow(); awaitStrip(true, r)
        r.dismiss(); awaitStrip(false, r)
        assertNotNull(r.available.value)
        src.latest = rel(10002); r.checkNow()
        awaitStrip(true, r)
    }

    @Test fun checkNow_ignoresThrottle() = runTest {
        val src = FakeSource(rel(10001)); val r = repo(src)
        r.maybeCheck(); r.checkNow(); r.checkNow()
        assertEquals(3, src.latestCalls)
    }

    @Test fun whatsNew_shownOnceAfterUpgrade() = runTest {
        val src = FakeSource(); src.tags["v1.0.1"] = rel(10001, "fixed things")
        assertNull(repo(src, 10000).consumeWhatsNew()) // first run records 10000
        val r = repo(src, 10001)
        assertEquals("fixed things", r.consumeWhatsNew()?.notes)
        assertNull(r.consumeWhatsNew())
    }

    @Test fun whatsNew_survivesProcessDeath() = runTest {
        val src = FakeSource()
        repo(src, 10000).apply { consumeWhatsNew(); markPendingWhatsNew(rel(10001, "saved notes")) }
        assertEquals("saved notes", repo(src, 10001).consumeWhatsNew()?.notes)
        assertNull(settings.updatePendingNotes.first())
    }

    @Test fun whatsNew_unknownTag_marksSeen() = runTest {
        val src = FakeSource(); repo(src, 10000).consumeWhatsNew()
        val r = repo(src, 10001)
        assertNull(r.consumeWhatsNew()) // no pending, tag unknown: nothing to show
        assertEquals(10001, settings.updateLastSeenCode.first())
    }

    @Test fun whatsNew_offlineKeepsRetrying() = runTest {
        val src = FakeSource(failTag = true); src.tags["v1.0.1"] = rel(10001, "late notes")
        repo(src, 10000).consumeWhatsNew()
        val r = repo(src, 10001)
        assertNull(r.consumeWhatsNew())
        assertEquals(10000, settings.updateLastSeenCode.first())
        src.failTag = false
        assertEquals("late notes", r.consumeWhatsNew()?.notes)
        assertEquals(10001, settings.updateLastSeenCode.first())
    }

    @Test fun available_survivesRestartWithinThrottle() = runTest {
        val src = FakeSource(rel(10001))
        repo(src).maybeCheck()
        val r2 = repo(src)
        r2.maybeCheck() // throttled, but seeds from storage
        assertEquals(1, src.latestCalls)
        assertEquals(10001, r2.available.value?.version?.code)
        awaitStrip(true, r2)
    }

    @Test fun upToDateCheck_clearsAvailable() = runTest {
        val src = FakeSource(rel(10001)); val r = repo(src)
        r.checkNow(); assertNotNull(r.available.value)
        src.latest = null; r.checkNow()
        assertNull(r.available.value)
        assertNull(settings.updateAvailable.first())
        src.latest = rel(10001); r.checkNow(); src.latest = rel(10000); r.checkNow()
        assertNull(r.available.value)
    }

    @Test fun installedCaughtUp_clearsAvailable() = runTest {
        val src = FakeSource(rel(10001))
        repo(src, 10000).checkNow()
        val r2 = repo(src, 10001)
        r2.maybeCheck() // throttled
        assertNull(r2.available.value)
        assertNull(settings.updateAvailable.first())
    }

    @Test fun freshInstall_noWhatsNew() = runTest {
        val src = FakeSource(); src.tags["v1.0.1"] = rel(10001)
        val r = repo(src, 10001)
        assertNull(r.consumeWhatsNew())
        assertEquals(10001, settings.updateLastSeenCode.first())
        assertTrue(r.consumeWhatsNew() == null)
    }

    @Test fun releaseInfo_jsonRoundTrip() {
        val info = rel(10203, "## Hi\n- **bold** \"quoted\" [l](https://x.test)\nünïcode")
        val json = Json.encodeToString(ReleaseInfo.serializer(), info)
        assertEquals(info, Json.decodeFromString(ReleaseInfo.serializer(), json))
    }
}
