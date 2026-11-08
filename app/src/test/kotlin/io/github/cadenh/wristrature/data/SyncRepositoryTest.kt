package io.github.cadenh.wristrature.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.cadenh.wristrature.core.book.Anchor
import io.github.cadenh.wristrature.core.position.PositionMapper
import io.github.cadenh.wristrature.core.sync.JumpMode
import io.github.cadenh.wristrature.core.sync.LoginStyle
import io.github.cadenh.wristrature.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class SyncRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var library: LibraryRepository
    private lateinit var settings: SettingsStore
    private lateinit var sync: SyncRepository
    private lateinit var server: MockWebServer
    private var clock = 1_000_000L

    @Before fun setUp() = runBlocking {
        db = AppDatabase.inMemory(context)
        root = Files.createTempDirectory("sync").toFile()
        library = LibraryRepository(root, db.library()) { clock }
        settings = SettingsStore(PreferenceDataStoreFactory.create { File(root, "settings.preferences_pb") })
        server = MockWebServer().also { it.start() }
        sync = SyncRepository(settings, MemorySecrets(Secrets(syncPassword = "secret")), library, { OkHttpClient() }) { clock }
        configure()
    }

    @After fun tearDown() {
        server.close()
        db.close()
        root.deleteRecursively()
    }

    private suspend fun configure(mode: JumpMode = JumpMode.ASK, skip: Int = 2) {
        settings.update {
            it.copy(
                servers = it.servers.copy(syncUrl = server.url("/").toString(), syncUser = "caden", loginStyle = LoginStyle.STOCK),
                sync = it.sync.copy(jumpMode = mode, skipCheckMinutes = skip),
                deviceId = "watch-1", deviceName = "Pixel Watch",
            )
        }
    }

    private fun remote(
        percentage: Double,
        device: String = "Boox Palma",
        deviceId: String = "boox-1",
        progress: String = "/body/DocFragment[9]/body/div/p[2]/text().0",
    ) =
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"document":"d","progress":"$progress","percentage":$percentage,"device":"$device","device_id":"$deviceId","timestamp":1}""",
            ).build(),
        )

    private suspend fun openDemo() = library.importDemo().let { it to PositionMapper(library.open(it.id)) }

    @Test fun aPlaceFurtherOnAnotherDeviceIsOfferedWhenAskingIsOn() = runBlocking {
        val (book, mapper) = openDemo()
        remote(0.58)
        val out = sync.pull(book, mapper, userHasScrolled = false)
        assertTrue("got $out", out is PullOutcome.Offer)
        assertEquals(0.58, (out as PullOutcome.Offer).remote.percentage, 0.0001)
        assertEquals("Boox Palma", out.remote.device)
    }

    @Test fun aNewerPlaceFromAnotherDeviceIsOfferedEvenWhenItsPercentIsLower() = runBlocking {
        configure(mode = JumpMode.AUTO, skip = 0)
        val (book, mapper) = openDemo()
        library.savePosition(book.id, Anchor(5, 0, 0), 0.34, "mine", unsent = false)
        // Written long after the watch's place last changed, by a device that counts the percent differently.
        val later = (System.currentTimeMillis() / 1000) + 3600
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"document":"d","progress":"/body/DocFragment[9]/body/div/p[2]/text().0","percentage":0.30,"device":"Crosspoint","device_id":"x4","timestamp":$later}""",
            ).build(),
        )
        val out = sync.pull(book, mapper, userHasScrolled = false)
        assertTrue("an offer, never a jump: it may be behind. Got $out", out is PullOutcome.Offer)
    }

    @Test fun withJumpOnTheWatchJumpsBeforeTheReaderHasScrolledAndOffersAfter() = runBlocking {
        configure(mode = JumpMode.AUTO, skip = 0)
        val (book, mapper) = openDemo()
        remote(0.58)
        assertTrue(sync.pull(book, mapper, userHasScrolled = false) is PullOutcome.Jump)
        remote(0.58)
        assertTrue("never jump under the reader's thumb", sync.pull(book, mapper, userHasScrolled = true) is PullOutcome.Offer)
    }

    @Test fun aPlaceBehindOrWrittenByThisWatchChangesNothing() = runBlocking {
        configure(skip = 0)
        val (book, mapper) = openDemo()
        library.savePosition(book.id, Anchor(5, 0, 0), 0.34, "mine", unsent = false)
        remote(0.20)
        assertEquals(PullOutcome.Nothing, sync.pull(book, mapper, false))
        remote(0.90, device = "Pixel Watch", deviceId = "watch-1")
        assertEquals(PullOutcome.Nothing, sync.pull(book, mapper, false))
    }

    @Test fun aRecentCheckIsNotRepeatedUnlessForced() = runBlocking {
        val (book, mapper) = openDemo()
        remote(0.58)
        assertTrue(sync.pull(book, mapper, false) is PullOutcome.Offer)
        // The first call recorded the time; one minute later is inside the two-minute window.
        library.savePosition(book.id, Anchor.START, 0.0, "p", unsent = false)
        library.setChecked(book.id)
        clock += 60_000
        assertEquals(1, server.requestCount)
        assertEquals(PullOutcome.Skipped, sync.pull(book, mapper, false))
        assertEquals("no request was made", 1, server.requestCount)
        remote(0.58)
        assertTrue(sync.pull(book, mapper, false, force = true) is PullOutcome.Offer)
    }

    @Test fun anUnreachableServerIsOfflineAndARefusedLoginIsSaid() = runBlocking {
        configure(skip = 0)
        val (book, mapper) = openDemo()
        server.enqueue(MockResponse.Builder().code(500).build())
        assertEquals(PullOutcome.Offline, sync.pull(book, mapper, false))
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(PullOutcome.LoginRefused, sync.pull(book, mapper, false))
    }

    @Test fun aSentPlaceIsMarkedSentAndAFailedOneStaysQueued() = runBlocking {
        val (book, _) = openDemo()
        library.savePosition(book.id, Anchor(5, 12, 0), 0.34, "/body/DocFragment[6]/body/div/p[13]/text().0", unsent = true)
        server.enqueue(MockResponse.Builder().code(500).build())
        assertFalse(sync.pushStored(book.id))
        assertTrue(library.position(book.id)!!.unsent)
        // Right after a failure the next automatic send waits; a few minutes later it is tried again.
        assertFalse(sync.pushStored(book.id))
        assertEquals("no second request inside the pause", 1, server.requestCount)
        clock += 5 * 60_000
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        assertTrue(sync.pushStored(book.id))
        assertFalse(library.position(book.id)!!.unsent)
        val sent = server.takeRequest().let { server.takeRequest() }
        assertEquals("PUT", sent.method)
        assertTrue(sent.body!!.utf8().contains("\"percentage\":0.34"))
    }

    @Test fun aPlaceTheServerAlreadyHasIsNotSentAgain() = runBlocking {
        val (book, _) = openDemo()
        library.savePosition(book.id, Anchor(5, 12, 0), 0.34, "p", unsent = false)
        assertTrue(sync.pushStored(book.id))
        assertEquals("nothing was sent: another device may have sent a newer place since", 0, server.requestCount)
    }

    @Test fun theBackgroundCheckMovesThePlaceOnlyWhenJumpIsOn() = runBlocking {
        val (book, _) = openDemo()
        remote(0.58)
        assertFalse("with Ask me the background cannot ask anyone", sync.backgroundCheck(book))
        assertEquals(0, server.requestCount)
        configure(mode = JumpMode.AUTO, skip = 0)
        remote(0.58)
        assertTrue(sync.backgroundCheck(book))
        val p = library.position(book.id)
        assertNotNull(p)
        assertEquals(0.58, p!!.percent, 0.0001)
        assertFalse("it came from the server, so it is not queued to be sent", p.unsent)
    }

    @Test fun withoutASyncServerEverythingIsQuietlySkipped() = runBlocking {
        settings.update { it.copy(servers = it.servers.copy(syncUrl = "", syncUser = "")) }
        val (book, mapper) = openDemo()
        assertEquals(PullOutcome.Skipped, sync.pull(book, mapper, false))
        library.savePosition(book.id, Anchor.START, 0.1, "p", unsent = true)
        assertTrue(sync.pushStored(book.id))
        assertEquals(0, server.requestCount)
    }

    @Test fun aNewerPlaceSavedWhileAnOlderOneWasSendingStaysQueued() = runBlocking {
        val (book, _) = openDemo()
        library.savePosition(book.id, Anchor(5, 12, 0), 0.34, "old", unsent = true)
        val sentAt = library.position(book.id)!!.updatedAt
        clock += 10
        library.savePosition(book.id, Anchor(5, 20, 0), 0.40, "new", unsent = true)
        library.markSent(book.id, sentAt)
        assertTrue("the newer place was not the one sent", library.position(book.id)!!.unsent)
    }
}
