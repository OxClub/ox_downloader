# Universal Downloader

A native Android download manager (Kotlin, Jetpack Compose, Material 3). Give it a URL (typed, pasted,
shared, or found in the built-in browser); it asks the server what the URL really is, shows what it
found, and downloads the selected files with pause/resume, segmented transfer, retries, queueing,
background execution and notifications.

It downloads publicly accessible or user-authorized resources only. It does **not** implement DRM,
paywall, CAPTCHA, login or other access-control bypasses, never executes downloaded files, and never
logs cookies, passwords or authorization headers.

> **Build status:** this source was written without access to an Android SDK, so it has not been
> compiled by its author. Push it to GitHub: the included workflow builds the debug APK, runs the
> unit tests and uploads both as artifacts. If the build reports an error, fix or report that one error
> (it will be a small, local fix).

## Build
* CI: push to GitHub; `.github/workflows/android-build.yml` does everything (it generates the Gradle wrapper).
* Local: Android Studio (JDK 17), open the folder, run. Or `gradle wrapper && ./gradlew assembleDebug`.
* Unit tests: `./gradlew testDebugUnitTest` (includes real HTTP tests against MockWebServer).
* Device tests: `./gradlew connectedDebugAndroidTest` (Room + Compose UI tests).

## Architecture (`app/src/main/java/com/oxclub/universaldownloader`)
| Package | Role |
|---|---|
| `extractor` | `UrlAnalyzer` (probe = `GET Range: bytes=0-0`), `ResourceDetector`, `MediaDetector`, `HtmlParser` + `LinkExtractor` (jsoup, static HTML only) |
| `data/network` | `HttpFetcher` (manual redirects, host policy, cancellable), `NetworkMonitor` |
| `data/database`, `data/repository` | Room entity/DAO, `DownloadRepository`, `FileStore` (MediaStore / SAF) |
| `data/preferences` | DataStore settings |
| `downloader` | `DownloadManager` (queue), `DownloadEngine`, `SegmentDownloader`, `RetryPolicy`, `ResumeManager`, `PartState`, `SpeedMeter`, `DownloadWorker` |
| `notification` | progress / complete / failed notifications with Pause, Cancel, Open, Share |
| `ui/*` | Compose screens: onboarding, home, analysis, downloads, categories, details, browser, batch, settings, storage, help/FAQ/privacy/licenses |
| `ads`, `premium` | Ad and entitlement abstractions (no-op in this build, no ad SDK, no fake purchases) |

Design notes
* DI is a small manual `AppContainer` (no annotation processing besides Room's KSP).
* Downloads run in the app process; a WorkManager worker runs as a foreground `dataSync` service to keep
  it alive and to restart the queue when the network returns. Interrupted jobs are re-queued on start.
* Data is written to an app-private `.part` file (positional writes, one `FileChannel` shared by up to 16
  segments) and then copied to `Downloads/UniversalDownloader` via MediaStore (no storage permission) or to
  a folder chosen with the Storage Access Framework. This needs transient extra space equal to the file size.
* Resume validates ETag / Last-Modified / length; a changed file restarts instead of being corrupted.
* `minSdk 29` (scoped storage without legacy permissions), `targetSdk 34`.

## Not included (deliberately)
Torrents (category hidden), HLS/DASH downloading (detected and reported only), JavaScript execution for
resource discovery, a real ad SDK, and a purchase flow. Configure ads / contact e-mail through Gradle
properties `adBannerUnitId` and `contactEmail` (never in source).
