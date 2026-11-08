package io.github.cadenh.wristrature.tile

import android.os.Looper
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import io.github.cadenh.wristrature.WristratureApp
import io.github.cadenh.wristrature.complication.ProgressRingComplicationService
import io.github.cadenh.wristrature.complication.ProgressTextComplicationService
import io.github.cadenh.wristrature.core.book.Anchor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The tile and the complications, run for real with a book that was started: they must build without an error. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class WatchSurfacesTest {
    private val app: WristratureApp = ApplicationProvider.getApplicationContext()

    private fun startedBook(): String = runBlocking {
        val b = app.graph.library.importDemo()
        app.graph.library.markOpened(b.id)
        app.graph.library.savePosition(b.id, Anchor(8, 0, 0), 0.34, "/body/DocFragment[9]/body/div/p[1]/text().0", unsent = false)
        b.id
    }

    private fun request(): RequestBuilders.TileRequest = RequestBuilders.TileRequest.Builder()
        .setDeviceConfiguration(
            DeviceParametersBuilders.DeviceParameters.Builder().setScreenWidthDp(204).setScreenHeightDp(204).setScreenDensity(2f)
                .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
                .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
                .build(),
        ).build()

    @Test fun theTileUsesTheAppColorsAndNotTheLibraryDefaults() {
        startedBook()
        val service = Robolectric.setupService(BookTileService::class.java)
        val tile = tileOf(service, request())
        waitFor { tile.isDone }
        val bytes = tile.get().tileTimeline!!.timelineEntries[0].layout!!.toByteArray()
        val primary = io.github.cadenh.wristrature.ui.theme.AppColors
            .of(io.github.cadenh.wristrature.ui.theme.AppPalette.EMBER)
            .primary.toArgb().toLong() and 0xFFFFFFFFL
        // The color is stored as a protobuf varint; look for those bytes.
        val varint = ArrayList<Byte>().also { out ->
            var v = primary
            while (v >= 0x80) { out.add(((v and 0x7F) or 0x80).toByte()); v = v shr 7 }
            out.add(v.toByte())
        }.toByteArray()
        fun contains(hay: ByteArray, needle: ByteArray) =
            (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }
        assertTrue("the button is in the palette's primary color", contains(bytes, varint))
    }

    @Test fun theTileBuildsForAStartedBookAndForAnEmptyLibrary() {
        val service = Robolectric.setupService(BookTileService::class.java)
        val empty = tileOf(service, request())
        waitFor { empty.isDone }
        assertNotNull(empty.get().tileTimeline)

        startedBook()
        val tile = tileOf(service, request())
        waitFor { tile.isDone }
        val t = tile.get()
        assertNotNull(t.tileTimeline)
        assertTrue("the resources version names the book", t.resourcesVersion.isNotEmpty() && t.resourcesVersion != "none")
        val res = resourcesOf(service)
        waitFor { res.isDone }
        assertEquals(t.resourcesVersion, res.get().version)
    }

    @Test fun theComplicationsShowThePercent() {
        startedBook()
        val ring = Robolectric.setupService(ProgressRingComplicationService::class.java)
        val text = Robolectric.setupService(ProgressTextComplicationService::class.java)
        assertNotNull(ring.getPreviewData(ComplicationType.RANGED_VALUE))
        assertNotNull(text.getPreviewData(ComplicationType.SHORT_TEXT))
        val ringData = runBlocking { ring.onComplicationRequest(ComplicationRequest(1, ComplicationType.RANGED_VALUE, false)) }
        val textData = runBlocking { text.onComplicationRequest(ComplicationRequest(1, ComplicationType.SHORT_TEXT, false)) }
        assertEquals(ComplicationType.RANGED_VALUE, ringData!!.type)
        assertEquals(ComplicationType.SHORT_TEXT, textData!!.type)
        // A type the source does not support gets nothing, not a crash.
        assertEquals(null, runBlocking { ring.onComplicationRequest(ComplicationRequest(1, ComplicationType.LONG_TEXT, false)) })
    }

    // Both are protected in the service; the system calls them, so the test does too.
    @Suppress("UNCHECKED_CAST")
    private fun tileOf(s: BookTileService, r: RequestBuilders.TileRequest) =
        BookTileService::class.java
            .getDeclaredMethod("onTileRequest", RequestBuilders.TileRequest::class.java)
            .apply { isAccessible = true }
            .invoke(s, r) as com.google.common.util.concurrent.ListenableFuture<androidx.wear.tiles.TileBuilders.Tile>

    @Suppress("UNCHECKED_CAST")
    private fun resourcesOf(s: BookTileService) =
        BookTileService::class.java
            .getDeclaredMethod("onTileResourcesRequest", RequestBuilders.ResourcesRequest::class.java)
            .apply { isAccessible = true }
            .invoke(s, RequestBuilders.ResourcesRequest.Builder().build())
                as com.google.common.util.concurrent.ListenableFuture<androidx.wear.protolayout.ResourceBuilders.Resources>

    private fun waitFor(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("timed out", cond())
    }
}
