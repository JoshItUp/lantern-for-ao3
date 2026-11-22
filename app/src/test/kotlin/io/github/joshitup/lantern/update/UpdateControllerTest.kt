package io.github.joshitup.lantern.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val release = ReleaseInfo(
        ReleaseVersion(1, 2, 3), "Lantern v1.2.3", "", "https://github.com/Owner/repo/releases/download/v1.2.3/lantern.apk", 3, "",
    )

    private var canInstall = true
    private var verifies = true
    private var downloadError: Exception? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var downloads = 0
    private var pending: ReleaseInfo? = null
    private var installed: File? = null
    private val events = mutableListOf<String>()

    private fun controller(scope: TestScope) = UpdateController(
        scope = scope.backgroundScope,
        canInstall = { canInstall },
        download = { _, onProgress ->
            downloads++
            gate?.await()
            downloadError?.let { throw it }
            onProgress(1f)
            tmp.newFile("lantern-1.2.3.apk").apply { writeText("apk") }
        },
        verify = { verifies },
        markPending = { pending = it; events += "pending" },
        install = { installed = it; events += "install" },
    )

    @Test fun success_marksPendingBeforeInstall() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(this)
        c.start(release)
        assertEquals(listOf("pending", "install"), events)
        assertEquals(release, pending)
        assertEquals(UpdateState.Installing, c.state.value)
    }

    @Test fun verifyFailure_deletesFile_reportsExactCopy() = runTest(UnconfinedTestDispatcher()) {
        verifies = false
        val c = controller(this)
        c.start(release)
        assertEquals(UpdateState.Failed("This update couldn't be verified."), c.state.value)
        assertFalse(File(tmp.root, "lantern-1.2.3.apk").exists())
        assertNull(pending)
        assertNull(installed)
    }

    @Test fun downloadFailure_reportsRetryCopy() = runTest(UnconfinedTestDispatcher()) {
        downloadError = IOException("boom")
        val c = controller(this)
        c.start(release)
        assertEquals(UpdateState.Failed("Couldn't download the update. Tap to retry."), c.state.value)
        c.resetIfFailed()
        assertEquals(UpdateState.Idle, c.state.value)
    }

    @Test fun noPermission_needsPermission_noDownload() = runTest(UnconfinedTestDispatcher()) {
        canInstall = false
        val c = controller(this)
        c.start(release)
        assertEquals(UpdateState.NeedsPermission, c.state.value)
        assertEquals(0, downloads)
    }

    @Test fun nonGithubUrl_notDownloaded() = runTest(UnconfinedTestDispatcher()) {
        for (url in listOf("http://github.com/a.apk", "https://example.com/a.apk", "https://github.com.evil.example/a.apk", "not a url")) {
            val c = controller(this)
            c.start(release.copy(apkUrl = url))
            assertEquals(url, UpdateState.Failed("This update couldn't be verified."), c.state.value)
        }
        assertEquals(0, downloads)
    }

    @Test fun startWhileDownloading_isNoOp() = runTest(UnconfinedTestDispatcher()) {
        gate = CompletableDeferred()
        val c = controller(this)
        c.start(release)
        assertTrue(c.state.value is UpdateState.Downloading)
        c.start(release)
        assertEquals(1, downloads)
        gate!!.complete(Unit)
        assertEquals(UpdateState.Installing, c.state.value)
    }

    @Test fun installFailure_reported() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(this)
        c.reportInstallFailure("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        assertEquals(UpdateState.Failed("Install failed: INSTALL_FAILED_UPDATE_INCOMPATIBLE"), c.state.value)
    }
}
