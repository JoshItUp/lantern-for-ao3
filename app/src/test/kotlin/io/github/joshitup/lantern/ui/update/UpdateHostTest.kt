package io.github.joshitup.lantern.ui.update

import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.joshitup.lantern.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UpdateHostTest {
    @get:Rule val compose = createComposeRule()

    private val src = FakeUpdates()
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun show(state: Lifecycle.State) {
        owner.registry.currentState = state
        val vm = UpdateViewModel(src, versionName = "1.0.0")
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UpdateHost(vm) { Intent("test.PERMISSION") }
            }
        }
        compose.waitForIdle()
    }

    private fun moveTo(state: Lifecycle.State) {
        compose.runOnIdle { owner.registry.currentState = state }
        compose.waitForIdle()
    }

    /** Actions of the activities started since the last call. */
    private fun started(): List<String?> {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        return generateSequence { app.nextStartedActivity }.map { it.action }.toList()
    }

    @Test fun confirmationWhileResumed_launchedOnce() {
        src.install.value = UpdateState.AwaitingConfirmation(Intent("test.CONFIRM"))
        show(Lifecycle.State.RESUMED)
        assertEquals(listOf("test.CONFIRM"), started())
        assertEquals(1, src.launched)
    }

    @Test fun confirmationWhileNotResumed_waitsForResume() {
        src.install.value = UpdateState.AwaitingConfirmation(Intent("test.CONFIRM"))
        show(Lifecycle.State.STARTED)
        assertEquals(emptyList<String?>(), started())
        assertEquals(0, src.launched)
        moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("test.CONFIRM"), started())
        assertEquals(1, src.launched)
    }

    @Test fun stillAwaiting_relaunchedOnNextResume() {
        src.install.value = UpdateState.AwaitingConfirmation(Intent("test.CONFIRM"))
        show(Lifecycle.State.RESUMED)
        started()
        moveTo(Lifecycle.State.STARTED)
        moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("test.CONFIRM"), started())
        assertEquals(2, src.launched)
    }

    @Test fun otherStates_launchNothing() {
        show(Lifecycle.State.RESUMED)
        for (s in listOf(UpdateState.Downloading(0.5f), UpdateState.Installing, UpdateState.NeedsPermission, UpdateState.Failed("x"))) {
            src.install.value = s
            compose.waitForIdle()
        }
        assertEquals(emptyList<String?>(), started())
        assertEquals(0, src.launched)
    }
}
