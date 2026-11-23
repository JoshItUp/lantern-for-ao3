package io.github.joshitup.lantern.ui.reader

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
import io.github.joshitup.lantern.R
import io.github.joshitup.lantern.data.ReaderFont

/** Weights the reader uses; each is a named instance of the bundled variable font. */
private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private fun variable(res: Int, style: FontStyle = FontStyle.Normal): List<Font> = WEIGHTS.map { w ->
    Font(res, weight = w, style = style, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
}

/** A static font's files: Normal and Medium use [regular], SemiBold and Bold use [bold]. */
private fun static(regular: Int, bold: Int, style: FontStyle): List<Font> = WEIGHTS.map { w ->
    Font(if (w < FontWeight.SemiBold) regular else bold, weight = w, style = style)
}

private val Literata = FontFamily(variable(R.font.literata) + variable(R.font.literata_italic, FontStyle.Italic))
private val Inter = FontFamily(variable(R.font.inter) + variable(R.font.inter_italic, FontStyle.Italic))
private val Atkinson = FontFamily(variable(R.font.atkinson) + variable(R.font.atkinson_italic, FontStyle.Italic))
private val OpenDyslexic = FontFamily(
    static(R.font.opendyslexic_regular, R.font.opendyslexic_bold, FontStyle.Normal) +
        static(R.font.opendyslexic_italic, R.font.opendyslexic_bold_italic, FontStyle.Italic),
)
private val JetBrainsMono = FontFamily(variable(R.font.jetbrains_mono) + variable(R.font.jetbrains_mono_italic, FontStyle.Italic))

fun ReaderFont.family(): FontFamily = when (this) {
    ReaderFont.SYSTEM -> FontFamily.Default
    ReaderFont.SERIF -> Literata
    ReaderFont.SANS -> Inter
    ReaderFont.ATKINSON -> Atkinson
    ReaderFont.DYSLEXIC -> OpenDyslexic
    ReaderFont.MONO -> JetBrainsMono
}

fun ReaderFont.label(): String = when (this) {
    ReaderFont.SYSTEM -> "System"
    ReaderFont.SERIF -> "Literata"
    ReaderFont.SANS -> "Inter"
    ReaderFont.ATKINSON -> "Atkinson"
    ReaderFont.DYSLEXIC -> "OpenDyslexic"
    ReaderFont.MONO -> "Mono"
}

/** Body text style for the reader: chosen family and size, line height 1.5x. */
@Composable
fun rememberReaderTextStyle(font: ReaderFont, sizeSp: Int): TextStyle = remember(font, sizeSp) {
    TextStyle(fontFamily = font.family(), fontSize = sizeSp.sp, lineHeight = 1.5.em)
}

/** Notes and the work header's metadata use a smaller size of the same style. */
fun TextStyle.forNotes(): TextStyle = copy(fontSize = fontSize * 0.9f)
