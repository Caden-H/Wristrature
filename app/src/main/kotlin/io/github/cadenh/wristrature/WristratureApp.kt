package io.github.cadenh.wristrature

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.data.CatalogRepository
import io.github.cadenh.wristrature.data.LibraryRepository
import io.github.cadenh.wristrature.data.SecretStorage
import io.github.cadenh.wristrature.data.SecretStore
import io.github.cadenh.wristrature.data.SettingsStore
import io.github.cadenh.wristrature.data.SyncRepository
import io.github.cadenh.wristrature.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import java.io.File

/** The app's singletons. A DI framework is not used: the object graph is small and DI would add start-up cost. */
class AppGraph(
    val context: Context,
    val settings: SettingsStore = SettingsStore(context),
    val secrets: SecretStorage = SecretStore(context),
    val db: AppDatabase = AppDatabase.create(context),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Shared HTTP client, created lazily because initialising TLS is unnecessary for the tile. */
    val http: OkHttpClient by lazy { OkHttpClient() }

    /** Folder scanned for EPUB files copied with `adb push`. App-owned storage, so no permission is required. */
    val importFolder: File by lazy { (context.getExternalFilesDir("import") ?: File(context.filesDir, "import")).also { it.mkdirs() } }

    val library = LibraryRepository(File(context.filesDir, "library"), db.library())

    val catalog = CatalogRepository(settings, secrets, library, File(context.cacheDir, "downloads")) { http }

    val sync = SyncRepository(settings, secrets, library, { http })

    /** Current settings for the UI. Holds the defaults until the first read completes. */
    val settingsNow: StateFlow<AppSettings> by lazy {
        settings.flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    }
}

class WristratureApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // The background sync job follows the setting. WorkManager keeps a single job per unique name, so applying it on
        // every start is cheap. Deferred to avoid competing with the first frame; failures are ignored.
        MainScope().launch(Dispatchers.Default) {
            delay(5_000)
            // Refresh the tile and complications after an app update (otherwise they keep the old rendering) and
            // whenever the palette changes, so they match the app colors.
            val prefs = getSharedPreferences("meta", MODE_PRIVATE)
            if (prefs.getInt("surfaces", 0) != BuildConfig.VERSION_CODE) {
                runCatching { io.github.cadenh.wristrature.complication.WatchSurfaces.refresh(this@WristratureApp) }
                prefs.edit { putInt("surfaces", BuildConfig.VERSION_CODE) }
            }
            launch {
                runCatching {
                    graph.settings.flow.map { it.palette }.distinctUntilChanged().drop(1).collect {
                        io.github.cadenh.wristrature.complication.WatchSurfaces.refresh(this@WristratureApp)
                    }
                }
            }
            runCatching {
                graph.settings.flow.map { it.sync.backgroundHours }.distinctUntilChanged().collect { hours ->
                    runCatching { io.github.cadenh.wristrature.work.SyncWork.applyBackground(this@WristratureApp, hours) }
                }
            }
        }
    }
}
