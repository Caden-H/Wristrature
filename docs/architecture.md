# Architecture

Wristrature is two Gradle modules.

| Module | Contents |
| --- | --- |
| `core` | Kotlin/JVM library with no Android UI: EPUB import, the block model, reading positions and the KOReader position mapping, the KOReader sync client, the OPDS client, speed-reading pacing. Everything here runs in plain JUnit. |
| `app` | The Wear OS app: reader engine, screens, storage, tile and complications, background work. |

The app targets Wear OS 6 and up (API 36 and 37) and is built on Wear Compose Material 3, Navigation 3, Room, DataStore,
WorkManager and Protolayout tiles.

## From EPUB to the screen

```
EPUB file ──import──▶ chapter files + book index ──▶ blocks ──layout──▶ rows ──draw──▶ canvas
```

### Import

An EPUB is imported once, after download, and never unpacked. The importer opens the zip with random access, reads the OPF
(manifest, spine, metadata) and the table of contents (NCX or nav document), and stream-parses each spine item into
**blocks**: paragraphs with styled runs, headings, list items, quotes, images and rules. It writes one compact file per
chapter plus a book index (character counts, table of contents, cover).

Each block keeps its original element path (`div[2]/p[5]`) and a table of its text nodes. That table is what makes
KOReader-compatible positions a lookup instead of a re-parse (see [sync.md](sync.md)).

Because the book is imported once, opening it only reads a few records from disk.

### The line stream

The reader treats the whole book as one sequence of blocks. Chapters are labels on that sequence, so scrolling across a
chapter border is invisible and no chapter is ever loaded whole.

* A **position** is an `Anchor(chapter, block, offset)`: the first visible character. There is no global pixel offset, so
  estimates for text that is not laid out can never make the page jump. A jump (contents, percent, bookmark, sync) sets a
  new anchor and lays out around it.
* A **window** of laid-out blocks surrounds the anchor: roughly three screens ahead and one and a half behind. A single
  background worker extends it when the reader nears an edge and trims it behind the reader. The worker is started under a
  lock so that a request made while it finishes is not lost, and results from an old style or position are discarded
  by a generation counter.
* Each block is laid out with `StaticLayout` at a fixed column width. Lines are never re-wrapped while scrolling.
* Percent is `characters before the anchor / total characters`, both taken from the index.

### Drawing

Everything is drawn on one canvas instead of a lazy list. A list item per line would recompose while scrolling, while the
canvas only reads state in the draw step. When nothing moves, no frames are drawn.

Three layouts share the same wrapped lines:

* **Column**: even margins; the top and bottom rows fade out.
* **Edge-hugging**: rows near the top and bottom shrink and fade so they fit the round screen. This is a visual scale, not
  a re-wrap.
* **Focus band**: rows outside a central band are dimmed.

Input comes from the touch scroller, taps, and the crown. The crown is read in `MainActivity` (`CrownInput`) because
Compose focus was unreliable for it on the watch. The one-handed gestures of the Pixel Watch (double pinch, wrist
turn) turn pages. Top and bottom info lines use `CurvedLayout`.

### Speed reading and auto scroll

Speed reading (`core/speed`) shows one word at a time. `WordSource` walks the book block by block in both directions and
keeps only a window of words in memory. `Pacing` places the focus letter (an optimal recognition point table), and sets
each word's delay from the target speed with extra time for punctuation, long words and numbers, and an ease-in after a
pause. Auto scroll turns words per minute into pixels per second from the column width and text size, and moves the
page from the frame clock.

## Data

| Store | Holds |
| --- | --- |
| Room (`AppDatabase`) | books, reading positions (with a flag for "not yet sent to the server"), bookmarks |
| DataStore | settings as one JSON document |
| Keystore-backed secret store | the two passwords, encrypted with AES-GCM |
| Files | imported chapter files, covers, the original EPUB |

A crash log is written to the app's external files folder so it can be read with `adb pull` without any permission.

## Tile and complications

The tile and the two complications read the book read last through one small query. They are redrawn when the place
moves (not on a timer), after an app update, and when the palette changes. The tile builds its color scheme from the same
palette as the app, so they match.

## Battery

* The default background is black and nothing animates while the screen is idle.
* Nothing polls. Network use happens when a book opens, when the place is sent (after a pause in reading, when the screen
  dims or turns off, and from a queued one-time job that waits for a connection), and in an optional periodic check that
  is off by default.
* Saves and sends are debounced: a scroll step only stores a timestamp; one coroutine waits for the quiet period.
* The list of books is not observed while a book is open.
* Auto scroll and speed reading stop by themselves after 30 minutes so a forgotten session does not hold the screen on.
* Timers (clock, battery) run only for what is shown, and only while the screen is on.

## Tests

`./gradlew :core:test :app:testDebugUnitTest` runs the unit tests: import and parsing, position mapping in both
directions, sync policy against a mock server, the repositories, the view model and the engine (scrolling, layout and
rendering), plus Roborazzi screenshot tests for the screens. Reference screenshots are stored in
`app/src/test/screenshots`; `./gradlew :app:recordRoborazziDebug` re-records them.
