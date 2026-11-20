package app.ao3reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.ao3reader.R
import app.ao3reader.data.ReaderFont

/** Weights the reader uses; each is a named instance of the bundled variable font. */
private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private fun variable(res: Int, style: FontStyle = FontStyle.Normal): List<Font> = WEIGHTS.map { w ->
    Font(res, weight = w, style = style, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
}

private val Literata = FontFamily(variable(R.font.literata) + variable(R.font.literata_italic, FontStyle.Italic))
private val Inter = FontFamily(variable(R.font.inter))
private val JetBrainsMono = FontFamily(variable(R.font.jetbrains_mono))

fun ReaderFont.family(): FontFamily = when (this) {
    ReaderFont.SYSTEM -> FontFamily.Default
    ReaderFont.SERIF -> Literata
    ReaderFont.SANS -> Inter
    ReaderFont.MONO -> JetBrainsMono
}

fun ReaderFont.label(): String = when (this) {
    ReaderFont.SYSTEM -> "System"
    ReaderFont.SERIF -> "Literata"
    ReaderFont.SANS -> "Inter"
    ReaderFont.MONO -> "Mono"
}

/** Body text style for the reader: chosen family and size, line height 1.5x. */
@Composable
fun rememberReaderTextStyle(font: ReaderFont, sizeSp: Int): TextStyle = remember(font, sizeSp) {
    TextStyle(fontFamily = font.family(), fontSize = sizeSp.sp, lineHeight = 1.5.em)
}

/** Notes and the work header's metadata use a smaller size of the same style. */
fun TextStyle.forNotes(): TextStyle = copy(fontSize = fontSize * 0.9f)
