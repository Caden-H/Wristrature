package io.github.cadenh.wristrature.reader

import android.content.res.AssetManager
import android.graphics.Typeface

/** The four faces available to text: regular, bold, italic and bold italic. */
class TypefaceSet(val regular: Typeface, val bold: Typeface, val italic: Typeface, val boldItalic: Typeface) {
    fun of(isBold: Boolean, isItalic: Boolean): Typeface = when {
        isBold && isItalic -> boldItalic
        isBold -> bold
        isItalic -> italic
        else -> regular
    }

    companion object {
        private val cache = HashMap<ReaderFont, TypefaceSet>()

        /**
         * Loads [font] from the app assets, caching the result. The files are variable fonts, so one file provides both
         * regular and bold. A font without an italic file falls back to the synthetic italic of its regular face.
         */
        @Synchronized
        fun load(assets: AssetManager, font: ReaderFont): TypefaceSet = cache.getOrPut(font) {
            fun make(file: String, weight: Int): Typeface = try {
                Typeface.Builder(assets, "fonts/$file.ttf").setFontVariationSettings("'wght' $weight").setWeight(weight).build()
                    ?: Typeface.DEFAULT
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
            val regular = make(font.file, 400)
            val bold = make(font.file, 700)
            val italic = font.italicFile?.let { make(it, 400) } ?: Typeface.create(regular, Typeface.ITALIC)
            val boldItalic = font.italicFile?.let { make(it, 700) } ?: Typeface.create(bold, Typeface.ITALIC)
            TypefaceSet(regular, bold, italic, boldItalic)
        }
    }
}
