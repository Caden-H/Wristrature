package io.github.cadenh.wristrature.core.opds

/** A link to another feed: a category such as "Authors" or "Recently added". */
data class OpdsNav(
    val title: String,
    val summary: String,
    val href: String,
    /** How many items it holds, if the server says. */
    val count: Int? = null,
    val rel: String = "",
)

data class OpdsAcquisition(
    val href: String,
    val mimeType: String,
    val length: Long? = null,
    val rel: String = "",
)

/** A book as the catalog lists it. */
data class OpdsBook(
    val id: String,
    val title: String,
    val authors: List<String>,
    val summary: String,
    val language: String,
    val published: String,
    val updated: String,
    val categories: List<String>,
    val coverUrl: String?,
    val thumbnailUrl: String?,
    val acquisitions: List<OpdsAcquisition>,
)

sealed interface OpdsEntry {
    data class Nav(val nav: OpdsNav) : OpdsEntry
    data class Book(val book: OpdsBook) : OpdsEntry
}

data class OpdsFeed(
    val title: String,
    val url: String,
    val entries: List<OpdsEntry>,
    /** The next page, as an absolute URL, or null on the last page. */
    val next: String?,
    /** A search description or template found in the feed, as an absolute URL. */
    val searchLink: String?,
    val searchIsDescription: Boolean,
    val totalResults: Int?,
) {
    val books: List<OpdsBook> get() = entries.filterIsInstance<OpdsEntry.Book>().map { it.book }
    val navigation: List<OpdsNav> get() = entries.filterIsInstance<OpdsEntry.Nav>().map { it.nav }
}

/** The result of an OPDS call. Nothing here throws. */
sealed interface OpdsResult<out T> {
    data class Ok<T>(val value: T) : OpdsResult<T>
    data object Unauthorized : OpdsResult<Nothing>
    data object NotFound : OpdsResult<Nothing>

    /** The server answers, but not with an OPDS 1.x (Atom) catalog. */
    data class NotACatalog(val message: String) : OpdsResult<Nothing>
    data class ServerError(val code: Int, val body: String) : OpdsResult<Nothing>
    data class NetworkError(val message: String) : OpdsResult<Nothing>
}
