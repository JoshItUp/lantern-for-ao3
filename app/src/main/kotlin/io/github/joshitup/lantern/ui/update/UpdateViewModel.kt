package io.github.joshitup.lantern.ui.update

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.joshitup.lantern.AppContainer
import io.github.joshitup.lantern.BuildConfig
import io.github.joshitup.lantern.update.CheckResult
import io.github.joshitup.lantern.update.ReleaseInfo
import io.github.joshitup.lantern.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** What the update UI needs from [io.github.joshitup.lantern.update.UpdateRepository] and the controller (a seam for tests). */
interface UpdateSource {
    val available: StateFlow<ReleaseInfo?>
    val showStrip: StateFlow<Boolean>
    val install: StateFlow<UpdateState>
    suspend fun checkNow(): CheckResult
    suspend fun dismiss()
    suspend fun consumeWhatsNew(): ReleaseInfo?
    fun start(release: ReleaseInfo)
    fun confirmationLaunched()
    fun resetIfFailed()
    fun canInstall(): Boolean
}

fun AppContainer.updateSource(): UpdateSource {
    val c = this
    return object : UpdateSource {
        override val available get() = c.updateRepo.available
        override val showStrip get() = c.updateRepo.showStrip
        override val install get() = c.updateController.state
        override suspend fun checkNow() = c.updateRepo.checkNow()
        override suspend fun dismiss() = c.updateRepo.dismiss()
        override suspend fun consumeWhatsNew() = c.updateRepo.consumeWhatsNew()
        override fun start(release: ReleaseInfo) = c.updateController.start(release)
        override fun confirmationLaunched() = c.updateController.confirmationLaunched()
        override fun resetIfFailed() = c.updateController.resetIfFailed()
        override fun canInstall() = c.updateInstaller.canInstall()
    }
}

@Immutable
data class UpdateUiState(
    /** The release the Search strip offers; null hides the strip. */
    val strip: ReleaseInfo? = null,
    /** The newer release, dismissed or not (the sheet and About use it). */
    val release: ReleaseInfo? = null,
    val install: UpdateState = UpdateState.Idle,
    val sheetOpen: Boolean = false,
    val checking: Boolean = false,
    /** About's result line after "Check for updates". */
    val checkMessage: String? = null,
    /** Notes for the version just installed; shown once. */
    val whatsNew: ReleaseInfo? = null,
)

private data class Local(
    val sheetOpen: Boolean = false,
    val checking: Boolean = false,
    val checkMessage: String? = null,
    val whatsNew: ReleaseInfo? = null,
)

/** Activity-scoped: the strip, the update sheet, About's check and the launch "What's new" share it. */
class UpdateViewModel(private val source: UpdateSource, val versionName: String) : ViewModel() {
    private val local = MutableStateFlow(Local())

    val state: StateFlow<UpdateUiState> = combine(
        source.available, source.showStrip, source.install, local,
    ) { rel, strip, install, l ->
        UpdateUiState(
            strip = rel.takeIf { strip },
            release = rel,
            install = install,
            sheetOpen = l.sheetOpen,
            checking = l.checking,
            checkMessage = l.checkMessage,
            whatsNew = l.whatsNew,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdateUiState())

    fun openSheet() = local.update { it.copy(sheetOpen = true) }

    /** A failure is cleared on close, so reopening offers Download again. */
    fun closeSheet() {
        local.update { it.copy(sheetOpen = false) }
        source.resetIfFailed()
    }

    fun later() {
        local.update { it.copy(sheetOpen = false) }
        viewModelScope.launch { source.dismiss() }
    }

    /** Download, and Retry after a failure (the controller proceeds from Failed). */
    fun download() {
        source.available.value?.let(source::start)
    }

    fun checkNow() {
        if (local.value.checking) return
        local.update { it.copy(checking = true, checkMessage = null) }
        viewModelScope.launch {
            val result = try {
                source.checkNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CheckResult.Failed("Couldn't check for updates")
            }
            local.update {
                when (result) {
                    CheckResult.UpToDate -> it.copy(checking = false, checkMessage = "You're up to date")
                    is CheckResult.Available -> it.copy(checking = false, sheetOpen = true)
                    is CheckResult.Failed -> it.copy(checking = false, checkMessage = result.reason)
                }
            }
        }
    }

    private var whatsNewLoaded = false

    /** Once per launch (this ViewModel lives as long as the activity); the repository marks it seen. */
    fun loadWhatsNew() {
        if (whatsNewLoaded) return
        whatsNewLoaded = true
        viewModelScope.launch {
            val notes = try {
                source.consumeWhatsNew()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (notes != null) local.update { it.copy(whatsNew = notes) }
        }
    }

    fun whatsNewShown() = local.update { it.copy(whatsNew = null) }

    /** Back from "Install unknown apps" with the permission granted: carry on with the update. */
    fun onResume() {
        if (source.install.value !is UpdateState.NeedsPermission || !source.canInstall()) return
        source.available.value?.let(source::start)
    }

    fun confirmationLaunched() = source.confirmationLaunched()

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { UpdateViewModel(container.updateSource(), BuildConfig.VERSION_NAME) }
        }
    }
}

/** The APK size for the sheet, in binary megabytes with one decimal. */
fun formatSize(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
