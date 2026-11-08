package io.github.cadenh.wristrature.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.cadenh.wristrature.core.opds.OpdsResult
import io.github.cadenh.wristrature.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
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
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CatalogRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var library: LibraryRepository
    private lateinit var settings: SettingsStore
    private lateinit var catalog: CatalogRepository
    private lateinit var server: MockWebServer

    @Before fun setUp() = runBlocking {
        db = AppDatabase.inMemory(context)
        root = Files.createTempDirectory("catalog").toFile()
        library = LibraryRepository(root, db.library())
        settings = SettingsStore(PreferenceDataStoreFactory.create { File(root, "settings.preferences_pb") })
        server = MockWebServer().also { it.start() }
        catalog = CatalogRepository(
            settings,
            MemorySecrets(Secrets(opdsPassword = "pw")),
            library,
            File(root, "downloads"),
        ) { OkHttpClient() }
        settings.update { it.copy(servers = it.servers.copy(opdsUrl = server.url("/opds").toString(), opdsUser = "reader")) }
    }

    @After fun tearDown() {
        server.close()
        db.close()
        root.deleteRecursively()
    }

    private fun feed(updated: String, length: Long) = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opds="http://opds-spec.org/2010/catalog">
  <title>Recent</title>
  <entry>
    <title>Alice</title><id>urn:uuid:alice</id><updated>$updated</updated>
    <author><name>Lewis Carroll</name></author>
    <link rel="http://opds-spec.org/acquisition" href="/opds/download/1/epub/" length="$length" type="application/epub+zip"/>
  </entry>
</feed>"""

    private val epub: ByteArray by lazy {
        LibraryRepository::class.java.classLoader!!.getResourceAsStream(LibraryRepository.DEMO_RESOURCE)!!.use { it.readBytes() }
    }

    private fun serveFeed(updated: String) = server.enqueue(
        MockResponse.Builder()
            .code(200)
            .body(feed(updated, epub.size.toLong()))
            .setHeader("Content-Type", "application/atom+xml;profile=opds-catalog;kind=acquisition")
            .build(),
    )

    private fun serveFile(bytes: ByteArray = epub) = server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())

    @Test fun theLoginIsSentAsABasicHeader() = runBlocking {
        serveFeed("2024-01-01T00:00:00+00:00")
        assertTrue(catalog.root() is OpdsResult.Ok)
        val auth = server.takeRequest().headers["Authorization"]!!
        assertEquals("Basic " + Base64.getEncoder().encodeToString("reader:pw".toByteArray()), auth)
    }

    @Test fun noCatalogMeansNullNotAnError() = runBlocking {
        settings.update { it.copy(servers = it.servers.copy(opdsUrl = "")) }
        assertNull(catalog.root())
        assertNull(catalog.feed("http://x/"))
    }

    @Test fun aDownloadIsImportedAndRemembersWhereItCameFrom() = runBlocking {
        serveFeed("2024-01-01T00:00:00+00:00")
        val book = (catalog.root() as OpdsResult.Ok).value.books.single()
        assertEquals(book, catalog.book("urn:uuid:alice"))
        serveFile()
        val done = catalog.download(book) { _, _ -> } as DownloadResult.Done
        assertEquals("Alice's Adventures in Wonderland", done.book.title)
        assertEquals("urn:uuid:alice", done.book.opdsId)
        assertEquals("2024-01-01T00:00:00+00:00", done.book.opdsUpdated)
        assertEquals("urn:uuid:alice", library.byOpdsId("urn:uuid:alice")!!.opdsId)
        assertFalse("nothing changed on the server", catalog.changed(done.book, book))
        assertFalse("the scratch file is gone", File(root, "downloads/download.epub").exists())
    }

    @Test fun aBookEditedOnTheServerIsFoundChangedAndKeepingItSilencesTheNotice() = runBlocking {
        serveFeed("2024-01-01T00:00:00+00:00")
        val first = (catalog.root() as OpdsResult.Ok).value.books.single()
        serveFile()
        val stored = (catalog.download(first) { _, _ -> } as DownloadResult.Done).book
        serveFeed("2024-06-01T00:00:00+00:00")
        val edited = (catalog.root() as OpdsResult.Ok).value.books.single()
        assertTrue(catalog.changed(stored, edited))
        catalog.keepCopy(stored, edited)
        assertFalse(catalog.changed(library.book(stored.id)!!, edited))
    }

    @Test fun aFileThatIsNotAnEpubFailsCleanlyAndLeavesNoBook() = runBlocking {
        serveFeed("2024-01-01T00:00:00+00:00")
        val book = (catalog.root() as OpdsResult.Ok).value.books.single()
        serveFile("this is not an epub".toByteArray())
        val r = catalog.download(book) { _, _ -> }
        assertTrue("got $r", r is DownloadResult.Failed)
        assertNull(library.byOpdsId("urn:uuid:alice"))
        assertEquals(0, library.all().first().size)
    }

    @Test fun aBookWithoutAnEpubIsSaidSo() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>PDF only</title><id>urn:x</id><updated>2024</updated>
                   <link rel="http://opds-spec.org/acquisition" href="/p.pdf" type="application/pdf"/></entry></feed>""",
            ).build(),
        )
        val book = (catalog.root() as OpdsResult.Ok).value.books.single()
        assertEquals(DownloadResult.NoEpub, catalog.download(book) { _, _ -> })
        assertNotNull(book)
    }

    @Test fun searchingForAnAuthorWithASpaceReturnsTheirBooks() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><title>Root</title>
  <link rel="search" href="/opds/search/{searchTerms}" type="application/atom+xml"/>
  <entry><title>Authors</title><id>a</id><link rel="subsection" href="/opds/author" type="application/atom+xml"/></entry>
</feed>""",
            ).setHeader("Content-Type", "application/atom+xml;profile=opds-catalog;kind=navigation").build(),
        )
        serveFeed("2024-01-01T00:00:00+00:00")
        val r = catalog.search("Lewis Carroll")
        assertTrue("got $r", r is OpdsResult.Ok)
        assertEquals("Alice", (r as OpdsResult.Ok).value.books.first().title)
        server.takeRequest()
        assertEquals("/opds/search/Lewis%20Carroll", server.takeRequest().url.encodedPath)
    }
}
