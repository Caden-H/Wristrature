package io.github.cadenh.wristrature.core.opds

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class OpdsTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().also { it.start() } }

    @After fun stop() = server.close()

    private fun reply(code: Int, body: String = "", type: String = "application/atom+xml;profile=opds-catalog;kind=navigation") =
        server.enqueue(MockResponse.Builder().code(code).body(body).setHeader("Content-Type", type).build())

    // A root feed shaped like the one Calibre-Web(-Automated) serves.
    private val root = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opds="http://opds-spec.org/2010/catalog"
      xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:thr="http://purl.org/syndication/thread/1.0">
  <id>urn:uuid:2853dacf-ed79-42f5-8e8a-a7bb3d1ae6a2</id>
  <updated>2026-10-01T10:00:00+00:00</updated>
  <title>Calibre-Web</title>
  <link rel="start" href="/opds/" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
  <link rel="self" href="/opds/" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
  <link rel="search" href="/opds/osd" type="application/opensearchdescription+xml"/>
  <entry>
    <title>Recently added Books</title>
    <content type="text">Books ordered by date added</content>
    <link href="/opds/new" type="application/atom+xml;profile=opds-catalog;kind=acquisition" rel="http://opds-spec.org/sort/new"/>
    <id>/opds/new</id>
  </entry>
  <entry>
    <title>Authors</title>
    <content type="text">Books ordered by author</content>
    <link href="/opds/author" type="application/atom+xml;profile=opds-catalog;kind=navigation" thr:count="112"/>
    <id>/opds/author</id>
  </entry>
  <entry>
    <title>Shelves</title>
    <content type="text">Your shelves</content>
    <link href="/opds/shelfindex" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
    <id>/opds/shelfindex</id>
  </entry>
