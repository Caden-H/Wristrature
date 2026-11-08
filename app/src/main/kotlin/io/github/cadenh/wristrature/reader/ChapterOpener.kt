package io.github.cadenh.wristrature.reader

import android.graphics.Path
import android.text.style.ReplacementSpan
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/**
 * A chapter heading such as "CHAPTER I. Down the Rabbit-Hole" or "12. Letters" split into a numeral and a title. The
 * numeral is shown in a round badge above the title. [prefixEnd] is the offset where the title starts in the heading
 * text; the text itself is not modified, so offsets into it remain valid.
 */
data class Opener(val prefixEnd: Int, val numeral: String)

object ChapterOpener {
    private val word = "(?:chapter|part|book|letter|canto|act|scene|section)"
    // The negative lookahead prevents "Chapter 12" from matching as numeral 1 with title "2".
    private val number = "(?:[0-9]{1,3}|[IVXLCDM]{1,7}|[ivxlcdm]{1,7}(?=[.:)]))(?![0-9A-Za-z])"

    // With a chapter word the number may be followed by a space or punctuation; without one it requires punctuation, so a
    // heading like "I Wandered Lonely" is not read as chapter I.
    private val withWord = Regex("""^(\s*$word\s+($number)\s*[.:)\-–—]*\s*)(\S.*)$""", RegexOption.IGNORE_CASE)
    private val bare = Regex("""^(\s*($number)\s*[.:)\-–—]\s*)(\S.*)$""")

    fun parse(text: String): Opener? {
        val t = text.replace('\n', ' ')
        val m = withWord.find(t)?.takeIf { isNumeral(it.groupValues[2]) }
            ?: bare.find(t)?.takeIf { isNumeral(it.groupValues[2]) }
            ?: return null
        val prefix = m.groupValues[1]
        val rest = m.groupValues[3]
        if (rest.none { it.isLetterOrDigit() }) return null
        return Opener(prefix.length, m.groupValues[2].uppercase())
    }

    private fun isNumeral(s: String) = s.all { it.isDigit() } || s.uppercase().all { it in "IVXLCDM" }

    /** Badge outline: a nine-lobed rounded star inscribed in a circle of radius 1 around (0, 0). Built once. */
    val cookie: Path by lazy {
        RoundedPolygon.star(9, innerRadius = 0.86f, rounding = CornerRounding(0.28f), innerRounding = CornerRounding(0.28f)).toPath()
    }
}

/** Replaces the numeral part of a heading with nothing, leaving room for the badge. */
class HiddenSpan : ReplacementSpan() {
    override fun getSize(
        paint: android.graphics.Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: android.graphics.Paint.FontMetricsInt?,
    ) = 0

    override fun draw(
        canvas: android.graphics.Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: android.graphics.Paint,
    ) = Unit
}
