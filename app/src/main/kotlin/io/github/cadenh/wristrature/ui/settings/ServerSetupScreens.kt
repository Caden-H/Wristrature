package io.github.cadenh.wristrature.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import io.github.cadenh.wristrature.core.opds.OpdsFeed
import io.github.cadenh.wristrature.core.opds.OpdsResult
import io.github.cadenh.wristrature.core.sync.KoAuth
import io.github.cadenh.wristrature.core.sync.KoResult
import io.github.cadenh.wristrature.core.sync.KoSyncClient
import io.github.cadenh.wristrature.core.sync.LoginStyle
import io.github.cadenh.wristrature.data.AppSettings
import io.github.cadenh.wristrature.data.DEFAULT_SYNC_URL
import io.github.cadenh.wristrature.data.Secrets
import io.github.cadenh.wristrature.ui.common.Field
import io.github.cadenh.wristrature.ui.common.ListRow
import io.github.cadenh.wristrature.ui.theme.AppIcons
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Adds `https://` when the user typed only a host. A typed `http://` stays: home servers often have no certificate. */
internal fun withScheme(address: String): String {
    val t = address.trim()
    return if (t.isEmpty() || t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
}

internal fun isPlainHttp(address: String) = address.trim().startsWith("http://", true)

private sealed interface Check {
    data object Idle : Check
    data object Busy : Check
    data class Ok(val title: String, val detail: String?) : Check
    data class Bad(val text: String) : Check
}

/** The result card: filled in the palette color with a check when it worked, in the error color when it did not. */
@Composable
private fun CheckCard(c: Check) {
    val s = MaterialTheme.colorScheme
    when (c) {
        is Check.Ok -> Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(s.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Check, null, Modifier.size(18.dp), tint = s.primary)
            Column(Modifier.padding(start = 10.dp)) {
                Text(c.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = s.onPrimaryContainer)
                c.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = s.onPrimaryContainer) }
            }
        }
        is Check.Bad -> Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(s.errorContainer)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Alert, null, Modifier.size(18.dp), tint = s.error)
            Text(
                c.text,
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = s.onErrorContainer,
            )
        }
        else -> Unit
    }
}

