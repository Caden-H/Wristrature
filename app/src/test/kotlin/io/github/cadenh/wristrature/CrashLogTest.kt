package io.github.cadenh.wristrature

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CrashLogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() { CrashLog.file(context).delete() }

    @Test fun noFileMeansNoCrash() = assertNull(CrashLog.lastCrash(context))

    @Test fun aCrashIsWrittenNewestFirstAndCutToSize() {
        CrashLog.write(context, Thread.currentThread(), IllegalStateException("first"))
        CrashLog.write(context, Thread.currentThread(), IllegalArgumentException("second"))
        val text = CrashLog.file(context).readText()
        assertTrue(text.indexOf("second") < text.indexOf("first"))
        assertTrue(CrashLog.lastCrash(context)!!.matches(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d""")))
        repeat(200) { CrashLog.write(context, Thread.currentThread(), RuntimeException("x".repeat(500))) }
        assertEquals(true, CrashLog.file(context).length() <= 40_000)
    }
}
