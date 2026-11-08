package io.github.cadenh.wristrature.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import io.github.cadenh.wristrature.WristratureApp
import io.github.cadenh.wristrature.data.NowReading
import io.github.cadenh.wristrature.data.nowReading
import io.github.cadenh.wristrature.reader.ReaderInfo
import io.github.cadenh.wristrature.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Tile showing the most recently read book with its percent, chapter, time left and a button that resumes at the saved
 * position. It does not poll: it is built when the watch shows it and refreshed when the reader closes.
 */
class BookTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
        val graph = (application as WristratureApp).graph
        val now = graph.library.nowReading()
        val palette = graph.settings.get().palette
        val layout = materialScope(
            this@BookTileService, requestParams.deviceConfiguration,
            allowDynamicTheme = false,
            defaultColorScheme = tileScheme(this@BookTileService, palette),
        ) {
            primaryLayout(
                titleSlot = { text((now?.title ?: "Wristrature").layoutString, typography = Typography.TITLE_SMALL, maxLines = 1) },
                mainSlot = {
                    if (now == null) text("No book yet".layoutString)
                    else {
                        val info = LayoutElementBuilders.Column.Builder()
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                            .addContent(
                                text(
                                    now.percentText.layoutString,
                                    typography = Typography.NUMERAL_SMALL,
                                    color = colorScheme.primary,
                                    alignment = LayoutElementBuilders.TEXT_ALIGN_START,
                                ),
                            )
                            .addContent(
                                text(
                                    (now.chapter ?: "Chapter").layoutString,
                                    typography = Typography.LABEL_MEDIUM,
                                    maxLines = 1,
                                    alignment = LayoutElementBuilders.TEXT_ALIGN_START,
                                ),
                            )
                            .addContent(
                                text(
                                    "${ReaderInfo.duration(now.minutesLeft)} left".layoutString,
                                    typography = Typography.BODY_SMALL,
                                    color = colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    alignment = LayoutElementBuilders.TEXT_ALIGN_START,
                                ),
                            )
                            .build()
                        if (now.cover == null) info
                        else LayoutElementBuilders.Row.Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(
                                LayoutElementBuilders.Image.Builder()
                                    .setResourceId(COVER_ID)
                                    .setWidth(dp(58f)).setHeight(dp(87f))
                                    .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_CROP)
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setBackground(
                                                ModifiersBuilders.Background.Builder()
                                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(10f)).build())
                                                    .build(),
                                            )
                                            .setPadding(ModifiersBuilders.Padding.Builder().setEnd(dp(12f)).build())
                                            .build(),
                                    )
                                    .build(),
                            )
                            .addContent(info)
                            .build()
                    }
                },
                bottomSlot = {
                    textEdgeButton(onClick = open(now?.bookId)) { text((if (now == null) "Open" else "Continue").layoutString) }
                },
            )
        }
        TileBuilders.Tile.Builder()
            .setResourcesVersion(versionOf(now))
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future {
            val now = (application as WristratureApp).graph.library.nowReading()
            val b = ResourceBuilders.Resources.Builder().setVersion(versionOf(now))
            val cover = now?.cover
            if (cover != null && cover.exists()) {
                val bytes = cover.readBytes()
                b.addIdToImageMapping(
                    COVER_ID,
                    ResourceBuilders.ImageResource.Builder()
                        .setInlineResource(
                            ResourceBuilders.InlineImageResource.Builder().setData(bytes).setWidthPx(174).setHeightPx(261)
                                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED).build(),
                        )
                        .build(),
                )
            }
            b.build()
        }

    private fun open(bookId: String?): ModifiersBuilders.Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(MainActivity::class.java.name)
        if (bookId != null) {
            activity.addKeyToExtraMapping(MainActivity.EXTRA_BOOK, ActionBuilders.AndroidStringExtra.Builder().setValue(bookId).build())
        }
        return ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
            .build()
    }

    private companion object {
        /** The system caches resources by version, so a different book needs a different version to fetch its cover. */
        fun versionOf(now: NowReading?) = now?.bookId ?: "none"

        const val COVER_ID = "cover"
    }
}
