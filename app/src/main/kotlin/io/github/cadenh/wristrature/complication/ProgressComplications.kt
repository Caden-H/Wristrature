package io.github.cadenh.wristrature.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.wear.tiles.TileService
import io.github.cadenh.wristrature.R
import io.github.cadenh.wristrature.WristratureApp
import io.github.cadenh.wristrature.data.nowReading
import io.github.cadenh.wristrature.tile.BookTileService
import io.github.cadenh.wristrature.ui.MainActivity

private fun Context.openBook(bookId: String?): PendingIntent {
    val intent = Intent(this, MainActivity::class.java).apply {
        bookId?.let { putExtra(MainActivity.EXTRA_BOOK, it) }
    }
    return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

private fun Context.bookIcon() = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_complication_book)).build()

/**
 * Ranged complication: a ring that fills as the book is read, with the book icon and the percent. It is updated only
 * when the reader requests it (the update period is 0), so it costs nothing while the watch is idle.
 */
class ProgressRingComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.RANGED_VALUE) build(34, "34%", null) else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.RANGED_VALUE) return null
        val now = (application as WristratureApp).graph.library.nowReading()
        return build(now?.percent ?: 0, now?.percentText ?: "0%", now?.bookId)
    }

    private fun build(percent: Int, text: String, bookId: String?): ComplicationData =
        RangedValueComplicationData.Builder(percent.toFloat(), 0f, 100f, PlainComplicationText.Builder("Reading progress $text").build())
            .setText(PlainComplicationText.Builder(text).build())
            .setMonochromaticImage(bookIcon())
            .setTapAction(openBook(bookId))
            .build()
}

/** Short-text complication: the book icon and the percent. */
class ProgressTextComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build("34%", null) else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val now = (application as WristratureApp).graph.library.nowReading()
        return build(now?.percentText ?: "0%", now?.bookId)
    }

    private fun build(text: String, bookId: String?): ComplicationData =
        ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder("Reading progress $text").build(),
        )
            .setMonochromaticImage(bookIcon())
            .setTapAction(openBook(bookId))
            .build()
}

/** Requests an update of the tile and complications. Called when the reader closes, as the position changes then. */
object WatchSurfaces {
    fun refresh(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(BookTileService::class.java) }
        for (cls in listOf(ProgressRingComplicationService::class.java, ProgressTextComplicationService::class.java)) {
            runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, cls)).requestUpdateAll() }
        }
    }
}
