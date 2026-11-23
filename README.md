<p align="center"><img src="docs-public/icon-512.png" alt="Lantern icon" width="128"></p>

# Lantern

An unofficial Android reader for [Archive of Our Own](https://archiveofourown.org).

> Unofficial; not affiliated with the OTW or AO3.

Screenshots coming soon.

AO3 has no API, so Lantern reads AO3's public HTML pages and submits AO3's own forms. It does so politely:

- one request at a time, about 1 s apart, through a single fetcher;
- `Retry-After` honoured on a 429, and never a retry loop;
- when Cloudflare answers with a challenge, a WebView opens on that page, the cookies it earns are copied into the app, and the request is retried once.

## Features

- **Search**: AO3's full work search (any field, title, creator; fandoms, characters, relationships, additional tags and tags to exclude, with AO3's autocomplete; rating, warnings, categories; completion, crossover, word count, language, updated date; sort). Filters save as named presets shown as chips; a long-press renames, updates, reorders or deletes one. Result cards show every tag and the full summary, paged 20 at a time. A Continue strip reopens the last work read.
- **Reader**: native Compose text, one chapter per scroll; swipe between chapters; a chapter list; resume at the exact chapter and position; immersive mode (tap the centre to show or hide the controls); font size and font family (the phone's own font, or bundled Literata, Inter, Atkinson Hyperlegible Next, OpenDyslexic and JetBrains Mono). The next chapter is prefetched to a disk cache, and only the current chapter is held in memory.
- **Sign-in**: AO3 username or email and password. The credentials are encrypted with an Android Keystore key, so the app signs in again silently when the session expires. Search and reading work signed out.
- **Library**: History, Marked for Later, Bookmarks, Subscriptions (read live from AO3) and Downloads. Works you have read show a "Read" or "✓ Ch N" badge.
- **Account actions** from the reader: kudos, bookmark (notes, tags, private, rec), mark for later, subscribe, and comment. Signed-in chapter reads are sent as visits, so they count in AO3 History.
- **Downloads**: save a whole work from the reader's ⋯ menu or the chapter-end footer, then read it offline in the same reader. Downloads run in the background, survive sign-out, and are noticed when AO3 posts new chapters ("N new chapters · Update"). The Downloads tab works signed out.
- **Updates**: in-app update checks against GitHub Releases, with a strip on Search and release notes in About.
- **Authors and series**: tapping an author or series anywhere opens its works in the app, with Subscribe in the header.

## Architecture

Two Gradle modules (`settings.gradle.kts`):

| Module | What it holds |
|---|---|
| `:ao3` (Kotlin/JVM) | Everything that knows AO3's markup and URLs. `model` (works, chapters, search queries), `net` (`Ao3Fetcher`, `FetchResult`), `parse` (Jsoup parsers for listings, chapters, the full-work page, forms, the account, the Library), `search` (the search URL builder). |
| `:app` (Android) | `net` (cookie jar, the Cloudflare WebView solver), `account` (`Session`, `CredentialStore`), `data` (repositories, the chapter cache, settings; `data/db` for Room; `data/download` for downloads), `ui` (Compose screens: search, reader, library, account, worklist). |

Key points:

- **One network path.** Every AO3 request, GET or POST, goes through `Ao3Fetcher`, shared from `AppContainer` (manual wiring, no DI framework). Prefetches and downloads are fetched signed out, so they add no History entries.
- **Room v4** stores presets, reading progress, read marks, Library page caches (keyed by account) and downloads. Schemas are exported to `app/schemas/` and the migrations are tested.
- **Downloads** run in a WorkManager worker, one at a time, in the app process and through the same fetcher. The source is AO3's "Entire Work" page. A failed download is retried only when you tap Retry.
- **The download store is format-neutral.** Each chapter is saved as the app's own serialized `ChapterContent` (`filesDir/downloads/{workId}/{index}.json`), and the source is pluggable (`DownloadSource`), so an EPUB source can become the default once AO3 restores EPUB downloads. A new copy is written to a temp directory and swapped in, so a failed update keeps the old copy.

## Install

1. Download the latest `lantern-X.Y.Z.apk` from the [Releases page](https://github.com/JoshItUp/lantern-for-ao3/releases).
2. Allow your browser to install unknown apps when Android asks.
3. Open the downloaded APK and install it.

Google Play Protect may warn that the app is unrecognised or potentially harmful, because Lantern is installed from outside the Play Store and comes from a developer Play Protect doesn't know. Choose to install anyway. The APK on the Releases page is the one built and signed by this repository's release workflow.

There is no Play Store build.

## Updates

Lantern checks GitHub for new releases. Update from the app: Account → About → Check for updates, or tap the update strip on Search. After the first update, later updates install without a prompt.

## Privacy

- Your AO3 credentials are encrypted on the device with an Android Keystore key and never leave it except to sign in to AO3.
- No analytics and no tracking.
- The update check contacts only GitHub; everything else goes to AO3.

## Building

Requirements: the Android SDK with platform 37.2 (`compileSdk`; min and target SDK are 36) and JDK 25, for example Android Studio's bundled JBR (the code targets Java 21):

```sh
export JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:installDebug         # build and install over adb
./gradlew :app:assembleRelease      # minified release build (R8)
```

The version comes from `-Plantern.version=X.Y.Z`, else the latest `v*` git tag, else `0.0.0-dev`.

Release signing reads `~/.lantern/keystore.properties` (keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or the `LANTERN_KEYSTORE_FILE`, `LANTERN_KEYSTORE_PASSWORD`, `LANTERN_KEY_ALIAS` and `LANTERN_KEY_PASSWORD` environment variables. Without either, release builds fall back to the debug key and cannot update a release install.

The debug build adds a developer hook that fetches any AO3 path and saves the page for fixture work:

```sh
adb shell am broadcast -a io.github.joshitup.lantern.DEBUG_FETCH -n io.github.joshitup.lantern/.DebugFetchReceiver --es path '/works/1'
```

Add `--ez anon true` to fetch signed out. Pages land in the app's `files/debug-pages`.

## Releasing

For the maintainer: on GitHub go to Actions → Release → Run workflow and choose patch, minor or major. The workflow tests, builds and signs the APK, verifies its certificate, moves `## [Unreleased]` in `CHANGELOG.md` to the new version and publishes the release as `lantern-X.Y.Z.apk`. Notes written by hand under `## [Unreleased]` are optional: when that section is empty, the notes are generated from the commit subjects since the last tag.

The repository needs these Actions secrets (names only): `LANTERN_KEYSTORE_B64`, `LANTERN_KEYSTORE_PASSWORD`, `LANTERN_KEY_ALIAS`, `LANTERN_KEY_PASSWORD`, `LANTERN_CERT_SHA256`.

## Tests

```sh
./gradlew :ao3:test :app:testDebugUnitTest
```

`:ao3` has plain JUnit tests for the fetcher and every parser. `:app` runs JVM unit tests with Robolectric (Room migrations, repositories, the session, ViewModels, the download pipeline). The HTML fixtures are synthetic: they keep AO3's markup but carry made-up titles, names and ids. Never commit real AO3 data.

## Note on AO3 History

AO3 doesn't write History straight away. Visits are queued and copied in by an hourly background job, which can fall hours behind when AO3 is busy. So History can take up to a few hours to show chapters read in the app, signed in, online or from a download, or to update their visit counts.

## License

MIT. See [LICENSE](LICENSE).

## Credits

The reader bundles these fonts, each under the [SIL Open Font License 1.1](https://openfontlicense.org). Their licence texts ship in the app and live in [app/src/main/assets/licenses](app/src/main/assets/licenses).

- [Literata](https://github.com/googlefonts/literata), by the Literata Project Authors
- [Inter](https://github.com/rsms/inter), by the Inter Project Authors
- [Atkinson Hyperlegible Next](https://github.com/googlefonts/atkinson-hyperlegible-next), by the Atkinson Hyperlegible Next Project Authors
- [OpenDyslexic](https://github.com/antijingoist/opendyslexic), by Abbie Gonzalez
- [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), by the JetBrains Mono Project Authors
