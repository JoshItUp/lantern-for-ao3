package io.github.joshitup.lantern.ui.update

import io.github.joshitup.lantern.update.CheckResult
import io.github.joshitup.lantern.update.ReleaseInfo
import io.github.joshitup.lantern.update.ReleaseVersion
import io.github.joshitup.lantern.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val release = ReleaseInfo(
        ReleaseVersion(1, 2, 3), "Lantern v1.2.3", "- a fix", "https://github.com/o/r/releases/download/v1.2.3/l.apk", 5_242_880L, "",
    )

    private class FakeUpdates : UpdateSource {
        override val available = MutableStateFlow<ReleaseInfo?>(null)
        override val showStrip = MutableStateFlow(false)
        override val install = MutableStateFlow<UpdateState>(UpdateState.Idle)
        var check: CheckResult = CheckResult.UpToDate
        var whatsNew: ReleaseInfo? = null
        var whatsNewCalls = 0
        var dismissCalls = 0
        val started = mutableListOf<ReleaseInfo>()
        var canInstall = true
        var launched = 0

        override suspend fun checkNow() = check
        override suspend fun dismiss() {
            dismissCalls++
            showStrip.value = false
        }
        override suspend fun consumeWhatsNew(): ReleaseInfo? {
            whatsNewCalls++
            return whatsNew.also { whatsNew = null }
        }
        override fun start(release: ReleaseInfo) { started += release }
        override fun confirmationLaunched() { launched++ }
        override fun resetIfFailed() {
            if (install.value is UpdateState.Failed) install.value = UpdateState.Idle
        }
        override fun canInstall() = canInstall
    }

    private lateinit var src: FakeUpdates

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        src = FakeUpdates()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = UpdateViewModel(src, versionName = "1.2.2")

    @Test fun strip_hiddenWhenNoUpdate() = runTest(dispatcher) {
        val vm = vm()
        assertNull(vm.state.value.strip)
    }

    @Test fun strip_shownWhenAvailableAndNotDismissed() = runTest(dispatcher) {
        val vm = vm()
        src.available.value = release
        src.showStrip.value = true
        assertEquals(release, vm.state.value.strip)
        src.showStrip.value = false
        assertNull(vm.state.value.strip)
        assertEquals(release, vm.state.value.release)
    }

    @Test fun later_dismisses() = runTest(dispatcher) {
        val vm = vm()
        src.available.value = release
        src.showStrip.value = true
        vm.openSheet()
        vm.later()
        assertEquals(1, src.dismissCalls)
        assertFalse(vm.state.value.sheetOpen)
        assertNull(vm.state.value.strip)
    }

    @Test fun download_startsController() = runTest(dispatcher) {
        val vm = vm()
        src.available.value = release
        vm.download()
        assertEquals(listOf(release), src.started)
    }

    @Test fun retry_afterFailure_startsAgain() = runTest(dispatcher) {
        val vm = vm()
        src.available.value = release
        src.install.value = UpdateState.Failed("Couldn't download the update. Tap to retry.")
        vm.download()
        assertEquals(listOf(release), src.started)
    }

    @Test fun checkNow_upToDate_message() = runTest(dispatcher) {
        val vm = vm()
        vm.checkNow()
        assertEquals("You're up to date", vm.state.value.checkMessage)
        assertFalse(vm.state.value.checking)
        assertFalse(vm.state.value.sheetOpen)
    }

    @Test fun checkNow_available_opensSheet() = runTest(dispatcher) {
        val vm = vm()
        src.check = CheckResult.Available(release)
        src.available.value = release
        vm.checkNow()
        assertTrue(vm.state.value.sheetOpen)
        assertNull(vm.state.value.checkMessage)
    }

    @Test fun checkNow_failed_showsReason() = runTest(dispatcher) {
        val vm = vm()
        src.check = CheckResult.Failed("Couldn't check for updates")
        vm.checkNow()
        assertEquals("Couldn't check for updates", vm.state.value.checkMessage)
    }

    @Test fun whatsNew_consumedOnce() = runTest(dispatcher) {
        src.whatsNew = release
        val vm = vm()
        vm.loadWhatsNew()
        assertEquals(release, vm.state.value.whatsNew)
        vm.whatsNewShown()
        vm.loadWhatsNew()
        assertNull(vm.state.value.whatsNew)
        assertEquals(1, src.whatsNewCalls)
    }

    @Test fun resume_afterPermissionGranted_restarts() = runTest(dispatcher) {
        val vm = vm()
        src.available.value = release
        src.install.value = UpdateState.NeedsPermission
        src.canInstall = false
        vm.onResume()
        assertTrue(src.started.isEmpty())
        src.canInstall = true
        vm.onResume()
        assertEquals(listOf(release), src.started)
    }

    @Test fun closeSheet_clearsFailure() = runTest(dispatcher) {
        val vm = vm()
        src.available.value = release
        src.install.value = UpdateState.Failed("x")
        vm.openSheet()
        vm.closeSheet()
        assertFalse(vm.state.value.sheetOpen)
        assertEquals(UpdateState.Idle, src.install.value)
    }

    @Test fun sizeInMb() {
        assertEquals("5.0 MB", formatSize(5_242_880L))
        assertEquals("12.3 MB", formatSize(12_897_485L))
    }
}
