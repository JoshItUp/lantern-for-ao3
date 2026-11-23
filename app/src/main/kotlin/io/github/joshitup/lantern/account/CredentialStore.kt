package io.github.joshitup.lantern.account

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption of small secrets. [open] throws a [GeneralSecurityException] on tampered input. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES/GCM/NoPadding; output is the cipher's random 12-byte IV followed by ciphertext and 16-byte tag. */
class AesGcmSecretBox(private val key: () -> SecretKey) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV given: the provider picks a random one (the Keystore key requires that).
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("unexpected IV length")
        return iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < IV_BYTES + TAG_BITS / 8) throw AEADBadTagException("sealed data too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "lantern_creds"
private val keyLock = Any()

/**
 * The real box: a non-exportable AES-256 key in the Android Keystore. No user-authentication
 * requirement, so the silent re-login works in the background.
 */
fun keystoreSecretBox(): SecretBox = AesGcmSecretBox(::keystoreKey)

private fun keystoreKey(): SecretKey = synchronized(keyLock) {
    val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generateKey()
    }
}

/**
 * The owner's AO3 credentials: username in plain text, password sealed by [box] (Base64 of IV‖ciphertext).
 * Never logs or reports either value.
 */
class CredentialStore(private val prefs: SharedPreferences, private val box: SecretBox) {
    /** Replaces any stored account. Throws if sealing fails (Keystore errors); nothing old is left behind then. */
    fun save(username: String, password: String) {
        prefs.edit().remove(USERNAME).remove(PASSWORD).remove(DISPLAY_NAME).commit()
        val sealed = Base64.getEncoder().encodeToString(box.seal(password.toByteArray(Charsets.UTF_8)))
        prefs.edit().putString(USERNAME, username).putString(PASSWORD, sealed).commit()
    }

    /**
     * Null when nothing is stored, or when the password can't be opened. A permanent failure (tampered
     * data, a lost or invalidated key) also clears the stored entry.
     */
    fun load(): Pair<String, String>? {
        val user = prefs.getString(USERNAME, null) ?: return null
        val sealed = prefs.getString(PASSWORD, null) ?: return null
        val password = try {
            String(box.open(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            clear(); return null
        } catch (_: IllegalArgumentException) {
            clear(); return null
        } catch (_: java.security.ProviderException) {
            // A Keystore service hiccup may be transient: report nothing stored, but keep the data.
            return null
        }
        return user to password
    }

    /** The stored username without decrypting anything; null unless a password is stored too. */
    fun username(): String? {
        if (!prefs.contains(PASSWORD)) return null
        return prefs.getString(USERNAME, null)
    }

    /** The AO3 name shown in the greeting (the login identifier may be an email). */
    fun saveDisplayName(name: String) {
        prefs.edit().putString(DISPLAY_NAME, name).commit()
    }

    /** The saved AO3 name; null unless a password is stored too. */
    fun displayName(): String? {
        if (!prefs.contains(PASSWORD)) return null
        return prefs.getString(DISPLAY_NAME, null)
    }

    fun clear() {
        prefs.edit().remove(USERNAME).remove(PASSWORD).remove(DISPLAY_NAME).commit()
    }

    private companion object {
        const val USERNAME = "username"
        const val PASSWORD = "password"
        const val DISPLAY_NAME = "display_name"
    }
}
