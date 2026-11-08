package io.github.cadenh.wristrature.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.cadenh.wristrature.AppGraph
import io.github.cadenh.wristrature.BuildConfig
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.data.Secrets
import io.github.cadenh.wristrature.ui.SettingsPage
import kotlinx.coroutines.launch

/** Holds the values typed on the sync pages while moving between them. */
class SyncDraftHolder {
    var draft by mutableStateOf<SyncDraft?>(null)
}

/**
 * Every settings page, wired to the app. The pages are pure functions of the settings; this is the only place that knows
 * where "Save" goes. [setup] is true during the first-run steps, which go on to the next step instead of back.
 */
@Composable
fun SettingsHost(
    page: SettingsPage,
    setup: Boolean,
    graph: AppGraph,
    settings: AppSettings,
    secrets: Secrets,
    holder: SyncDraftHolder,
    open: (SettingsPage, Boolean) -> Unit,
    pop: () -> Unit,
    finishSetup: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val change: Change = { f -> scope.launch { graph.settings.update(f) } }
    val go: (SettingsPage) -> Unit = { open(it, false) }
    when (page) {
        SettingsPage.ROOT -> SettingsRoot(settings, go)
        SettingsPage.LAYOUT -> LayoutSettings(settings, change, go)
        SettingsPage.TEXT -> TextSettings(settings, change)
        SettingsPage.COLORS -> ColorSettings(settings, change, go)
        SettingsPage.PALETTE -> PaletteSettings(settings, change)
        SettingsPage.DAY_NIGHT -> DayNightSettings(settings, change, go)
        SettingsPage.DAY_COLOR -> DayNightColorPicker(
            "Day color",
            settings.dayNight.day,
            "Pick the same four for Night",
        ) { v -> change { it.copy(dayNight = it.dayNight.copy(day = v)) } }
        SettingsPage.NIGHT_COLOR -> DayNightColorPicker(
            "Night color",
            settings.dayNight.night,
            "Pick the same four for Day",
        ) { v -> change { it.copy(dayNight = it.dayNight.copy(night = v)) } }
        SettingsPage.NIGHT_HOURS -> NightHoursSettings(settings, change)
        SettingsPage.INFO -> InfoSettings(settings, go)
        SettingsPage.INFO_TOP -> InfoPicker("Top line shows", settings.reader.topInfo) { v -> change.reader { it.copy(topInfo = v) } }
        SettingsPage.INFO_BOTTOM -> InfoPicker(
            "Bottom line shows",
            settings.reader.bottomInfo,
        ) { v -> change.reader { it.copy(bottomInfo = v) } }
        SettingsPage.PROGRESS -> ProgressSettings(settings, change)
        SettingsPage.CONTROLS -> ControlSettings(settings, change, go)
        SettingsPage.DOUBLE_TAP -> DoubleTapSettings(settings, change)
        SettingsPage.AUTO -> AutoScrollSettingsPage(settings, change)
        SettingsPage.SPEED -> SpeedSettingsPage(settings, change)
        SettingsPage.SYNC_BEHAVIOR -> SyncBehaviorSettings(settings, change)
        SettingsPage.LIBRARY_SERVER -> LibraryServerScreen(
            settings, secrets,
            test = { url, user, pass -> graph.catalog.test(url, user, pass) },
            save = { url, user, pass ->
                graph.secrets.save(secrets.copy(opdsPassword = pass))
                graph.settings.update { it.copy(servers = it.servers.copy(opdsUrl = url, opdsUser = user)) }
            },
            onDone = { if (setup) open(SettingsPage.SYNC_SERVER, true) else pop() },
            setup = setup,
            onSkip = finishSetup,
            onDraft = { url, user -> change { it.copy(drafts = it.drafts.copy(opdsUrl = url, opdsUser = user)) } },
        )
        SettingsPage.SYNC_SERVER -> {
            val d = holder.draft ?: SyncDraft(settings, secrets).also { holder.draft = it }
            RememberDraft(d, change)
            SyncServerScreen(
                d,
                settings,
                onAdvanced = { go(SettingsPage.SYNC_ADVANCED) },
                onNext = { open(SettingsPage.SYNC_SIGN_IN, setup) },
            )
        }
        SettingsPage.SYNC_SIGN_IN -> {
            val d = holder.draft ?: SyncDraft(settings, secrets).also { holder.draft = it }
            RememberDraft(d, change)
            SyncSignInScreen(
                d, { graph.http },
                save = { url, user, pass, style, byName ->
                    graph.secrets.save(secrets.copy(syncPassword = pass))
                    graph.settings.update {
                        it.copy(
                            servers = it.servers.copy(
                                syncUrl = url,
                                syncUser = user,
                                loginStyle = style,
                                matchByFileName = byName,
                            ),
                        )
                    }
                },
                onDone = { holder.draft = null; if (setup) finishSetup() else pop() },
                setup = setup,
            )
        }
        SettingsPage.SYNC_ADVANCED -> {
            val d = holder.draft ?: SyncDraft(settings, secrets).also { holder.draft = it }
            SyncAdvancedScreen(d, settings)
        }
        SettingsPage.STORAGE -> {
            val booksFlow = remember { graph.library.all() }
            val books by booksFlow.collectAsState(initial = emptyList())
            var version by remember { mutableStateOf(0) }
            val items by produceState(emptyList<StorageItem>(), books, version) {
                value = books.filter { it.book.onWatch }.map {
                    StorageItem(it.book.id, it.book.title, graph.library.sizeOf(it.book.id), it.book.finished)
                }
            }
            StorageScreen(
                items,
                graph.importFolder.absolutePath
                    .substringAfter("/Android/data/", graph.importFolder.absolutePath)
                    .let { "…/Android/data/$it" },
                onRemove = { graph.library.removeFromWatch(it); version++ },
                onImport = { graph.library.importPending(graph.importFolder) },
            )
        }
        SettingsPage.ABOUT -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            AboutScreen(
                BuildConfig.VERSION_NAME,
                io.github.cadenh.wristrature.CrashLog.lastCrash(context),
                "Android/data/${context.packageName}/files/crash.log",
            )
        }
    }
}

/** Writes the typed values to the saved drafts, so they survive leaving the screen and returning later. */
@Composable
private fun RememberDraft(d: SyncDraft, change: Change) {
    androidx.compose.runtime.LaunchedEffect(d) {
        androidx.compose.runtime.snapshotFlow { Triple(d.custom, d.url, d.user) }.collect { (custom, url, user) ->
            change { it.copy(drafts = it.drafts.copy(syncCustom = custom, syncUrl = url, syncUser = user)) }
        }
    }
}
