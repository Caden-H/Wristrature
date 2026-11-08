package io.github.cadenh.wristrature.ui.reader

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.cadenh.wristrature.WristratureApp
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.reader.ReaderTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The reader's logic driven for real: the demo book, the real database, the real engine. No server is set up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReaderViewModelTest {
    private val app: WristratureApp = ApplicationProvider.getApplicationContext()
    private val graph get() = app.graph
    private val settings = AppSettings()
    private lateinit var vm: ReaderViewModel
    private lateinit var bookId: String

    private fun await(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Before fun open() {
        bookId = runBlocking { graph.library.importDemo().id }
        vm = ReaderViewModel.Factory(app, bookId).create(ReaderViewModel::class.java)
        vm.start(settings)
        await("the book to open") { vm.ready }
        vm.configure(settings.reader, 408, settings, ReaderTheme.BLACK)
        await("the first page to be laid out") { vm.engine.isReady }
    }

    @After fun close() {
        // Closing the view model saves and sends in the app's own scope.
        runCatching { vm.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm) }
    }

    @Test fun theBookOpensAtTheStartWithItsTitleAndNoEndScreen() {
        assertEquals("Alice's Adventures in Wonderland", vm.book!!.title)
        assertNull(vm.ending)
        assertEquals(0.0, vm.progress.bookFraction, 0.02)
    }

    @Test fun scrollingForwardMovesTheProgressAndASavedPlaceFollows() {
        repeat(40) { vm.scrollBy(vm.pageDistance()); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(15) }
        await("progress to move") { vm.progress.bookFraction > 0.01 }
        vm.flush()
        // The save runs in the app's own scope, so wait until what is stored matches what is on screen.
        await("the place to be saved") {
            val p = runBlocking { graph.library.position(bookId) }
            p != null && kotlin.math.abs(p.percent - vm.progress.bookFraction) < 0.02
        }
        val saved = runBlocking { graph.library.position(bookId)!! }
        assertEquals(vm.progress.bookFraction, saved.percent, 0.02)
        assertTrue("the place is a KOReader XPointer", saved.progress.startsWith("/body/DocFragment["))
    }

    @Test fun goingToAPlaceAndBackReturnsToWhereTheReaderWas() {
        vm.jumpToFraction(0.5)
        await("the jump") { kotlin.math.abs(vm.progress.bookFraction - 0.5) < 0.05 }
        assertTrue("a jump leaves a way back", vm.card is SyncCard.Back)
        vm.goBack()
        await("the way back") { vm.progress.bookFraction < 0.05 }
        assertNull(vm.card)
    }

    @Test fun everyJumpAddsAWayBackAndGoBackWalksThemDown() {
        assertNull(vm.backPercent())
        vm.jumpToFraction(0.3)
        await("the first jump") { kotlin.math.abs(vm.progress.bookFraction - 0.3) < 0.05 }
        vm.jumpToFraction(0.6)
        await("the second jump") { kotlin.math.abs(vm.progress.bookFraction - 0.6) < 0.05 }
        assertEquals(2, vm.jumpBack.size)
        vm.jumpBackOne()
        await("back to 30%") { kotlin.math.abs(vm.progress.bookFraction - 0.3) < 0.05 }
        assertEquals(1, vm.jumpBack.size)
        vm.jumpBackOne()
        await("back to the start") { vm.progress.bookFraction < 0.05 }
        assertTrue(vm.jumpBack.isEmpty())
        assertNull(vm.backPercent())
    }

    @Test fun pushingPastTheEndOpensTheFinishedScreenAndMarkingSavesAHundredPercent() {
        vm.jumpToFraction(1.0)
        await("the end of the book") { vm.progress.bookFraction > 0.97 && vm.engine.isReady }
        var guard = 0
        while (vm.ending == null && guard++ < 60) {
            vm.scrollBy(vm.pageDistance())
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
        assertEquals(ReaderViewModel.Ending.REACHED, vm.ending)
        vm.markFinished()
        await("the finished mark") { runBlocking { graph.library.position(bookId) }?.percent == 1.0 }
        assertTrue(runBlocking { graph.library.book(bookId)!!.finished })
        assertEquals(ReaderViewModel.Ending.MARKED, vm.ending)
        vm.dismissEnding()
        assertNull(vm.ending)
    }

    @Test fun theMenuListsTheContentsOfTheBook() {
        val toc = vm.toc()
        assertTrue(toc.size > 10)
        assertNotNull(toc.first().title)
        assertFalse(vm.lastSyncedText() != null && vm.lastSyncedText()!!.isEmpty())
    }
}
