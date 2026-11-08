# Third-party material

## Demo book

* *Alice's Adventures in Wonderland* by Lewis Carroll, as the EPUB from Project Gutenberg (ebook 11),
  `core/src/main/resources/demo/alices-adventures-in-wonderland.epub`. The file is kept byte for byte as downloaded.
* The text is in the public domain in the USA. The file carries the Project Gutenberg license text in its first chapter.
  Project Gutenberg's trademark rules apply to redistribution of the file.
* Used for: demo mode, unit tests and screenshots.

## Code

All libraries are used as binary dependencies from Maven Central or Google's Maven repository (versions in
`gradle/libs.versions.toml`).

| Library | License |
| --- | --- |
| AndroidX: Core, Activity, Lifecycle, Navigation 3, Room, DataStore, WorkManager, ProfileInstaller, Wear Tiles and ProtoLayout, Watch Face complications | Apache License 2.0 |
| Jetpack Compose UI, Wear Compose (Foundation, Material 3, Navigation 3) | Apache License 2.0 |
| AndroidX Graphics Shapes | Apache License 2.0 |
| Roborazzi, Robolectric, JUnit, MockWebServer (tests only) | Apache License 2.0 / EPL 1.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache License 2.0 |
| OkHttp | Apache License 2.0 |
| kxml2 (tests only) | BSD-style license |

## Fonts

Shipped in `app/src/main/assets/fonts/`, all under the SIL Open Font License 1.1 (free to use, embed and redistribute with
the app, but not to sell on their own).

| Font | Copyright holders |
| --- | --- |
| Lexend | The Lexend Project Authors (Bonnie Shaver-Troup, Thomas Jockin, Santiago Orozco, Hector Gomez, Superunion) |
| Literata | The Literata Project Authors (TypeTogether) |
| Fraunces | The Fraunces Project Authors (Undercase Type) |
| Atkinson Hyperlegible Next | The Atkinson Hyperlegible Next Project Authors (Braille Institute of America) |

The license text of each font is in `app/src/main/assets/fonts/licenses/` and ships with the app. Bookerly is proprietary and is not used.
