# patreon-kt

An Android library (AAR) that archives Patreon content — posts, images, audio, video, attachments, and shop products — to device storage. Output is 100 % compatible with [patrickkfkan/patreon-dl](https://github.com/patrickkfkan/patreon-dl). All content is indexed in Room with FTS4 full-text search. An embedded NanoHTTPD server exposes a REST API over localhost for browsing your archive from any HTTP client.

A **mock backend** is bundled so you can build and test your app with zero credentials. Real Patreon integration is opt-in.

---

## Table of contents

1. [Requirements](#1-requirements)
2. [Installation](#2-installation)
3. [Required permissions](#3-required-permissions)
4. [ProGuard rules](#4-proguard-rules)
5. [Quick start — mock backend](#5-quick-start--mock-backend)
6. [Real Patreon authentication](#6-real-patreon-authentication)
   - 6.1 [Exporting cookies from a browser](#61-exporting-cookies-from-a-browser)
   - 6.2 [Storing the cookie file on Android](#62-storing-the-cookie-file-on-android)
   - 6.3 [Loading cookies into the archiver](#63-loading-cookies-into-the-archiver)
   - 6.4 [Persisting cookies after a session](#64-persisting-cookies-after-a-session)
   - 6.5 [WebView fallback for Cloudflare-protected pages](#65-webview-fallback-for-cloudflare-protected-pages)
   - 6.6 [Troubleshooting auth failures](#66-troubleshooting-auth-failures)
7. [Running a real download](#7-running-a-real-download)
8. [Listening to progress events](#8-listening-to-progress-events)
9. [ArchiverConfig reference](#9-archiverconfig-reference)
10. [Room database — querying your archive](#10-room-database--querying-your-archive)
11. [Browse server](#11-browse-server)
12. [FFmpegKit — HLS / video downloads](#12-ffmpegkit--hls--video-downloads)
13. [YouTube embed downloads](#13-youtube-embed-downloads)
14. [Output directory structure](#14-output-directory-structure)
15. [patreon-dl compatibility](#15-patreon-dl-compatibility)
16. [Building from source](#16-building-from-source)

---

## 1. Requirements

| Requirement | Minimum |
|-------------|---------|
| Android | API 26 (Android 8.0 Oreo) |
| Kotlin | 1.9 |
| Coroutines | 1.7 |
| AndroidX | any recent version |

---

## 2. Installation

### JitPack (recommended)

Add JitPack to your repository list in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency in your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.hecker-01:patreon-kt:v1.0.0")
}
```

### Transitive dependencies

patreon-kt exposes these as `implementation` (they are included automatically when you use JitPack):

| Library | Version |
|---------|---------|
| `com.squareup.okhttp3:okhttp` | 4.12.0 |
| `com.google.code.gson:gson` | 2.10.1 |
| `androidx.room:room-runtime` | 2.6.1 |
| `org.nanohttpd:nanohttpd` | 2.3.1 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 |

### FFmpegKit — optional, HLS/video only

FFmpegKit is not bundled (it adds ~80 MB). Add it yourself only if you need HLS playlist or video downloading:

```kotlin
implementation("com.arthenica:ffmpeg-kit-full:6.0.LTS")
```

To keep APK size under control, add ABI splits in your app module:

```kotlin
android {
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")   // add "x86_64" for emulators
            isUniversalApk = false
        }
    }
}
```

---

## 3. Required permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Writing to external storage — only needed on API ≤ 29 -->
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="29" />
```

If you are writing to `getExternalFilesDir()` on API 29+, no storage permission is required (it is your app's scoped directory).

---

## 4. ProGuard rules

Add to `proguard-rules.pro`:

```
-keep class com.patreonkt.** { *; }
-keepclassmembers class com.patreonkt.db.entities.** { *; }
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
```

---

## 5. Quick start — mock backend

The fastest way to verify setup. No network access, no cookies, no configuration needed.

```kotlin
// MyActivity.kt
import com.patreonkt.*
import kotlinx.coroutines.launch
import java.io.File

class MyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val archiver = PatreonArchiver.Builder(this)
            .mockBackend()
            .config(
                ArchiverConfig(
                    outputDir = File(filesDir, "archive")
                )
            )
            .listener(object : DefaultArchiverListener() {
                override fun onDownloadComplete(item: DownloadItem, file: File) {
                    Log.d("Archive", "Saved ${file.name}")
                }
                override fun onError(item: DownloadItem, error: Throwable) {
                    Log.e("Archive", "Failed ${item.id}", error)
                }
            })
            .build()

        lifecycleScope.launch {
            // "mock-creator" is the built-in synthetic campaign.
            // Replace with a real URL once you have cookies.
            archiver.downloadCampaign("https://www.patreon.com/mock-creator")
        }
    }
}
```

After this runs, inspect `filesDir/archive/` on your device:

```
archive/
└── mock-creator - Mock Campaign/
    ├── campaign_info/campaign_info.json
    ├── posts/
    │   ├── 111111 - First Image Post/
    │   │   ├── post_info/post_info.json
    │   │   └── images/img001.jpg
    │   └── ...
    └── shop/
        └── p001 - Mock Digital Print/
            └── product_info/product_info.json
```

---

## 6. Real Patreon authentication

Patreon uses session cookies for authentication. The library reads these cookies from a standard **Netscape-format cookie file** that you export once from your browser after logging in.

> **Why cookies, not username/password?**  
> Patreon does not expose a public OAuth flow for third-party apps. Cookies are the same mechanism used by patreon-dl and every other Patreon downloader.

---

### 6.1 Exporting cookies from a browser

You need to be logged in to Patreon in your desktop browser.

#### Chrome / Edge (recommended)

1. Install the extension **[Get cookies.txt LOCALLY](https://chrome.google.com/webstore/detail/get-cookiestxt-locally/cclelndahbckbenkjhflpdbgdldlbecc)**
2. Navigate to `https://www.patreon.com` (make sure you are logged in)
3. Click the extension icon → select **"Export"** → choose **"Current site"**
4. Save the file as `patreon_cookies.txt`

#### Firefox

1. Install **[cookies.txt](https://addons.mozilla.org/firefox/addon/cookies-txt/)**
2. Navigate to `https://www.patreon.com`
3. Click the extension icon → **"Current site"** → save

#### What the file looks like

```
# Netscape HTTP Cookie File
# Generated by patreon-kt

.patreon.com	TRUE	/	TRUE	1893456000	session_id	AbCdEf123...
.patreon.com	TRUE	/	TRUE	1893456000	__cf_bm	xyz987...
.patreon.com	TRUE	/	FALSE	0	patreon_device_id	device123
```

The key cookies are:
- `session_id` — your Patreon session token (most important)
- `__cf_bm` — Cloudflare bot management (short-lived, refreshed automatically)
- `patreon_location_key` — locale/region

> **Security note:** Your `session_id` cookie gives full access to your Patreon account. Treat it like a password. Store the cookie file in your app's private internal storage (see below), never in external/shared storage.

---

### 6.2 Storing the cookie file on Android

Transfer the cookie file from your desktop to your device. The safest location is your app's private internal storage — no other app can read it.

#### Option A — Transfer via adb (development)

```bash
adb push patreon_cookies.txt /data/data/com.yourapp/files/patreon_cookies.txt
```

#### Option B — Load from a file picker at runtime

```kotlin
// In your Activity, let the user pick the cookie file
private val cookiePickerLauncher = registerForActivityResult(
    ActivityResultContracts.GetContent()
) { uri ->
    uri ?: return@registerForActivityResult
    // Copy the file to private storage
    val dest = File(filesDir, "patreon_cookies.txt")
    contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
    Log.d("Auth", "Cookie file saved to ${dest.absolutePath}")
}

// Trigger from a button click:
cookiePickerLauncher.launch("text/*")
```

#### Option C — Bundle for testing only

During development you can include the file in `assets/` and copy it to private storage on first run:

```kotlin
fun copyTestCookies(context: Context): File {
    val dest = File(context.filesDir, "patreon_cookies.txt")
    if (!dest.exists()) {
        context.assets.open("patreon_cookies.txt")
            .use { it.copyTo(dest.outputStream()) }
    }
    return dest
}
```

> Do **not** ship a real cookie file in `assets/` in a production APK — it would expose your session to anyone who decompiles the APK.

---

### 6.3 Loading cookies into the archiver

Pass the cookie file path to `ArchiverConfig`:

```kotlin
val cookieFile = File(filesDir, "patreon_cookies.txt")

val archiver = PatreonArchiver.Builder(this)
    .config(
        ArchiverConfig(
            outputDir    = File(filesDir, "archive"),
            cookieFile   = cookieFile,         // <-- pass your cookie file here
            userAgent    = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                           "AppleWebKit/537.36 (KHTML, like Gecko) " +
                           "Chrome/125.0.0.0 Mobile Safari/537.36"
        )
    )
    .build()

lifecycleScope.launch {
    archiver.downloadCampaign("https://www.patreon.com/creator-vanity-name")
}
```

Replace `creator-vanity-name` with the creator's actual URL slug (the part after `patreon.com/`).

---

### 6.4 Persisting cookies after a session

Patreon occasionally issues new session cookies mid-session (e.g. after a Cloudflare challenge). To save updated cookies back to disk so you do not have to re-export from your browser:

```kotlin
// Access the cookie jar after a download completes
val cookieJar = PatreonCookieJar(cookieFile)

val archiver = PatreonArchiver.Builder(this)
    .cookieJar(cookieJar)   // pass the jar directly so you keep a reference
    .config(ArchiverConfig(outputDir = File(filesDir, "archive")))
    .build()

lifecycleScope.launch {
    archiver.downloadCampaign("https://www.patreon.com/my-creator")
    // After the run, persist any refreshed cookies back to disk
    cookieJar.saveToFile(cookieFile)
    Log.d("Auth", "Cookies saved")
}
```

---

### 6.5 WebView fallback for Cloudflare-protected pages

Some Patreon pages return HTTP 403 to direct requests because Cloudflare's JavaScript challenge cannot be solved without a real browser engine. Enable the WebView fallback to handle these automatically:

```kotlin
ArchiverConfig(
    cookieFile         = File(filesDir, "patreon_cookies.txt"),
    useWebViewFallback = true       // hidden WebView used when direct HTTP fails
)
```

**Requirements for the fallback:**
- Must be constructed with an `Activity` context (not `applicationContext`) so the WebView can attach to a window.
- The device must have the system WebView installed (true on all modern Android devices).
- The first fallback request takes ~2–4 seconds while the page loads.

The fallback automatically harvests the cookies the WebView receives (including the short-lived `__cf_bm` Cloudflare token) and adds them to the cookie jar, so subsequent requests in the same session can use them directly.

```kotlin
// Pass 'this' (an Activity), not applicationContext
val archiver = PatreonArchiver.Builder(this)   // <-- Activity context
    .config(
        ArchiverConfig(
            cookieFile         = File(filesDir, "patreon_cookies.txt"),
            useWebViewFallback = true
        )
    )
    .build()
```

---

### 6.6 Troubleshooting auth failures

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `HTTP 401` or `HTTP 403` on all requests | Session cookie expired | Re-export cookies from browser |
| `HTTP 403` only on the first request, then OK | Cloudflare challenge | Enable `useWebViewFallback = true` |
| Posts return `isViewable = false` | Logged-in user is not a patron at the required tier | Check tier membership in the browser |
| `session_id` cookie missing from file | Exported from the wrong site tab | Re-export while on `https://www.patreon.com` |
| Download starts but images are 0 bytes | Image CDN uses a different domain | Check that your cookie file includes `.patreonusercontent.com` cookies |
| `IllegalStateException: Could not find campaignId` | Page HTML changed or Cloudflare blocked the scrape | Enable `useWebViewFallback = true` |

**Verifying your cookie file manually:**

```bash
# On your desktop — grep for the session cookie
grep "session_id" patreon_cookies.txt
# Should print one line with a long token value
```

**Checking cookie expiry:**

The fifth tab-separated column in each cookie line is a Unix timestamp. Expired cookies have a timestamp in the past:

```bash
# Print name + expiry for each cookie
awk -F'\t' '{print $6, $5}' patreon_cookies.txt
# session_id 1893456000  <- year 2030, fine
# __cf_bm 1700000000     <- expired, will be refreshed by Cloudflare
```

---

## 7. Running a real download

```kotlin
val archiver = PatreonArchiver.Builder(this)
    .config(
        ArchiverConfig(
            outputDir        = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS
                ),
                "patreon-archive"
            ),
            cookieFile       = File(filesDir, "patreon_cookies.txt"),
            maxParallelJobs  = 3,
            requestSpacingMs = 800,   // be polite — 800 ms between API calls
            maxRetries       = 3,
            downloadThumbnails = true,
            downloadComments   = false,
            // Only download images and audio; skip video and attachments
            mediaTypeFilter  = setOf(MediaType.IMAGE, MediaType.AUDIO),
            // Only posts from 2024 onward
            dateRange        = DateRange(
                from = LocalDate.of(2024, 1, 1),
                to   = LocalDate.now()
            )
        )
    )
    .listener(progressListener)
    .build()

lifecycleScope.launch(Dispatchers.IO) {
    try {
        archiver.downloadCampaign("https://www.patreon.com/creator-name")
    } catch (e: Exception) {
        Log.e("Archive", "Campaign download failed", e)
    }
}
```

### Cancellation

`downloadCampaign` is a suspend function that respects coroutine cancellation. Cancel the enclosing scope or call `archiver.cancel()` to abort in-flight downloads:

```kotlin
val job = lifecycleScope.launch {
    archiver.downloadCampaign("https://www.patreon.com/creator-name")
}

// Cancel from a stop button
binding.stopButton.setOnClickListener { job.cancel() }
```

---

## 8. Listening to progress events

Implement `ArchiverListener` (or extend `DefaultArchiverListener` and override only what you need):

```kotlin
val listener = object : DefaultArchiverListener() {

    override fun onDownloadStart(item: DownloadItem) {
        // item.displayName  — filename or post title
        // item.type         — MediaType.IMAGE / AUDIO / VIDEO / etc.
        // item.campaignId   — owning campaign ID
        Log.d("Archive", "▶ ${item.displayName}")
    }

    override fun onProgress(item: DownloadItem, bytesDownloaded: Long, totalBytes: Long) {
        if (totalBytes > 0) {
            val pct = (bytesDownloaded * 100 / totalBytes).toInt()
            // Update a ProgressBar on the main thread
            runOnUiThread { progressBar.progress = pct }
        }
    }

    override fun onDownloadComplete(item: DownloadItem, file: File) {
        Log.d("Archive", "✓ ${file.name}  (${file.length() / 1024} KB)")
    }

    override fun onError(item: DownloadItem, error: Throwable) {
        Log.e("Archive", "✗ ${item.displayName}: ${error.message}")
        // item is still available for retry logic
    }

    override fun onDatabaseWrite(entity: Any) {
        // entity is one of: CampaignEntity, PostEntity, MediaEntity, ProductEntity
        when (entity) {
            is com.patreonkt.db.entities.PostEntity ->
                Log.d("Archive", "DB ← post: ${entity.title}")
            is com.patreonkt.db.entities.MediaEntity ->
                Log.d("Archive", "DB ← media: ${entity.filename}")
        }
    }
}
```

> All listener callbacks run on a background dispatcher. Wrap UI updates in `runOnUiThread { }` or `withContext(Dispatchers.Main) { }`.

---

## 9. ArchiverConfig reference

```kotlin
ArchiverConfig(
    outputDir            = File(filesDir, "archive"),
    maxParallelJobs      = 3,           // concurrent downloads (default 3)
    requestSpacingMs     = 500L,        // ms between API requests (default 500)
    maxRetries           = 3,           // per-file retry attempts (default 3)

    // ── Content filters ──────────────────────────────────────────────────
    tierFilter           = setOf("Early Access", "Premium"),
                                        // empty = all tiers (default)
    mediaTypeFilter      = MediaType.ALL,
                                        // or setOf(IMAGE, AUDIO, VIDEO, ATTACHMENT, EMBED)
    dateRange            = DateRange(
                             from = LocalDate.of(2023, 6, 1),
                             to   = LocalDate.now()
                           ),           // null = no date restriction (default)
    includePreviewContent = false,      // download teaser media for locked posts
    downloadThumbnails   = true,
    downloadComments     = false,

    // ── Behaviour ────────────────────────────────────────────────────────
    dryRun               = false,       // log only, no writes
    earlyStopCondition   = EarlyStopCondition.NONE,
                                        // NONE | ALREADY_DOWNLOADED | FIRST_PAGE

    // ── Network ──────────────────────────────────────────────────────────
    proxy                = ProxyConfig(
                             host = "10.0.0.1",
                             port = 8080,
                             type = ProxyType.HTTP   // HTTP or SOCKS
                           ),           // null = no proxy (default)
    userAgent            = "Mozilla/5.0 ...",

    // ── Auth ─────────────────────────────────────────────────────────────
    cookieFile           = File(filesDir, "patreon_cookies.txt"),
    useWebViewFallback   = false,

    // ── External tools ───────────────────────────────────────────────────
    youtubeDownloaderCommand = "/data/data/com.termux/files/usr/bin/yt-dlp",
                                        // null = skip YouTube embeds (default)
    browseServerPort     = 8765
)
```

---

## 10. Room database — querying your archive

The Room database is exposed directly on the `PatreonArchiver` instance so you can drive your own UI from it.

```kotlin
val db = archiver.database
```

### Get all campaigns

```kotlin
// As a Flow — re-emits whenever campaigns change
db.campaignDao().getAll().collect { campaigns ->
    adapter.submitList(campaigns)
}

// One-shot snapshot
val campaigns = db.campaignDao().getAllOnce()
```

### Get posts for a campaign

```kotlin
// Flow — ideal for a RecyclerView via ListAdapter
db.postDao().getPostsByCampaign(campaignId).collect { posts ->
    adapter.submitList(posts)
}

// Snapshot
val posts = db.postDao().getPostsByCampaignOnce(campaignId)
```

### Full-text search

Backed by an SQLite FTS4 index across post `title`, `content`, and `teaserText`.

```kotlin
// Returns posts whose title/content matches the query
val results = db.postDao().search("watercolour tutorial")

// Prefix search
val results = db.postDao().search("water*")

// Column-scoped search
val results = db.postDao().search("title:exclusive")
```

### Get media for a post

```kotlin
val mediaList = db.mediaDao().getMediaForPost(postId)

for (media in mediaList) {
    val localFile = media.localPath?.let { File(it) }
    if (localFile?.exists() == true) {
        // load into ImageView, ExoPlayer, etc.
    }
}
```

### Sample: wire posts to a RecyclerView in a ViewModel

```kotlin
class ArchiveViewModel(app: Application) : AndroidViewModel(app) {

    private val archiver = PatreonArchiver.Builder(app)
        .mockBackend()
        .build()

    val db = archiver.database

    fun posts(campaignId: String): Flow<List<PostEntity>> =
        db.postDao().getPostsByCampaign(campaignId)

    fun search(query: String): Flow<List<PostEntity>> = flow {
        emit(db.postDao().search(query))
    }
}
```

```kotlin
// In your Fragment
viewModel.posts(campaignId)
    .flowWithLifecycle(lifecycle)
    .onEach { adapter.submitList(it) }
    .launchIn(lifecycleScope)
```

---

## 11. Browse server

An embedded NanoHTTPD server lets you browse your archive over HTTP from any device on localhost — useful for a web front-end, `curl` debugging, or Postman testing.

```kotlin
// Start
val port = archiver.startBrowseServer()
Log.d("Server", "Listening on http://localhost:$port")

// Stop
archiver.stopBrowseServer()
```

All endpoints return `Content-Type: application/json` with `Access-Control-Allow-Origin: *`.

### Endpoints

#### `GET /api/campaigns`
```bash
curl http://localhost:8765/api/campaigns
# [ { "id": "12345678", "name": "Mock Campaign", "vanity": "mock-creator", ... } ]
```

#### `GET /api/campaigns/{id}`
```bash
curl http://localhost:8765/api/campaigns/12345678
```

#### `GET /api/campaigns/{id}/posts?page=0&limit=20`
```bash
curl "http://localhost:8765/api/campaigns/12345678/posts?page=0&limit=10"
# { "posts": [...], "total": 47, "page": 0, "limit": 10 }
```

#### `GET /api/posts/{id}`
```bash
curl http://localhost:8765/api/posts/111111
```

#### `GET /api/posts/{id}/media`
```bash
curl http://localhost:8765/api/posts/111111/media
# [ { "id": "m001", "mediaType": "IMAGE", "filename": "img001.jpg",
#     "localPath": "/data/data/.../files/archive/...img001.jpg", ... } ]
```

#### `GET /api/search?q={query}`
```bash
curl "http://localhost:8765/api/search?q=audio+episode"
# { "query": "audio episode", "results": [ { "id": "222222", "title": "Audio Episode 1", ... } ] }
```

#### `GET /api/settings` and `PUT /api/settings`
```bash
# Read
curl http://localhost:8765/api/settings
# { "theme": "dark", "pageSize": "25" }

# Write
curl -X PUT http://localhost:8765/api/settings \
     -H "Content-Type: application/json" \
     -d '{"theme":"dark","pageSize":"25"}'
```

#### `GET /api/media/stream/{id}`
Streams the local file for a media record. Supports `Range` for video scrubbing:
```bash
curl -r 0-1023 http://localhost:8765/api/media/stream/m001 -o chunk.jpg
```

---

## 12. FFmpegKit — HLS / video downloads

When FFmpegKit is on the classpath, `HlsDownloader` automatically uses it to demux `.m3u8` playlists and remux the result into a single MP4. No code changes are needed — the library detects FFmpegKit at runtime.

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.hecker-01:patreon-kt:v1.0.0")
    implementation("com.arthenica:ffmpeg-kit-full:6.0.LTS")  // opt-in
}
```

When FFmpegKit is **not** present, HLS URLs fall back to a direct-stream download (works for plain MP4 links, not adaptive playlists).

---

## 13. YouTube embed downloads

Patreon posts often embed YouTube videos. The library detects these automatically and delegates to an external binary (e.g. `yt-dlp`).

```kotlin
ArchiverConfig(
    youtubeDownloaderCommand = "/data/data/com.termux/files/usr/bin/yt-dlp",
    cookieFile = File(filesDir, "patreon_cookies.txt")
)
```

The binary is invoked via `ProcessBuilder` with:
```
yt-dlp --cookies {cookieFile} --no-playlist -o "{outputDir}/%(title)s.%(ext)s" {url}
```

If `youtubeDownloaderCommand` is null (the default), YouTube embeds are skipped silently.

---

## 14. Output directory structure

```
{outputDir}/
└── {creator.vanity} - {campaign.name}/
    ├── campaign_info/
    │   └── campaign_info.json          ← campaign + creator metadata
    ├── posts/
    │   └── {post.id} - {post.title}/
    │       ├── post_info/
    │       │   └── post_info.json      ← post metadata + embed info
    │       ├── images/
    │       │   ├── photo1.jpg
    │       │   └── photo2.jpg
    │       ├── audio/
    │       │   └── episode.mp3
    │       ├── video/
    │       │   └── clip.mp4
    │       ├── attachments/
    │       │   └── bonus.pdf
    │       └── embed/
    │           └── youtube_dQw4w9WgXcQ.info.json
    └── shop/
        └── {product.id} - {product.name}/
            ├── product_info/
            │   └── product_info.json
            └── content_media/
```

Directory names are sanitised (illegal filesystem characters stripped, truncated to 200 chars).  
Fallback name if vanity/title is empty: `campaign-{id}`, `post-{id}`, `product-{id}`.

---

## 15. patreon-dl compatibility

The folder layout, sidecar file names, and JSON schemas match [patrickkfkan/patreon-dl](https://github.com/patrickkfkan/patreon-dl) exactly. Archives produced by this library can be used with any tool that consumes patreon-dl output, and vice versa.

Key compatibility points:
- Sidecar directories are named `campaign_info/`, `post_info/`, `product_info/` (not bare files)
- Sidecar JSON includes a `raw` field containing the original Patreon JSON-API response
- Media subdirectory names: `images/`, `audio/`, `video/`, `attachments/`, `content_media/`, `embed/`
- Campaign directory: `{vanity} - {name}` with a space-dash-space separator
- Post/product directory: `{id} - {title}`

---

## 16. Building from source

Prerequisites: JDK 17, Android SDK with platform API 34.

```bash
# Clone
git clone https://github.com/hecker-01/patreon-kt.git
cd patreon-kt

# Build the AAR
./gradlew :lib:assembleDebug

# Run all tests (mock pipeline, FTS, browse server)
./gradlew :lib:test

# Build release AAR
./gradlew :lib:assembleRelease
```

Output AAR: `lib/build/outputs/aar/lib-release.aar`  
Room schema: `lib/schemas/com.patreonkt.db.PatreonDatabase/1.json`

---

## License

Apache 2.0
