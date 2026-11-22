package io.github.joshitup.lantern.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(scope: CoroutineScope) = SettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb").also { it.delete() } }
    )

    @Test fun `font size clamped 14 to 28`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = store(scope)
        assertEquals(18, s.fontSizeSp.first())
        assertEquals(ReaderFont.SYSTEM, s.fontFamily.first())
        s.setFontSize(40); assertEquals(28, s.fontSizeSp.first())
        s.setFontSize(5); assertEquals(14, s.fontSizeSp.first())
        s.setFontSize(20); assertEquals(20, s.fontSizeSp.first())
        s.setFont(ReaderFont.MONO); assertEquals(ReaderFont.MONO, s.fontFamily.first())
        scope.cancel()
    }

    @Test fun `notification permission is recorded as asked`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = store(scope)
        assertEquals(false, s.notificationPermissionAsked.first())
        s.markNotificationPermissionAsked()
        assertEquals(true, s.notificationPermissionAsked.first())
        scope.cancel()
    }
}
