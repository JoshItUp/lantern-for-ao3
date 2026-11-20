package app.ao3reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

    private companion object {
        const val MIN_SP = 14
        const val MAX_SP = 28
        const val DEFAULT_SP = 18
        val FONT_SIZE = intPreferencesKey("font_size_sp")
        val FONT = stringPreferencesKey("font_family")
    }
}
