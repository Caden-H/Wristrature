package io.github.cadenh.wristrature.core.sync

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class KoSyncClientTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().also { it.start() } }

    @After fun stop() = server.close()

    private fun reply(code: Int, body: String = "") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun stock(path: String = "") = KoSyncClient(server.url(path).toString(), KoAuth.Stock("caden", "secret"))

    @Test fun `stock login sends the user and the MD5 of the password`() = runBlocking {
        reply(200, """{"authorized":"OK"}""")
        assertTrue(stock().authorize() is KoResult.Ok)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/users/auth", r.target)
        assertEquals("caden", r.headers["x-auth-user"])
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", r.headers["x-auth-key"])
        assertEquals("application/vnd.koreader.v1+json", r.headers["Accept"])
        assertNull(r.headers["Authorization"])
    }

    @Test fun `basic login sends an Authorization header and no x-auth headers`() = runBlocking {
        reply(200)
        val c = KoSyncClient(server.url("/").toString(), KoAuth.Basic("caden", "pä:ss#word"))
        assertTrue(c.authorize() is KoResult.Ok)
        val r = server.takeRequest()
        val expected = "Basic " + Base64.getEncoder().encodeToString("caden:pä:ss#word".toByteArray(Charsets.UTF_8))
        assertEquals(expected, r.headers["Authorization"])
        assertNull(r.headers["x-auth-user"])
    }

    @Test fun `the address may carry a path`() = runBlocking {
        reply(200)
        stock("/kosync/").authorize()
        assertEquals("/kosync/users/auth", server.takeRequest().target)
    }

    @Test fun `the user agent is ours`() = runBlocking {
        reply(200)
        stock().authorize()
        assertEquals("Wristrature (Wear OS)", server.takeRequest().headers["User-Agent"])
    }

    @Test fun `401 is Unauthorized`() = runBlocking {
        reply(401, """{"message":"Unauthorized"}""")
        assertEquals(KoResult.Unauthorized, stock().authorize())
    }

    @Test fun `get progress parses a full reply`() = runBlocking {
        reply(
            200,
            """{"document":"abc","progress":"/body/DocFragment[12]/body/div/p[3]/text().18","percentage":0.342,
               "device":"Boox Palma","device_id":"D-1","timestamp":1760000000}""",
        )
        val res = stock().getProgress("abc") as KoResult.Ok
        assertEquals("/body/DocFragment[12]/body/div/p[3]/text().18", res.value.progress)
        assertEquals(0.342, res.value.percentage, 1e-9)
        assertEquals("Boox Palma", res.value.device)
        assertEquals("D-1", res.value.deviceId)
        assertEquals(1760000000L, res.value.timestamp)
        assertEquals("/syncs/progress/abc", server.takeRequest().target)
    }

    @Test fun `get progress accepts a numeric progress and no timestamp`() = runBlocking {
        reply(200, """{"document":"abc","progress":42,"percentage":0.5,"device":"x","device_id":"y"}""")
        val res = stock().getProgress("abc") as KoResult.Ok
        assertEquals("42", res.value.progress)
        assertNull(res.value.timestamp)
    }

    @Test fun `an empty object or empty body or 404 means no place`() = runBlocking {
        reply(200, "{}")
        reply(200, "")
        reply(404, """{"message":"Not found"}""")
        repeat(3) { assertEquals(KoResult.NotFound, stock().getProgress("abc")) }
    }

    @Test fun `put progress sends the JSON body`() = runBlocking {
        reply(200, """{"document":"abc","timestamp":1}""")
        val res = stock().putProgress(KoProgress("abc", "/body/DocFragment[1]/body/p[1]/text().0", 0.01, "Pixel Watch", "W-1"))
        assertTrue(res is KoResult.Ok)
        val r = server.takeRequest()
        assertEquals("PUT", r.method)
        assertEquals("/syncs/progress", r.target)
        assertTrue(r.headers["Content-Type"]!!.startsWith("application/json"))
        val body = r.body!!.utf8()
        assertTrue(body, body.contains(""""document":"abc""""))
        assertTrue(body, body.contains(""""progress":"/body/DocFragment[1]/body/p[1]/text().0""""))
        assertTrue(body, body.contains(""""percentage":0.01"""))
        assertTrue(body, body.contains(""""device":"Pixel Watch""""))
        assertTrue(body, body.contains(""""device_id":"W-1""""))
    }

    @Test fun `register sends the MD5 of the password and maps 402`() = runBlocking {
        reply(201, """{"username":"caden"}""")
        reply(402, """{"message":"Username is already registered."}""")
        assertTrue(stock().register() is KoResult.Ok)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/users/create", r.target)
        assertNull(r.headers["x-auth-user"])
        assertTrue(r.body!!.utf8().contains("5ebe2294ecd0e0f08eab7690d2a6ee69"))
        assertEquals(KoResult.UserExists, stock().register())
    }

    @Test fun `a document ID with odd characters is escaped in the path`() = runBlocking {
        reply(200, "{}")
        stock().getProgress("a b/c")
        assertEquals("/syncs/progress/a%20b%2Fc", server.takeRequest().target)
    }

    @Test fun `other status codes are server errors`() = runBlocking {
        reply(500, "boom")
        val res = stock().authorize()
        assertTrue(res is KoResult.ServerError && res.code == 500)
    }

    @Test fun `a dropped connection is a network error`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertTrue(stock().authorize() is KoResult.NetworkError)
    }

    @Test fun `a bad address is a network error`() = runBlocking {
        val c = KoSyncClient("not a url", KoAuth.Stock("a", "b"))
        assertTrue(c.authorize() is KoResult.NetworkError)
    }

    @Test fun `detect finds stock login first`() = runBlocking {
        reply(200)
        val res = KoSyncClient.detectLoginStyle(server.url("/").toString(), "caden", "secret")
        assertEquals(KoResult.Ok(LoginStyle.STOCK), res)
        assertEquals(1, server.requestCount)
    }

    @Test fun `detect falls back to basic when stock is refused`() = runBlocking {
        reply(401)
        reply(200)
        val res = KoSyncClient.detectLoginStyle(server.url("/").toString(), "caden", "secret")
        assertEquals(KoResult.Ok(LoginStyle.BASIC), res)
        server.takeRequest()
        assertTrue(server.takeRequest().headers["Authorization"]!!.startsWith("Basic "))
    }

    @Test fun `detect reports Unauthorized when both are refused`() = runBlocking {
        reply(401)
        reply(401)
        assertEquals(KoResult.Unauthorized, KoSyncClient.detectLoginStyle(server.url("/").toString(), "caden", "bad"))
    }

    @Test fun `detect reports a network error without trying twice`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val res = KoSyncClient.detectLoginStyle(server.url("/").toString(), "caden", "secret")
        assertTrue(res is KoResult.NetworkError)
        assertEquals(1, server.requestCount)
    }
}