</feed>"""

    private val books = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
  <id>urn:uuid:new</id><updated>2026-10-01T10:00:00+00:00</updated><title>Recently added Books</title>
  <link rel="self" href="/opds/new" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
  <link rel="next" href="/opds/new?offset=60" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
  <opensearch:totalResults>101</opensearch:totalResults>
  <entry>
    <title>Frankenstein</title>
    <id>urn:uuid:5e5d0c1a</id>
    <updated>2026-09-20T08:00:00+00:00</updated>
    <author><name>Mary Shelley</name></author>
    <dcterms:language>eng</dcterms:language>
    <dcterms:issued>1818</dcterms:issued>
    <category scheme="http://calibre.kovidgoyal.net/2009/menu" term="Horror" label="Horror"/>
    <link rel="http://opds-spec.org/acquisition" href="/opds/download/12/kepub/" length="900000" type="application/kepub+zip"/>
    <link rel="http://opds-spec.org/acquisition" href="/opds/download/12/epub/" length="800000" type="application/epub+zip"/>
    <link rel="http://opds-spec.org/image" href="/opds/cover/12" type="image/jpeg"/>
    <link rel="http://opds-spec.org/image/thumbnail" href="/opds/thumb_240_240/12" type="image/jpeg"/>
    <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>A young scientist builds a creature.</p><p>It goes badly.</p></div></content>
  </entry>
</feed>"""

    private val osd = """<?xml version="1.0" encoding="UTF-8"?>
<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/"><ShortName>Calibre-Web</ShortName>
<Url type="application/atom+xml" template="/opds/search/{searchTerms}"/></OpenSearchDescription>"""

    private fun client(login: OpdsLogin? = null, path: String = "/opds/") = OpdsClient(server.url(path).toString(), login)

    @Test fun `the root lists categories with their targets and counts`() = runBlocking {
        reply(200, root)
        val feed = (client().root() as OpdsResult.Ok).value
        assertEquals("Calibre-Web", feed.title)
        assertEquals(3, feed.navigation.size)
        val authors = feed.navigation.first { it.title == "Authors" }
        assertEquals(server.url("/opds/author").toString(), authors.href)
        assertEquals(112, authors.count)
        assertEquals("Books ordered by author", authors.summary)
        assertEquals("http://opds-spec.org/sort/new", feed.navigation.first { it.title.startsWith("Recently") }.rel)
        assertEquals(server.url("/opds/osd").toString(), feed.searchLink)
        assertTrue(feed.searchIsDescription)
        assertTrue(feed.books.isEmpty())
    }

    @Test fun `a book entry has authors, covers, formats and a clean summary`() = runBlocking {
        reply(200, books, "application/atom+xml;profile=opds-catalog;kind=acquisition")
        val feed = (client().feed(server.url("/opds/new").toString()) as OpdsResult.Ok).value
        val b = feed.books.single()
        assertEquals("Frankenstein", b.title)
        assertEquals(listOf("Mary Shelley"), b.authors)
        assertEquals("eng", b.language)
        assertEquals("1818", b.published)
        assertEquals(listOf("Horror"), b.categories)
        assertEquals(server.url("/opds/cover/12").toString(), b.coverUrl)
        assertEquals(server.url("/opds/thumb_240_240/12").toString(), b.thumbnailUrl)
        assertEquals("A young scientist builds a creature. It goes badly.", b.summary)
        assertEquals(2, b.acquisitions.size)
        assertEquals(101, feed.totalResults)
        assertEquals(server.url("/opds/new?offset=60").toString(), feed.next)
    }

    @Test fun `EPUB is picked before KEPUB so every device gets the same file`() = runBlocking {
        reply(200, books)
        val b = (client().feed(server.url("/opds/new").toString()) as OpdsResult.Ok).value.books.single()
        assertEquals(server.url("/opds/download/12/epub/").toString(), OpdsClient.pick(b)!!.href)
        assertEquals(server.url("/opds/download/12/kepub/").toString(), OpdsClient.pick(b, listOf("application/kepub+zip"))!!.href)
        assertNull(OpdsClient.pick(b, listOf("application/pdf")))
    }

    @Test fun `a book edited on the server is seen as changed`() = runBlocking {
        reply(200, books)
        val b = (client().feed(server.url("/opds/new").toString()) as OpdsResult.Ok).value.books.single()
        val epub = OpdsClient.pick(b)
        // Nothing was stored: no change is known.
        assertEquals(false, OpdsClient.changed(null, null, b, epub))
        // Same update time and size: the same file.
        assertEquals(false, OpdsClient.changed(b.updated, 800000, b, epub))
        // A newer update time, or a different size, is a different file.
        assertEquals(true, OpdsClient.changed("2000-01-01T00:00:00+00:00", 800000, b, epub))
        assertEquals(true, OpdsClient.changed(b.updated, 123, b, epub))
    }

    @Test fun `login goes in a header, not in the URL`() = runBlocking {
        reply(200, root)
        client(OpdsLogin("reader", "pa:ss#word")).root()
        val r = server.takeRequest()
        val expected = "Basic " + Base64.getEncoder().encodeToString("reader:pa:ss#word".toByteArray())
        assertEquals(expected, r.headers["Authorization"])
        assertEquals("/opds/", r.target)
        assertEquals("Wristrature (Wear OS)", r.headers["User-Agent"])
    }

    @Test fun `401 and 404 are named`() = runBlocking {
        reply(401)
        reply(404)
        assertEquals(OpdsResult.Unauthorized, client().root())
        assertEquals(OpdsResult.NotFound, client().feed(server.url("/x").toString()))
    }

    @Test fun `a page that is not a catalog is reported`() = runBlocking {
        reply(200, "<html><body>Login</body></html>", "text/html")
        reply(200, "{\"metadata\":{}}", "application/opds+json")
        assertTrue(client().root() is OpdsResult.NotACatalog)
        assertTrue(client().root() is OpdsResult.NotACatalog)
    }

    @Test fun `search reads the description then asks the catalog`() = runBlocking {
        reply(200, root)
        reply(200, osd, "application/opensearchdescription+xml")
        reply(200, books)
        val c = client()
        val rootFeed = (c.root() as OpdsResult.Ok).value
        val res = (c.search(rootFeed, "frank enstein&co") as OpdsResult.Ok).value
        assertEquals(1, res.books.size)
        server.takeRequest()
        assertEquals("/opds/osd", server.takeRequest().target)
        assertEquals("/opds/search/frank%20enstein%26co", server.takeRequest().target)
    }

    @Test fun `search templates with optional parts are filled`() {
        assertEquals(
            "https://x/s?q=a%20b",
            OpdsParser.fillSearch("https://x/s?q={searchTerms}{&startPage?}", "a b")
                .replace("&", "")
                .let { if (it.endsWith("?")) it else it },
        )
        assertEquals("/s?q=hi", OpdsParser.fillSearch("/s?q={searchTerms}", "hi"))
    }

    @Test fun `a search template in the path is filled and not escaped`() = runBlocking {
        val feedWithTemplate = root.replace("""<link rel="search" href="/opds/osd" type="application/opensearchdescription+xml"/>""",
            """<link rel="search" href="/opds/search/{searchTerms}" type="application/atom+xml"/>""")
        reply(200, feedWithTemplate)
        reply(200, books)
        val c = client()
        val rootFeed = (c.root() as OpdsResult.Ok).value
        assertTrue(c.search(rootFeed, "Lewis Carroll") is OpdsResult.Ok)
        server.takeRequest()
        assertEquals("/opds/search/Lewis%20Carroll", server.takeRequest().target)
    }

    @Test fun `a search description that is not XML is reported and never thrown`() = runBlocking {
        reply(200, root)
        reply(200, "<html>nothing here</html", "text/html")
        val c = client()
        val rootFeed = (c.root() as OpdsResult.Ok).value
        assertTrue(c.search(rootFeed, "anyone") is OpdsResult.NotACatalog)
    }

    @Test fun `a direct search link is used as a template`() = runBlocking {
        val feedWithTemplate = root.replace("""<link rel="search" href="/opds/osd" type="application/opensearchdescription+xml"/>""",
            """<link rel="search" href="/opds/find?q={searchTerms}" type="application/atom+xml"/>""")
        reply(200, feedWithTemplate)
        reply(200, books)
        val c = client()
        val rootFeed = (c.root() as OpdsResult.Ok).value
        assertEquals(false, rootFeed.searchIsDescription)
        c.search(rootFeed, "alice")
        server.takeRequest()
        assertEquals("/opds/find?q=alice", server.takeRequest().target)
    }

    @Test fun `a download is stored byte for byte and shows progress`() = runBlocking {
        val payload = ByteArray(100_000) { (it * 7).toByte() }
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(payload)).build())
        val target = Files.createTempDirectory("dl").resolve("book.epub").toFile()
        var last = 0L
        val res = client().download(server.url("/opds/download/12/epub/").toString(), target) { done, _ -> last = done }
        assertTrue(res is OpdsResult.Ok)
        assertTrue(payload.contentEquals(target.readBytes()))
        assertEquals(payload.size.toLong(), last)
        assertTrue(!java.io.File(target.path + ".part").exists())
    }

    @Test fun `a download that is too large is refused and leaves nothing behind`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ByteArray(5000))).build())
        val target = Files.createTempDirectory("dl").resolve("big.epub").toFile()
        val res = client().download(server.url("/x").toString(), target, maxBytes = 1000)
        assertTrue(res is OpdsResult.ServerError)
        assertTrue(!target.exists())
        assertTrue(!java.io.File(target.path + ".part").exists())
    }

    @Test fun `relative links are resolved against the feed address`() {
        assertEquals("https://h/opds/cover/12", OpdsParser.absolute("https://h/opds/new", "/opds/cover/12"))
        assertEquals("https://h/opds/new?offset=60", OpdsParser.absolute("https://h/opds/new", "new?offset=60"))
        assertEquals("https://other/x", OpdsParser.absolute("https://h/opds/new", "https://other/x"))
    }
}
