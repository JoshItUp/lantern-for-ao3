package app.ao3reader.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.KeyGenerator

/** Pure JVM: the same AES-GCM code path the Keystore key uses, with a software key. */
class AesGcmSecretBoxTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = AesGcmSecretBox { key }

    @Test fun `seal then open round trips`() {
        val plain = "pässword".toByteArray()
        val sealed = box.seal(plain)
        assertArrayEquals(plain, box.open(sealed))
        // 12-byte IV, ciphertext, 16-byte tag.
        assertEquals(12 + plain.size + 16, sealed.size)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("ssword"))
    }

    @Test fun `random IV each time`() {
        val plain = "same".toByteArray()
        assertNotEquals(box.seal(plain).toList(), box.seal(plain).toList())
    }

    @Test fun `tampering is detected`() {
        val sealed = box.seal("secret".toByteArray())
        for (i in listOf(0, 12, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            try {
                box.open(bad)
                fail("tampered byte $i accepted")
            } catch (_: java.security.GeneralSecurityException) {
            }
        }
    }

    @Test fun `wrong key is rejected`() {
        val other = AesGcmSecretBox { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        try {
            other.open(box.seal("secret".toByteArray()))
            fail("opened with the wrong key")
        } catch (_: java.security.GeneralSecurityException) {
        }
    }

    @Test fun `too short input is rejected`() {
        try {
            box.open(ByteArray(5))
            fail("accepted a truncated box")
        } catch (_: java.security.GeneralSecurityException) {
        }
    }
}
