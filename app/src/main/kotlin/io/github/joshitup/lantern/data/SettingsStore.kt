package io.github.joshitup.lantern.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ReaderFont { SYSTEM, SERIF, SANS, MONO }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val fontSizeSp: Flow<Int> = store.data.map { it[FONT_SIZE]?.coerceIn(MIN_SP, MAX_SP) ?: DEFAULT_SP }

    val fontFamily: Flow<ReaderFont> = store.data.map { prefs ->
        prefs[FONT]?.let { name -> ReaderFont.entries.firstOrNull { it.name == name } } ?: ReaderFont.SYSTEM
    }

    suspend fun setFontSize(sp: Int) {
        store.edit { it[FONT_SIZE] = sp.coerceIn(MIN_SP, MAX_SP) }
    }

    suspend fun setFont(f: ReaderFont) {
        store.edit { it[FONT] = f.name }
    }

    /** The notification permission has been asked for once (Android 13+); it is never asked again. */
    val notificationPermissionAsked: Flow<Boolean> = store.data.map { it[NOTIFICATIONS_ASKED] ?: false }

    suspend fun markNotificationPermissionAsked() {
        store.edit { it[NOTIFICATIONS_ASKED] = true }
    }

    /** Update state: when the last check ran, which release the user dismissed, the last version whose notes were seen, notes saved before an install. */
    val updateLastCheck: Flow<Long?> = store.data.map { it[UPDATE_LAST_CHECK] }
    val updateDismissedCode: Flow<Int> = store.data.map { it[UPDATE_DISMISSED] ?: 0 }
    val updateLastSeenCode: Flow<Int?> = store.data.map { it[UPDATE_LAST_SEEN] }
    val updatePendingNotes: Flow<String?> = store.data.map { it[UPDATE_PENDING] }

    val updateAvailable: Flow<String?> = store.data.map { it[UPDATE_AVAILABLE] }

    suspend fun setUpdateAvailable(json: String?) {
        store.edit { if (json == null) it.remove(UPDATE_AVAILABLE) else it[UPDATE_AVAILABLE] = json }
    }

    /** One edit, so a process death can't leave the notes both seen and still pending. */
    suspend fun markWhatsNewSeen(code: Int) {
        store.edit { it[UPDATE_LAST_SEEN] = code; it.remove(UPDATE_PENDING) }
    }

    suspend fun setUpdateLastCheck(ms: Long) { store.edit { it[UPDATE_LAST_CHECK] = ms } }
    suspend fun setUpdateDismissedCode(code: Int) { store.edit { it[UPDATE_DISMISSED] = code } }
    suspend fun setUpdateLastSeenCode(code: Int) { store.edit { it[UPDATE_LAST_SEEN] = code } }
    suspend fun setUpdatePendingNotes(json: String?) {
        store.edit { if (json == null) it.remove(UPDATE_PENDING) else it[UPDATE_PENDING] = json }
    }

    private companion object {
        val UPDATE_LAST_CHECK = longPreferencesKey("update.lastCheck")
        val UPDATE_DISMISSED = intPreferencesKey("update.dismissedCode")
        val UPDATE_LAST_SEEN = intPreferencesKey("update.lastSeenCode")
        val UPDATE_AVAILABLE = stringPreferencesKey("update.available")
        val UPDATE_PENDING = stringPreferencesKey("update.pendingNotes")
        const val MIN_SP = 14
        const val MAX_SP = 28
        const val DEFAULT_SP = 18
        val FONT_SIZE = intPreferencesKey("font_size_sp")
        val FONT = stringPreferencesKey("font_family")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notification_permission_asked")
    }
}
