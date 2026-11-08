package io.github.cadenh.wristrature.core.sync

/** One reading position as the KOReader sync protocol stores it. */
data class KoProgress(
    /** The document ID (partial MD5 of the file, or MD5 of the file name). */
    val document: String,
    /** For EPUB a crengine XPointer such as `/body/DocFragment[12]/body/div/p[3]/text().18`. */
    val progress: String,
    /** 0.0 to 1.0. */
    val percentage: Double,
    val device: String,
    val deviceId: String,
    /** Seconds since the epoch, set by the server on newer servers. Null if the server sends none. */
    val timestamp: Long? = null,
)

/** How the client signs in. Servers differ. */
sealed interface KoAuth {
    val user: String
    val password: String

    /** The stock KOReader server: headers `x-auth-user` and `x-auth-key` (the MD5 of the password). */
    data class Stock(override val user: String, override val password: String) : KoAuth

    /** HTTP Basic login. Used by some servers, for example Calibre-Web-Automated's own sync. */
    data class Basic(override val user: String, override val password: String) : KoAuth
}

@kotlinx.serialization.Serializable
enum class LoginStyle { STOCK, BASIC }

/** The result of a sync call. Nothing here throws. */
sealed interface KoResult<out T> {
    data class Ok<T>(val value: T) : KoResult<T>

    /** The server does not know this user or the password is wrong (HTTP 401). */
    data object Unauthorized : KoResult<Nothing>

    /** The server has no position for this document yet. */
    data object NotFound : KoResult<Nothing>

    /** Registration only: the user name is taken (HTTP 402). */
    data object UserExists : KoResult<Nothing>

    /** The server answered with a status we do not handle. */
    data class ServerError(val code: Int, val body: String) : KoResult<Nothing>

    /** No answer: offline, timeout, bad address, TLS problem. Retry later. */
    data class NetworkError(val message: String) : KoResult<Nothing>
}
