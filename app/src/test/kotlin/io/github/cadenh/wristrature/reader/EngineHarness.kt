package io.github.cadenh.wristrature.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import io.github.cadenh.wristrature.core.book.BookImporter
import io.github.cadenh.wristrature.core.book.OpenBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Builds an engine on the demo book and draws it to a bitmap, so a test (or a developer) can look at the result. */
class EngineHarness(val settings: ReaderSettings = ReaderSettings(), val diameter: Int = 408) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val book: OpenBook
    val engine: ReaderEngine
    val columnWidth: Int
    private val lookFn: ReaderEngine.RowLook

    init {
        val epub = Files.createTempFile("alice", ".epub").toFile().apply { deleteOnExit() }
        epub.writeBytes(javaClass.getResourceAsStream("/demo/alices-adventures-in-wonderland.epub")!!.readBytes())
        val dir = Files.createTempDirectory("wr-eng").toFile()
        BookImporter.import(epub, dir)
        book = OpenBook(dir)
        // Layout runs inline in tests: Unconfined makes the "background" work finish before the call returns.
        engine = ReaderEngine(book, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        columnWidth = ReaderGeometry.columnWidth(settings.layout, settings.margin, diameter)
        val dm = context.resources.displayMetrics
        val style = LayoutStyle(
            faces = TypefaceSet.load(context.assets, settings.font),
            textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.sizeSp, dm),
            lineSpacing = settings.lineSpacing,
            width = columnWidth,
            density = dm.density,
            justify = settings.justify,
            hyphenate = settings.hyphenate,
            ink = settings.theme.ink,
            mute = settings.theme.mute,
        )
        lookFn = ReaderGeometry.look(settings.layout, diameter, diameter, columnWidth.toFloat(), style.lineHeightPx)
        engine.configure(style, diameter, (diameter * 0.24f).toInt())
    }

    companion object {
        /** The layout style for [s] on a screen [diameter] px wide. */
        fun styleFor(context: Context, s: ReaderSettings, diameter: Int): LayoutStyle {
            val dm = context.resources.displayMetrics
            return LayoutStyle(
                faces = TypefaceSet.load(context.assets, s.font),
                textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, s.sizeSp, dm),
                lineSpacing = s.lineSpacing,
                width = ReaderGeometry.columnWidth(s.layout, s.margin, diameter),
                density = dm.density,
                justify = s.justify,
                hyphenate = s.hyphenate,
                ink = s.theme.ink,
                mute = s.theme.mute,
            )
        }
    }

    fun render(): Bitmap {
        val bmp = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF2A2A2E.toInt())
        val circle = Path().apply { addCircle(diameter / 2f, diameter / 2f, diameter / 2f, Path.Direction.CW) }
        c.save()
        c.clipPath(circle)
        c.drawColor(settings.theme.paper)
        engine.draw(c, diameter, diameter, (diameter - columnWidth) / 2f, lookFn)
        c.restore()
        return bmp
    }

    fun save(name: String): File {
        val dir = File("build/engine-shots").apply { mkdirs() }
        val f = File(dir, "$name.png")
        FileOutputStream(f).use { render().compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}
