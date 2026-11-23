package io.github.joshitup.lantern.ui.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.joshitup.lantern.data.ReaderFont
import io.github.joshitup.lantern.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReaderFontsTest {
    @Test fun entries_inPickerOrder() {
        assertEquals(listOf("System", "Literata", "Inter", "Atkinson", "OpenDyslexic", "Mono"), ReaderFont.entries.map { it.label() })
    }

    @Test fun everyBundledFont_hasItalic() {
        for (f in ReaderFont.entries - ReaderFont.SYSTEM) {
            val fonts = (f.family() as FontListFontFamily).fonts
            for (style in listOf(FontStyle.Normal, FontStyle.Italic)) for (weight in listOf(FontWeight.Normal, FontWeight.Bold)) {
                assertTrue("$f $style $weight", fonts.any { it.style == style && it.weight == weight })
            }
        }
    }

    @Test fun licences_shipForEveryBundledFont() {
        val assets = RuntimeEnvironment.getApplication().assets
        val expected = listOf("Literata", "Inter", "JetBrainsMono", "AtkinsonHyperlegibleNext", "OpenDyslexic").map { "$it-OFL.txt" }
        assertEquals(expected.sorted(), assets.list("licenses")!!.sorted())
        for (name in expected) {
            val text = assets.open("licenses/$name").bufferedReader().readText()
            assertTrue(name, text.startsWith("Copyright"))
            assertTrue(name, text.contains("SIL OPEN FONT LICENSE"))
        }
    }

    private class MemoryPrefs : DataStore<Preferences> {
        private val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(flow.value).also { flow.value = it }
    }

    @Test fun savedNames_stillLoad() = runTest {
        val prefs = MemoryPrefs()
        val store = SettingsStore(prefs)
        val key = stringPreferencesKey("font_family")
        for ((saved, expected) in listOf(
            "SERIF" to ReaderFont.SERIF, "SANS" to ReaderFont.SANS, "MONO" to ReaderFont.MONO,
            "SYSTEM" to ReaderFont.SYSTEM, "GONE" to ReaderFont.SYSTEM,
        )) {
            prefs.edit { it[key] = saved }
            assertEquals(saved, expected, store.fontFamily.first())
        }
    }
}
