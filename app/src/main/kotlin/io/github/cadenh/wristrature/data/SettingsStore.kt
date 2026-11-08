package io.github.cadenh.wristrature.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wristrature_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Stores all settings as one JSON value in DataStore. Missing fields take defaults, so older files stay readable. */
class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val flow: Flow<AppSettings> = store.data.map { decode(it[key]) }

    suspend fun get(): AppSettings = flow.first()

    /** Reads the settings, applies [change] and writes them back. The device ID is generated once and then kept. */
    suspend fun update(change: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = change(decode(prefs[key]))
            prefs[key] = json.encodeToString(AppSettings.serializer(), next)
        }
    }

    private fun decode(text: String?): AppSettings {
        val s = text?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
        return if (s.deviceId.isEmpty()) s.copy(deviceId = UUID.randomUUID().toString()) else s
    }
}
