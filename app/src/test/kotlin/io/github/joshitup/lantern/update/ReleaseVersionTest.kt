package io.github.joshitup.lantern.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun parsesTagWithPrefix() {
        val v = ReleaseVersion.parseTag("v1.2.3")
        assertEquals(ReleaseVersion(1, 2, 3), v)
        assertEquals(10203, v!!.code)
    }

    @Test
    fun parsesTagWithoutPrefix() {
        assertEquals(ReleaseVersion(1, 0, 0), ReleaseVersion.parseTag("1.0.0"))
    }

    @Test
    fun rejectsMalformedTags() {
        for (tag in listOf("v1.0", "v1.0.0-beta", "v1.100.0", "v1.0.100", "latest", "")) {
            assertNull(tag, ReleaseVersion.parseTag(tag))
        }
    }

    @Test
    fun ordersNumerically() {
        assertTrue(ReleaseVersion.parseTag("v1.10.0")!! > ReleaseVersion.parseTag("v1.9.9")!!)
    }

    @Test
    fun formatsAsDottedString() {
        assertEquals("1.2.3", ReleaseVersion(1, 2, 3).toString())
    }
}
