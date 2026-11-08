package io.github.cadenh.wristrature.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LibraryRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var repo: LibraryRepository
    private var clock = 1_000L

    @Before fun setUp() {
        db = AppDatabase.inMemory(context)
        root = Files.createTempDirectory("library").toFile()
        repo = LibraryRepository(root, db.library()) { clock }
    }

    @After fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun demoCopy(name: String = "copy.epub"): File {
        val f = File(root, name).also { root.mkdirs() }
        LibraryRepository::class.java.classLoader!!.getResourceAsStream(LibraryRepository.DEMO_RESOURCE)!!
            .use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    @Test fun theDemoBookImportsWithATitleAChapterCountAndACover() = runBlocking {
        val b = repo.importDemo()
        assertEquals("Alice's Adventures in Wonderland", b.title)
        assertTrue(b.chapterCount > 10)
        assertTrue(b.demo)
        assertTrue(repo.epubFile(b.id).exists())
        assertEquals(b.id, repo.all().first().single().book.id)
    }

    @Test fun importingTheSameFileTwiceKeepsOneBook() = runBlocking {
        val first = repo.importDemo()
        val again = repo.import(demoCopy(), "again.epub")
        assertEquals(first.id, again.id)
        assertEquals(1, repo.all().first().size)
    }

    @Test fun aSavedPlaceComesBackAndStaysUnsentUntilTheServerHasIt() = runBlocking {
        val b = repo.importDemo()
        repo.savePosition(b.id, Anchor(5, 12, 3), 0.34, "/body/DocFragment[6]/body/div/p[13]/text().3", unsent = true)
        assertTrue(repo.position(b.id)!!.unsent)
        assertEquals(listOf(b.id), repo.unsent().map { it.bookId })
        repo.setUnsent(b.id, false)
        // A later save that is not unsent must not hide an earlier one that never reached the server.
        repo.savePosition(b.id, Anchor(5, 13, 0), 0.35, "x", unsent = false)
        assertFalse(repo.position(b.id)!!.unsent)
        repo.savePosition(b.id, Anchor(5, 14, 0), 0.36, "y", unsent = true)
        repo.savePosition(b.id, Anchor(5, 15, 0), 0.37, "z", unsent = false)
        assertTrue(repo.position(b.id)!!.unsent)
    }

    @Test fun removingFromTheWatchFreesTheFilesButKeepsThePlaceAndTheMark() = runBlocking {
        val b = repo.importDemo()
        repo.savePosition(b.id, Anchor(2, 0, 0), 0.1, "p", unsent = false)
        repo.setFinished(b.id, true)
        assertTrue(repo.sizeOf(b.id) > 0)
        repo.removeFromWatch(b.id)
        assertEquals(0, repo.sizeOf(b.id))
        val row = repo.withPosition(b.id)!!
        assertFalse(row.book.onWatch)
        assertTrue(row.book.finished)
        assertNotNull(row.position)
    }

    @Test fun deletingTakesTheBookAndItsPlace() = runBlocking {
        val b = repo.importDemo()
        repo.savePosition(b.id, Anchor.START, 0.0, "p", unsent = false)
        repo.delete(b.id)
        assertNull(repo.book(b.id))
        assertNull(repo.position(b.id))
    }

    @Test fun recentBooksAreTheOpenedUnfinishedOnesNewestFirst() = runBlocking {
        val a = repo.importDemo()
        assertTrue("never opened: not recent", repo.recent().first().isEmpty())
        clock = 5_000
        repo.markOpened(a.id)
        assertEquals(listOf(a.id), repo.recent().first().map { it.book.id })
        repo.setFinished(a.id, true)
        assertTrue(repo.recent().first().isEmpty())
    }

    @Test fun booksCopiedWithAdbAreImportedAndTheOriginalsRemovedOnlyOnSuccess() = runBlocking {
        val folder = Files.createTempDirectory("import").toFile()
        try {
            demoCopy("good.epub").copyTo(File(folder, "good.epub"))
            File(folder, "broken.epub").writeText("this is not an epub")
            File(folder, "notes.txt").writeText("ignored")
            val added = repo.importPending(folder)
            assertEquals(1, added)
            assertFalse("the imported original is gone", File(folder, "good.epub").exists())
            assertTrue("a file that cannot be read stays where it was", File(folder, "broken.epub").exists())
            assertTrue(File(folder, "notes.txt").exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun aRedownloadedBookTakesOverThePlaceByPercentAndTheOldOneGoes() = runBlocking {
        val old = repo.importDemo()
        repo.markOpened(old.id)
        repo.setFinished(old.id, false)
        repo.savePosition(old.id, Anchor(8, 0, 0), 0.40, "old", unsent = false)
        // A "new" file with different bytes: the same EPUB with one extra byte of padding at the end of the zip comment area
        // cannot be produced here, so the new book is simulated by importing the demo under a changed entity id.
        val changed = old.copy(id = "newid", nameId = "newname", dir = "newid")
        repo.dir("newid").mkdirs()
        repo.dir(old.id).copyRecursively(repo.dir("newid"))
        db.library().upsertBook(changed)
        repo.replaceBook(old, changed)
        assertNull(repo.book(old.id))
        val moved = repo.withPosition("newid")!!
        assertNotNull(moved.position)
        assertEquals(0.40, moved.position!!.percent, 0.02)
        assertTrue("the new place is queued for the server", moved.position.unsent)
        assertEquals(old.lastOpenedAt, moved.book.lastOpenedAt)
    }

    @Test fun theSameFileUnderANewerCatalogEntryRemembersTheEntry() = runBlocking {
        repo.import(demoCopy("one.epub"), "one.epub", CatalogOrigin("urn:a", "2024-01-01", 100))
        val again = repo.import(demoCopy("two.epub"), "two.epub", CatalogOrigin("urn:a", "2024-06-01", 100))
        assertEquals("2024-06-01", again.opdsUpdated)
        assertEquals("2024-06-01", repo.byOpdsId("urn:a")!!.opdsUpdated)
    }
}
