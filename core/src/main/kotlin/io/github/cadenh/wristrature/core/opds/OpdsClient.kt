package io.github.cadenh.wristrature.core.opds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Optional HTTP Basic login for the catalog. */
data class OpdsLogin(val user: String, val password: String)

/**
 * A client for an OPDS 1.x catalog. The address is whatever the user enters (for example `https://books.example/opds`).
 * The login is sent as an Authorization header, never inside a URL, and only to the catalog's own host.
 */
class OpdsClient(
    private val rootUrl: String,
    private val login: OpdsLogin? = null,
    http: OkHttpClient = OkHttpClient(),
    private val userAgent: String = "Wristrature (Wear OS)",
) {
    private val http = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val host = rootUrl.toHttpUrlOrNull()?.host

    suspend fun root(): OpdsResult<OpdsFeed> = feed(rootUrl)

    suspend fun feed(url: String): OpdsResult<OpdsFeed> = fetch(url) { bytes, finalUrl ->
        try {
            OpdsResult.Ok(OpdsParser.parseFeed(bytes, finalUrl))
        } catch (e: Exception) {
            OpdsResult.NotACatalog("The server did not return an OPDS catalog (Atom).")
        }
    }

    /** Runs a search against the catalog described by [from] (normally the root feed). */
    suspend fun search(from: OpdsFeed, query: String): OpdsResult<OpdsFeed> {
        val link = from.searchLink ?: return OpdsResult.NotACatalog("This catalog does not offer search.")
        val template = if (from.searchIsDescription) {
            val description = fetch(link) { bytes, finalUrl ->
                // A reply that is not an OpenSearch description is a catalog problem, never a crash.
                try {
                    OpdsResult.Ok(OpdsParser.parseSearchDescription(bytes, finalUrl))
                } catch (e: Exception) {
                    OpdsResult.NotACatalog("This catalog does not offer search.")
                }
            }
            when (val d = description) {
                is OpdsResult.Ok -> d.value ?: return OpdsResult.NotACatalog("This catalog does not offer search.")
                is OpdsResult.Unauthorized -> return OpdsResult.Unauthorized
                is OpdsResult.NotFound -> return OpdsResult.NotFound
                is OpdsResult.NotACatalog -> return d
                is OpdsResult.ServerError -> return d
                is OpdsResult.NetworkError -> return d
            }
        } else link
        return feed(OpdsParser.fillSearch(template, query))
    }

    /** Fetches an image (cover or thumbnail). */
    suspend fun bytes(url: String, maxBytes: Int = 4 * 1024 * 1024): OpdsResult<ByteArray> =
        fetch(url, maxBytes) { bytes, _ -> OpdsResult.Ok(bytes) }

    /**
     * Downloads a book to [target]. The file is written to a temporary name and renamed when complete. [progress]
     * gets (bytes so far, total or -1). The bytes are stored exactly as sent, because the sync ID depends on them.
     */
    suspend fun download(
        url: String,
        target: File,
        maxBytes: Long = 200L * 1024 * 1024,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): OpdsResult<File> = withContext(Dispatchers.IO) {
        val request = request(url) ?: return@withContext OpdsResult.NetworkError("The address is not valid.")
        val tmp = File(target.path + ".part")
        try {
            http.newCall(request).execute().use { r ->
                when {
                    r.code == 401 -> return@withContext OpdsResult.Unauthorized
                    r.code == 404 -> return@withContext OpdsResult.NotFound
                    !r.isSuccessful -> return@withContext OpdsResult.ServerError(r.code, "")
                }
                val total = r.body.contentLength()
                if (total > maxBytes) return@withContext OpdsResult.ServerError(413, "The file is too large.")
                target.parentFile?.mkdirs()
                var done = 0L
                r.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            // A cancelled download stops at the next chunk and its partial file is removed below.
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > maxBytes) {
                                tmp.delete()
                                return@withContext OpdsResult.ServerError(413, "The file is too large.")
                            }
                            out.write(buf, 0, n)
                            progress(done, total)
                        }
                    }
                }
                if (total >= 0 && done != total) {
                    tmp.delete()
                    return@withContext OpdsResult.NetworkError("The download stopped early.")
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                OpdsResult.Ok(target)
            }
        } catch (e: IOException) {
            tmp.delete()
            OpdsResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            // Nothing is left behind after an error or a cancel. After a good download the file was already renamed.
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun request(url: String): Request? {
        val u = url.toHttpUrlOrNull() ?: return null
        val b = Request.Builder().url(u).header("User-Agent", userAgent)
        if (login != null && u.host == host) b.header("Authorization", Credentials.basic(login.user, login.password, Charsets.UTF_8))
        return b.build()
    }

    private suspend fun <T> fetch(
        url: String,
        maxBytes: Int = 8 * 1024 * 1024,
        parse: (ByteArray, String) -> OpdsResult<T>,
    ): OpdsResult<T> = withContext(Dispatchers.IO) {
        val request = request(url) ?: return@withContext OpdsResult.NetworkError("The address is not valid.")
        try {
            http.newCall(request).execute().use { r ->
                when {
                    r.code == 401 -> return@withContext OpdsResult.Unauthorized
                    r.code == 404 -> return@withContext OpdsResult.NotFound
                    !r.isSuccessful -> return@withContext OpdsResult.ServerError(r.code, "")
                }
                val len = r.body.contentLength()
                if (len > maxBytes) return@withContext OpdsResult.ServerError(413, "The reply is too large.")
                val bytes = r.body.source().use { s ->
                    s.request(maxBytes + 1L)
                    if (s.buffer.size > maxBytes) return@withContext OpdsResult.ServerError(413, "The reply is too large.")
                    s.readByteArray()
                }
                parse(bytes, r.request.url.toString())
            }
        } catch (e: IOException) {
            OpdsResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /**
         * True when the catalog's copy of a book is not the one stored: its update time or its size differs. A book edited
         * on the server (for example a new cover) is a different file with a different sync ID, so it must be downloaded
         * again. Nothing stored (a book from before this was recorded) means no change is known.
         */
        fun changed(storedUpdated: String?, storedLength: Long?, book: OpdsBook, file: OpdsAcquisition?): Boolean {
            if (storedUpdated == null && storedLength == null) return false
            val newer = storedUpdated != null && book.updated.isNotEmpty() && book.updated != storedUpdated
            val longer = storedLength != null && file?.length != null && file.length != storedLength
            return newer || longer
        }

        /** Picks the file to download: the first format in [preferred] (mime types, best first) that the book offers. */
        fun pick(book: OpdsBook, preferred: List<String> = listOf("application/epub+zip")): OpdsAcquisition? {
            for (mime in preferred) book.acquisitions.firstOrNull { it.mimeType.equals(mime, ignoreCase = true) }?.let { return it }
            return null
        }
    }
}
