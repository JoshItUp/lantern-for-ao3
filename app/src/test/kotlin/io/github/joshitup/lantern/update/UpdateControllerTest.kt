package io.github.joshitup.lantern.update

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val release = ReleaseInfo(
        ReleaseVersion(1, 2, 3), "Lantern v1.2.3", "", "https://github.com/Owner/repo/releases/download/v1.2.3/lantern.apk", 3, "",
    )

    private var canInstall = true
    private var verifies = true
    private var verifyError: Exception? = null
    private var downloadError: Exception? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var downloads = 0
    private var pending: ReleaseInfo? = null
    private var installed: File? = null
    private val events = mutableListOf<String>()
    private var sessions = 0

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
        verify = { verifyError?.let { throw it }; verifies },
        markPending = { pending = it; events += "pending" },
        install = { apk, onSession ->
            onSession(++sessions)
            installed = apk; events += "install"
        },
        installTimeoutMs = TIMEOUT,
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

    @Test fun cancelPermissionRequest_returnsToIdle_onlyFromNeedsPermission() = runTest(UnconfinedTestDispatcher()) {
        canInstall = false
        val c = controller(this)
        c.start(release)
        c.cancelPermissionRequest()
        assertEquals(UpdateState.Idle, c.state.value)
        verifies = false
        canInstall = true
        c.start(release)
        val failed = c.state.value
        c.cancelPermissionRequest()
        assertEquals(failed, c.state.value)
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
        c.start(release)
        c.reportInstallFailure(sessions, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        assertEquals(UpdateState.Failed("Install failed: INSTALL_FAILED_UPDATE_INCOMPATIBLE"), c.state.value)
    }

    @Test fun verifyThrows_reportsUnverified() = runTest(UnconfinedTestDispatcher()) {
        verifyError = SecurityException("parse")
        val c = controller(this)
        c.start(release)
        assertEquals(UpdateState.Failed("This update couldn't be verified."), c.state.value)
        assertFalse(File(tmp.root, "lantern-1.2.3.apk").exists())
        assertNull(installed)
    }

    @Test fun pendingUserAction_exposesIntent_andConfirmationLaunchedReturnsToInstalling() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(this)
        c.start(release)
        val confirm = Intent("test.CONFIRM")
        c.awaitConfirmation(sessions, confirm)
        assertEquals(UpdateState.AwaitingConfirmation(confirm), c.state.value)
        c.confirmationLaunched()
        assertEquals(UpdateState.Installing, c.state.value)
    }

    @Test fun installWatchdog_failsAfterTimeout() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(this)
        c.start(release)
        advanceTimeBy(TIMEOUT - 1); runCurrent()
        assertEquals(UpdateState.Installing, c.state.value)
        c.awaitConfirmation(sessions, Intent("test.CONFIRM")) // a callback restarts the clock
        advanceTimeBy(TIMEOUT - 1); runCurrent()
        assertTrue(c.state.value is UpdateState.AwaitingConfirmation)
        advanceTimeBy(2); runCurrent()
        assertEquals(UpdateState.Failed("Install failed: no response from the system"), c.state.value)
    }

    @Test fun watchdog_doesNotOverrideAReportedFailure() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(this)
        c.start(release)
        c.reportInstallFailure(sessions, "aborted")
        advanceTimeBy(TIMEOUT * 2); runCurrent()
        assertEquals(UpdateState.Failed("Install failed: aborted"), c.state.value)
    }

    @Test fun startAfterFailed_runsAgain() = runTest(UnconfinedTestDispatcher()) {
        downloadError = IOException("boom")
        val c = controller(this)
        c.start(release)
        assertTrue(c.state.value is UpdateState.Failed)
        downloadError = null
        c.start(release)
        assertEquals(2, downloads)
        assertEquals(UpdateState.Installing, c.state.value)
    }

    @Test fun startFromStaleConfirmation_runsAgain() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(this)
        c.start(release)
        c.awaitConfirmation(sessions, Intent("test.CONFIRM"))
        tmp.root.resolve("lantern-1.2.3.apk").delete()
        c.start(release)
        assertEquals(2, downloads)
        assertEquals(UpdateState.Installing, c.state.value)
    }

    @Test fun staleSessionCallbacks_ignored() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(this)
        c.start(release)
        c.awaitConfirmation(1, Intent("test.CONFIRM"))
        tmp.root.resolve("lantern-1.2.3.apk").delete()
        gate = CompletableDeferred()
        c.start(release) // the user starts again from the stale confirmation
        c.reportInstallFailure(1, "user cancelled") // session 1's late result
        assertTrue(c.state.value is UpdateState.Downloading)
        gate!!.complete(Unit)
        assertEquals(2, sessions)
        c.awaitConfirmation(1, Intent("test.OLD"))
        c.reportInstallFailure(1, "user cancelled")
        assertEquals(UpdateState.Installing, c.state.value)
        c.reportInstallFailure(2, "aborted")
        assertEquals(UpdateState.Failed("Install failed: aborted"), c.state.value)
    }

    private companion object {
        const val TIMEOUT = 120_000L
    }
}
