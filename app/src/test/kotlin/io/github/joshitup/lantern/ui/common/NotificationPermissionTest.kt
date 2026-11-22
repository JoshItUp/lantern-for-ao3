package io.github.joshitup.lantern.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test fun `asks on Android 13 and up when not granted and never asked`() {
        assertTrue(shouldAskNotificationPermission(sdk = 33, granted = false, askedBefore = false))
        assertTrue(shouldAskNotificationPermission(sdk = 36, granted = false, askedBefore = false))
    }

    @Test fun `never asks below Android 13`() {
        assertFalse(shouldAskNotificationPermission(sdk = 32, granted = false, askedBefore = false))
    }

    @Test fun `never asks when already granted`() {
        assertFalse(shouldAskNotificationPermission(sdk = 33, granted = true, askedBefore = false))
    }

    @Test fun `asks at most once`() {
        assertFalse(shouldAskNotificationPermission(sdk = 33, granted = false, askedBefore = true))
    }
}
