package io.github.joshitup.lantern.ui.update

import io.github.joshitup.lantern.update.CheckResult
import io.github.joshitup.lantern.update.ReleaseInfo
import io.github.joshitup.lantern.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow

/** A scriptable [UpdateSource] for the update UI tests. */
internal class FakeUpdates : UpdateSource {
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
    override fun cancelPermissionRequest() {
        if (install.value is UpdateState.NeedsPermission) install.value = UpdateState.Idle
    }
}
