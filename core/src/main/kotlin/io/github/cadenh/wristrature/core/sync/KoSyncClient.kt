package io.github.cadenh.wristrature.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A client for the KOReader sync protocol (the stock server and compatible servers).
 *
 * The address is the one the user enters. It may carry a path (for example `https://host/kosync`). Calls are
 * `users/auth`, `users/create`, `syncs/progress` (PUT) and `syncs/progress/{document}` (GET).
 *
 * Credentials are never put in a URL and never logged.
 */
class KoSyncClient(
    baseUrl: String,
    private val auth: KoAuth,
    http: OkHttpClient = defaultHttp(),
    private val userAgent: String = "Wristrature (Wear OS)",
) {
    private val base: HttpUrl? = normalize(baseUrl)
    private val http = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Checks the login. [KoResult.Ok] means the server accepted it. */
    suspend fun authorize(): KoResult<Unit> = call(listOf("users", "auth"), "GET", null) { KoResult.Ok(Unit) }

    /** Creates an account on a server that allows it. The password is sent as its MD5, as KOReader does. */
    suspend fun register(): KoResult<Unit> {
        val body = buildJsonObject {
            put("username", auth.user)
            put("password", md5Hex(auth.password))
        }
        return call(listOf("users", "create"), "POST", body.toString(), registration = true) { KoResult.Ok(Unit) }
    }

    /** Reads the stored position of [document]. [KoResult.NotFound] if the server has none. */
    suspend fun getProgress(document: String): KoResult<KoProgress> =
        call(listOf("syncs", "progress", document), "GET", null) { text -> parseProgress(document, text) }

    /** Saves a position. */
    suspend fun putProgress(p: KoProgress): KoResult<Unit> {
        val body = buildJsonObject {
            put("document", p.document)
            put("progress", p.progress)
            put("percentage", p.percentage)
            put("device", p.device)
            put("device_id", p.deviceId)
        }
        return call(listOf("syncs", "progress"), "PUT", body.toString()) { KoResult.Ok(Unit) }
    }

    private suspend fun <T> call(
        segments: List<String>,
        method: String,
        json: String?,
        registration: Boolean = false,
        parse: (String) -> KoResult<T>,
    ): KoResult<T> = withContext(Dispatchers.IO) {
        val url = base?.newBuilder()?.apply { segments.forEach { addPathSegment(it) } }?.build()
            ?: return@withContext KoResult.NetworkError("The address is not valid.")
        val builder = Request.Builder().url(url)
            .header("Accept", ACCEPT)
            .header("User-Agent", userAgent)
        if (!registration) signIn(builder)
        if (json != null) builder.method(method, json.toRequestBody(JSON_TYPE)) else builder.method(method, null)
        try {
            http.newCall(builder.build()).execute().use { r ->
                val text = r.body.string()
                when (r.code) {
                    200, 201, 202 -> parse(text)
                    401 -> KoResult.Unauthorized
                    402 -> if (registration) KoResult.UserExists else KoResult.ServerError(402, text.take(200))
                    404 -> KoResult.NotFound
                    else -> KoResult.ServerError(r.code, text.take(200))
                }
            }
        } catch (e: IOException) {
            KoResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun signIn(b: Request.Builder) {
        when (auth) {
            is KoAuth.Stock -> b.header("x-auth-user", auth.user).header("x-auth-key", md5Hex(auth.password))
            is KoAuth.Basic -> b.header("Authorization", Credentials.basic(auth.user, auth.password, Charsets.UTF_8))
        }
    }

    companion object {
        private const val ACCEPT = "application/vnd.koreader.v1+json"
        private val JSON_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder().followRedirects(true).build()

        /** Returns null for an unusable address. The caller supplies the `https://` or `http://` scheme. */
        internal fun normalize(address: String): HttpUrl? {
            val t = address.trim().trimEnd('/')
            return (t + "/").toHttpUrlOrNull()
        }

        /**
         * Finds out which login style a server wants: tries the stock style first, then HTTP Basic.
         * Returns null if neither works. A wrong password and an unknown style look the same (both 401), so a null
         * result means "check the user name, the password and the address".
         */
        suspend fun detectLoginStyle(
            baseUrl: String,
            user: String,
            password: String,
            http: OkHttpClient = defaultHttp(),
        ): KoResult<LoginStyle> {
            val stock = KoSyncClient(baseUrl, KoAuth.Stock(user, password), http).authorize()
            when (stock) {
                is KoResult.Ok -> return KoResult.Ok(LoginStyle.STOCK)
                is KoResult.Unauthorized -> Unit
                is KoResult.NetworkError -> return stock
                else -> Unit
            }
            val basic = KoSyncClient(baseUrl, KoAuth.Basic(user, password), http).authorize()
            return when (basic) {
                is KoResult.Ok -> KoResult.Ok(LoginStyle.BASIC)
                is KoResult.NetworkError -> basic
                is KoResult.Unauthorized -> KoResult.Unauthorized
                else -> if (stock is KoResult.ServerError) stock else KoResult.Unauthorized
            }
        }

        fun authFor(style: LoginStyle, user: String, password: String): KoAuth = when (style) {
            LoginStyle.STOCK -> KoAuth.Stock(user, password)
            LoginStyle.BASIC -> KoAuth.Basic(user, password)
        }

        /**
         * Reads a position reply. A server with no position may answer 200 with `{}` or an empty body. Fields may be
         * numbers or strings, and `progress` is a page number for PDFs, so everything is read leniently.
         */
        internal fun parseProgress(requested: String, text: String): KoResult<KoProgress> {
            if (text.isBlank()) return KoResult.NotFound
            val obj = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return KoResult.ServerError(200, "The reply was not a JSON object.")
            val progress = obj.str("progress")
            val pct = obj["percentage"]?.let { (it as? JsonPrimitive)?.doubleOrNull }
            if (progress == null && pct == null) return KoResult.NotFound
            return KoResult.Ok(
                KoProgress(
                    document = obj.str("document") ?: requested,
                    progress = progress.orEmpty(),
                    percentage = pct ?: 0.0,
                    device = obj.str("device").orEmpty(),
                    deviceId = obj.str("device_id").orEmpty(),
                    timestamp = obj["timestamp"]?.let { (it as? JsonPrimitive)?.longOrNull },
                ),
            )
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
