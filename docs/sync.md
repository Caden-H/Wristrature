# Sync and library servers

Wristrature talks to two independent servers.

* A **library server** speaks OPDS 1.x (Calibre-Web-Automated, Calibre, Kavita and others) and serves the catalog and the
  files. It uses an optional HTTP Basic login.
* A **sync server** speaks the KOReader sync protocol and stores the reading position. It defaults to the official server,
  `https://sync.koreader.rocks`, and can be any compatible server.

Credentials are stored in separate fields and sent in headers. The app does not put them in `user:password@` URLs.

## KOReader sync protocol

| Call | Request | Success |
| --- | --- | --- |
| Register | `POST /users/create` `{username, password}` | 201 |
| Check login | `GET /users/auth` | 200 |
| Push position | `PUT /syncs/progress` | 200 |
| Pull position | `GET /syncs/progress/{document}` | 200 |

Headers: `Accept: application/vnd.koreader.v1+json`, `x-auth-user`, and `x-auth-key` (the MD5 of the password). Some
servers use HTTP Basic instead; the login screen tries the standard style first and then Basic, and remembers which worked.

The push body is `{document, progress, percentage, device, device_id}`. A pull returns the same fields plus a `timestamp`.
`progress` is a crengine XPointer such as `/body/DocFragment[12]/body/div/p[3]/text().18`; `percentage` is 0 to 1. A server
with no position may answer `200 {}`.

### Document ID

By default the document ID is KOReader's partial MD5: an MD5 over up to twelve 1 KiB samples of the file taken at offsets
`1024 << (2 * i)` for `i = -1 … 10` (the first is offset 0, because the shift count wraps in the original Lua). Matching by
file name is available as an option. Because the ID comes from the bytes, every device must have the identical file. If
a book is edited on the library server (new cover, new metadata), it becomes a different file; the app notices the changed
size or update time in the catalog entry and offers to download it again.

## Position mapping

The app stores positions as `(chapter, block, character offset)`. Mapping to and from XPointers uses the element paths and
text-node tables written at import, so both directions are table lookups.

* **Push**: find the block and text node of the first visible character and build
  `/body/DocFragment[spineIndex + 1]/body/<path>/text().<offset>`. Send the percentage as well. Nothing ahead of what has
  been read is sent.
* **Pull**: try each step in order and stop at the first that works.
  1. the exact character (resolve the path, pick the text node, apply the offset);
  2. the start of the element, when the offset does not resolve;
  3. the N-th paragraph, when the path does not resolve but contains a `p[N]` step (what CrossPoint writes);
  4. the percentage inside the chapter named by `DocFragment[N]`;
  5. the percentage of the whole book;
  6. the start of the chapter, only when the position itself is a chapter start.

Paths are indexed as in crengine, which parses the XHTML as an XML tree, so the importer parses XML too and carries a table
of HTML entities. Documents that are not well-formed fall back to the lower steps.

## When the place is read and sent

**Pulling.** When a book opens it is shown at once from the saved place; the server is asked in the background (skipped when
the last check was recent, 2 minutes by default). "Sync now" in the reader menu asks again.

A place from another device counts as news when:

* it is not from this watch (`device_id` differs) and its progress string differs from the local one, and
* it is further ahead (more than 0.1 %), **or** the server stamped it after the last time the place changed here.

The second rule exists because devices measure percent differently. An e-ink reader counts pages and the watch counts
characters, so the same spot can have a smaller percent there. A place found only by timestamp is offered, never jumped to
automatically, because it may be behind. The setting "If another device is ahead" chooses between asking and jumping; an
automatic jump only happens before the reader has scrolled, and never backwards.

**Pushing.** The stored place is sent:

* every few screens of reading (5 screens and 60 seconds apart at most);
* 30 seconds after the reader stops moving;
* when the watch dims or the app leaves the foreground;
* from a queued job that waits for a network, after a failed send.

A place the server already has is never sent again, so an old place cannot overwrite a newer one written by another
device in the meantime. After a failed send, automatic retries pause for two minutes.

Holding "Sync now" opens a screen that shows the server's place (percent, device, age) next to the watch's, with buttons to
take the server's place or send the watch's.

## Known limits

* Offsets can be a few characters off where the XHTML source counts whitespace differently. That lands on the same line or
  the next.
* KOReader's percentage depends on its own layout and only roughly matches the watch's. It is used as a fallback.
* Readers that write coarse positions (CrossPoint, CrossInk) usually land a page or a chapter apart.
* Some servers keep only the furthest position (BookBridge does), so scrolling back may not lower the stored place.
* Calibre-Web-Automated's own sync endpoint uses HTTP Basic and, from v4.0.7, stores progress per book instead of per file.

## OPDS

The catalog screens are built from whatever navigation feed the server returns, so they work with any OPDS 1.x server
(Calibre-Web-Automated lists Recently added, Authors, Series, Categories, Shelves and more). Feeds are paged through `next`
links, lists use thumbnails, and search follows the OpenSearch description or a direct template link. EPUB is preferred over
KEPUB. Downloaded bytes are stored exactly as sent. EPUB files are treated as untrusted: zip paths, expanded size, image size
and XML are all bounded.