@Composable
private fun HttpWarning() {
    Text(
        "This address is not encrypted. Use it only on your own network.", Modifier.padding(horizontal = 12.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center,
    )
}

/** Where the books come from: any OPDS catalog (for example Calibre-Web-Automated at `https://host/opds`). */
@Composable
fun LibraryServerScreen(
    settings: AppSettings,
    secrets: Secrets,
    test: suspend (String, String, String) -> OpdsResult<OpdsFeed>,
    save: suspend (url: String, user: String, password: String) -> Unit,
    onDone: () -> Unit,
    setup: Boolean = false,
    onSkip: () -> Unit = {},
    onDraft: (url: String, user: String) -> Unit = { _, _ -> },
) {
    var url by remember { mutableStateOf(settings.servers.opdsUrl.ifBlank { settings.drafts.opdsUrl }) }
    var user by remember { mutableStateOf(settings.servers.opdsUser.ifBlank { settings.drafts.opdsUser }) }
    var password by remember { mutableStateOf(secrets.opdsPassword) }
    var check by remember { mutableStateOf<Check>(Check.Idle) }
    val scope = rememberCoroutineScope()
    val saved = check is Check.Ok
    SettingsPageFrame(
        "Library server",
        edgeButton = {
            EdgeButton(
                onClick = {
                    if (saved) { onDone(); return@EdgeButton }
                    val address = withScheme(url)
                    check = Check.Busy
                    scope.launch {
                        check = when (val r = test(address, user.trim(), password)) {
                            is OpdsResult.Ok -> {
                                save(address, user.trim(), password)
                                Check.Ok(
                                    "Connected",
                                    r.value.navigation.size.takeIf { it > 0 }?.let { "$it categories found" }
                                        ?: r.value.title.ifBlank { null },
                                )
                            }
                            is OpdsResult.Unauthorized -> Check.Bad("Wrong user or password")
                            is OpdsResult.NotFound -> Check.Bad("Nothing at this address")
                            is OpdsResult.NotACatalog -> Check.Bad("Not an OPDS catalog")
                            is OpdsResult.NetworkError -> Check.Bad("No connection")
                            is OpdsResult.ServerError -> Check.Bad("Server error ${r.code}")
                        }
                    }
                },
                enabled = url.isNotBlank() && check != Check.Busy,
            ) {
                Text(
                    if (saved) (if (setup) "Next" else "Done") else if (check == Check.Busy) "Testing…" else "Save",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    ) {
        item {
            Field(
                "OPDS address",
                url,
                {
                    url = it
                    check = Check.Idle
                    onDraft(it, user)
                },
                hint = "books.example/opds",
                keyboard = KeyboardType.Uri,
            )
        }
        if (isPlainHttp(url)) item { HttpWarning() }
        item { Field("User (optional)", user, { user = it; check = Check.Idle; onDraft(url, it) }) }
        item { Field("Password (optional)", password, { password = it; check = Check.Idle }, password = true) }
        item { CheckCard(check) }
        item {
            Text(
                "Tap a field to type or speak. Works with any OPDS catalog.", Modifier.padding(horizontal = 14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (settings.servers.opdsConfigured && !setup) item {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        save("", "", "")
                        url = ""
                        user = ""
                        password = ""
                        check = Check.Idle
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Remove server") },
            )
        }
        if (setup) item { FilledTonalButton(onClick = onSkip, modifier = Modifier.fillMaxWidth(), label = { Text("Skip") }) }
    }
}

/** Values typed on the sync pages, kept while moving between them. */
@Stable
class SyncDraft(settings: AppSettings, secrets: Secrets) {
    var custom by mutableStateOf(
        if (settings.servers.syncUrl.isNotBlank() && settings.servers.syncUser.isNotBlank()) settings.servers.syncUrl != DEFAULT_SYNC_URL
        else settings.drafts.syncCustom,
    )
    var url by mutableStateOf(
        if (settings.servers.syncUrl.isNotBlank() && settings.servers.syncUrl != DEFAULT_SYNC_URL) settings.servers.syncUrl
        else settings.drafts.syncUrl,
    )
    var user by mutableStateOf(settings.servers.syncUser.ifBlank { settings.drafts.syncUser })
    var password by mutableStateOf(secrets.syncPassword)
    var byName by mutableStateOf(settings.servers.matchByFileName)
    val address: String get() = if (custom) withScheme(url) else DEFAULT_SYNC_URL
}

/** Step one of the reading sync: the official KOReader server, or any other KOReader-compatible one. */
@Composable
fun SyncServerScreen(draft: SyncDraft, settings: AppSettings, onAdvanced: () -> Unit, onNext: () -> Unit) {
    SettingsPageFrame(
        "Reading sync server",
        edgeButton = {
            EdgeButton(onClick = onNext, enabled = !draft.custom || draft.url.isNotBlank()) {
                Text("Next", style = MaterialTheme.typography.labelMedium)
            }
        },
    ) {
        item {
            ListRow(
                "KOReader official", { draft.custom = false }, secondary = "sync.koreader.rocks · default", selected = !draft.custom,
                trailing = { RadioDot(!draft.custom) },
            )
        }
        item { ListRow("Custom server", { draft.custom = true }, selected = draft.custom, trailing = { RadioDot(draft.custom) }) }
        if (draft.custom) {
            item { Field("Address", draft.url, { draft.url = it }, hint = "kosync.example.com", keyboard = KeyboardType.Uri) }
            if (isPlainHttp(draft.url)) item { HttpWarning() }
        }
        linkRow("Advanced", "Login: automatic · Match: ${if (draft.byName) "file name" else "file"}", onAdvanced)
        item {
            Text(
                "Separate from the library server. Works with any KOReader-compatible server.", Modifier.padding(horizontal = 14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RadioDot(on: Boolean) {
    val s = MaterialTheme.colorScheme
    Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) s.primary else Color.Transparent).padding(if (on) 6.dp else 0.dp)) {
        if (on) Box(Modifier.fillMaxSize().clip(CircleShape).background(s.onPrimary))
        else Box(Modifier.fillMaxSize().clip(CircleShape).background(s.surfaceContainerHigh))
    }
}

/** Step two: the user name and password, with "Create account" for servers that allow it. */
@Composable
fun SyncSignInScreen(
    draft: SyncDraft,
    http: () -> OkHttpClient,
    save: suspend (url: String, user: String, password: String, style: LoginStyle?, byName: Boolean) -> Unit,
    onDone: () -> Unit,
    setup: Boolean = false,
) {
    var check by remember { mutableStateOf<Check>(Check.Idle) }
    var canCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val signedIn = check is Check.Ok
    SettingsPageFrame(
        "Sign in",
        edgeButton = {
            EdgeButton(
                onClick = {
                    if (signedIn) { onDone(); return@EdgeButton }
                    check = Check.Busy
                    scope.launch {
                        check = when (val r = KoSyncClient.detectLoginStyle(draft.address, draft.user.trim(), draft.password, http())) {
                            is KoResult.Ok -> {
                                save(draft.address, draft.user.trim(), draft.password, r.value, draft.byName)
                                Check.Ok("Signed in", "as ${draft.user.trim()}")
                            }
                            is KoResult.Unauthorized -> { canCreate = true; Check.Bad("Wrong user or password") }
                            is KoResult.NetworkError -> Check.Bad("No connection")
                            else -> Check.Bad("The server did not accept this")
                        }
                    }
                },
                enabled = draft.user.isNotBlank() && check != Check.Busy,
            ) {
                Text(
                    if (signedIn) (if (setup) "Done" else "Done") else if (check == Check.Busy) "Testing…" else "Sign in",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    ) {
        item { Field("User", draft.user, { draft.user = it; check = Check.Idle; canCreate = false }) }
        item { Field("Password", draft.password, { draft.password = it; check = Check.Idle; canCreate = false }, password = true) }
        item { CheckCard(check) }
        item {
            FilledTonalButton(
                onClick = {
                    check = Check.Busy
                    scope.launch {
                        check = when (KoSyncClient(draft.address, KoAuth.Stock(draft.user.trim(), draft.password), http()).register()) {
                            is KoResult.Ok -> {
                                save(draft.address, draft.user.trim(), draft.password, LoginStyle.STOCK, draft.byName)
                                canCreate = false
                                Check.Ok("Account created", "as ${draft.user.trim()}")
                            }
                            is KoResult.UserExists -> Check.Bad("This user name is taken")
                            is KoResult.NetworkError -> Check.Bad("No connection")
                            else -> Check.Bad("This server does not allow new accounts")
                        }
                    }
                },
                enabled = draft.user.isNotBlank() && draft.password.isNotBlank() && check != Check.Busy,
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(AppIcons.Plus, null, Modifier.size(18.dp)) },
                label = { Text("Create account") },
            )
        }
        item {
            Text(
                if (draft.custom) "No account yet? Some servers let you create one." else
                    "No account? Create a free one on the official server.",
                Modifier.padding(horizontal = 14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Matching by file name, for servers configured that way. The login style is detected when signing in. */
@Composable
fun SyncAdvancedScreen(draft: SyncDraft, settings: AppSettings) = SettingsPageFrame("Advanced") {
    item {
        SwitchButton(
            checked = draft.byName, onCheckedChange = { draft.byName = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Match by file name", fontWeight = FontWeight.SemiBold) },
            secondaryLabel = { Text("Off: match by file contents") },
        )
    }
    item {
        Text(
            "The login type is detected when you sign in. It tries the standard KOReader login first, then HTTP Basic." +
                (settings.servers.loginStyle?.let { " Using: ${if (it == LoginStyle.STOCK) "standard" else "Basic"}." } ?: ""),
            Modifier.padding(horizontal = 14.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
