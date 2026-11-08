package io.github.cadenh.wristrature

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text crash log in the app's external files folder, readable with `adb pull` without extra permissions:
 * `Android/data/<package>/files/crash.log`. Only the most recent crashes are kept, and nothing leaves the device.
 */
object CrashLog {
    private const val MAX_BYTES = 40_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash.log")

    internal fun write(context: Context, thread: Thread, error: Throwable) {
        val f = file(context)
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "== $stamp on ${thread.name} (v${BuildConfig.VERSION_NAME})\n$trace\n"
        // Newest first, truncated to a fixed size.
        val old = if (f.exists()) f.readText() else ""
        f.writeText((entry + old).take(MAX_BYTES))
    }

    /** Timestamp of the most recent crash, or null if there is none. */
    fun lastCrash(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        return f.useLines { lines -> lines.firstOrNull { it.startsWith("== ") }?.removePrefix("== ")?.substringBefore(" on ") }
    }
}
