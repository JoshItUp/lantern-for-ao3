package io.github.joshitup.lantern.account

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/** Robolectric has no AndroidKeyStore: a reversible stand-in that never stores the plain bytes. */
class FakeSecretBox : SecretBox {
    var failOpen = false
    override fun seal(plain: ByteArray): ByteArray = byteArrayOf(0x7F) + plain.map { (it.toInt() xor 0x5A).toByte() }
    override fun open(sealed: ByteArray): ByteArray {
        if (failOpen || sealed.isEmpty() || sealed[0] != 0x7F.toByte()) throw javax.crypto.AEADBadTagException("tampered")
        return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

@RunWith(RobolectricTestRunner::class)
class CredentialStoreTest {
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("account-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun `round trip and clear`() {
        val store = CredentialStore(prefs, FakeSecretBox())
        assertNull(store.load())
        assertNull(store.username())
        store.save("Me", "hunter2")
        assertEquals("Me" to "hunter2", store.load())
        assertEquals("Me", store.username())
        // A second instance reads the same prefs.
        assertEquals("Me" to "hunter2", CredentialStore(prefs, FakeSecretBox()).load())
        store.saveDisplayName("Me_AO3")
        assertEquals("Me_AO3", store.displayName())
        store.clear()
        assertNull(store.displayName())
        assertNull(store.load())
        assertNull(store.username())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `stored bytes do not contain the password`() {
        val password = "correct horse battery staple"
        CredentialStore(prefs, FakeSecretBox()).save("Me", password)
        val b64 = Base64.getEncoder().encodeToString(password.toByteArray())
        for ((_, v) in prefs.all) {
            val s = v.toString()
            assertFalse(s.contains(password))
            assertFalse(s.contains(b64))
        }
    }

    @Test fun `undecryptable password reads as no credentials`() {
        val box = FakeSecretBox()
        val store = CredentialStore(prefs, box)
        store.save("Me", "pw")
        box.failOpen = true
        assertNull(store.load())
        assertNull(store.username())
    }
}
