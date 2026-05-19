# patreon-kt

An Android library (AAR) for archiving Patreon content to device storage. Downloads posts,
collections, shop products, images, audio, attachments, and videos using cookie-based
authentication. Output is 100% compatible with [patrickkfkan/patreon-dl](https://github.com/patrickkfkan/patreon-dl),
so archives can be read by any tool that consumes patreon-dl output.

All content is indexed in a local SQLite database via Room with FTS4 full-text search. An
embedded NanoHTTPD server exposes a REST API over localhost for browsing content from any
HTTP client.

A mock backend is bundled so the library works **immediately without any configuration or
credentials** — real Patreon integration is entirely optional.

---

## Requirements

- Android API 26+
- Kotlin 1.9+
- AndroidX

---

## Gradle setup

Add the AAR to your project. Via local file:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/lib-release.aar"))

    // Required transitive dependencies
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### FFmpegKit (optional — HLS support)

FFmpegKit adds ~80 MB to your APK. Include it only if you need HLS/video downloads:

```kotlin
implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2")
```

To reduce APK size with ABI splits:

```kotlin
android {
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}
```

---

## Required permissions

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<!-- For writing to external storage on API 28 and below -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="29" />
```

---

## ProGuard rules

Add to your `proguard-rules.pro`:

```
-keep class com.patreonkt.** { *; }
-keepclassmembers class com.patreonkt.db.entities.** { *; }
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-keep class com.google.gson.** { *; }
-keepattributes Signature
```

---

## Quick start

```kotlin
import com.patreonkt.*
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val archiver = PatreonArchiver.Builder(this)
            .mockBackend()                             // no credentials needed
            .config(
                ArchiverConfig(
                    outputDir = File(filesDir, "archive"),
                    maxParallelJobs = 3,
                    downloadThumbnails = true,
                    dryRun = false
                )
            )
            .listener(object : DefaultArchiverListener() {
                override fun onDownloadStart(item: DownloadItem) {
                    Log.d("Archiver", "Downloading: ${item.displayName}")
                }
                override fun onDownloadComplete(item: DownloadItem, file: File) {
                    Log.d("Archiver", "Saved: ${file.absolutePath}")
                }
                override fun onError(item: DownloadItem, error: Throwable) {
                    Log.e("Archiver", "Failed: ${item.id}", error)
                }
            })
            .build()

        lifecycleScope.launch {
            archiver.downloadCampaign("https://www.patreon.com/mock-creator")
        }

        // Start browse server
        val port = archiver.startBrowseServer()
        Log.d("Archiver", "Browse server: http://localhost:$port")
    }
}
```

---

## ArchiverConfig reference

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `outputDir` | `File` | `{tmpdir}/patreon-archive` | Base directory for campaign folders |
| `maxParallelJobs` | `Int` | `3` | Max concurrent file downloads |
| `requestSpacingMs` | `Long` | `500` | Min ms between Patreon API requests |
| `maxRetries` | `Int` | `3` | Max retry attempts per download |
| `tierFilter` | `Set<String>` | `emptySet()` | Tier titles to include; empty = all tiers |
| `mediaTypeFilter` | `Set<MediaType>` | all types | Media types to download |
| `dateRange` | `DateRange?` | `null` | Filter by post publication date |
| `includePreviewContent` | `Boolean` | `false` | Download teaser media for locked posts |
| `downloadThumbnails` | `Boolean` | `true` | Download post cover images |
| `downloadComments` | `Boolean` | `false` | Fetch and store post comments |
| `dryRun` | `Boolean` | `false` | Log without writing files or DB |
| `earlyStopCondition` | `EarlyStopCondition` | `NONE` | When to stop before all pages are processed |
| `proxy` | `ProxyConfig?` | `null` | HTTP/HTTPS/SOCKS proxy settings |
| `userAgent` | `String` | Chrome Android UA | HTTP User-Agent header |
| `cookieFile` | `File?` | `null` | Netscape-format cookie file for auth |
| `useWebViewFallback` | `Boolean` | `false` | Use hidden WebView for blocked pages |
| `youtubeDownloaderCommand` | `String?` | `null` | Path to yt-dlp/youtube-dl binary |
| `browseServerPort` | `Int` | `8765` | Browse server TCP port |

---

## ArchiverListener events

| Method | When fired |
|--------|------------|
| `onDownloadStart(item)` | Before each file download begins |
| `onProgress(item, bytes, total)` | Periodically while bytes are written; `total=-1` if unknown |
| `onDownloadComplete(item, file)` | After a file is successfully saved |
| `onError(item, error)` | After all retry attempts are exhausted |
| `onDatabaseWrite(entity)` | After each Room entity is persisted |

All callbacks run on a background dispatcher. Switch to `Dispatchers.Main` for UI updates.

---

## Mock backend

The mock backend serves synthetic data from bundled JSON assets — no network or credentials
required:

```kotlin
val archiver = PatreonArchiver.Builder(context)
    .mockBackend()
    .build()
```

Mock data contains:
- 1 campaign: `mock-creator` / "Mock Campaign"
- 3 posts: image post (2 JPEGs), audio post (MP3), text post with YouTube embed
- 1 shop product

The mock backend is ideal for unit tests, CI, and demos.

---

## Cookie authentication

Export cookies from your browser after logging in to Patreon:

1. Install a browser extension such as "Get cookies.txt LOCALLY"
2. Navigate to `https://www.patreon.com` while logged in
3. Export cookies in Netscape format to a file (e.g. `patreon_cookies.txt`)
4. Pass the file to the config:

```kotlin
ArchiverConfig(
    cookieFile = File(filesDir, "patreon_cookies.txt")
)
```

### Netscape cookie format

Each line: `domain  TRUE  path  secure  expiry  name  value`

```
.patreon.com    TRUE    /    TRUE    1893456000    session_id    abc123...
```

### WebView fallback

For pages that return HTTP 403 to direct requests, enable the WebView fallback:

```kotlin
ArchiverConfig(useWebViewFallback = true)
```

The WebView fallback harvests cookies from `CookieManager` after the page loads. It must be
called from a UI context (Activity/Fragment).

---

## Browse server

Start and stop the embedded HTTP server:

```kotlin
val port = archiver.startBrowseServer()   // returns actual port
archiver.stopBrowseServer()
```

### Endpoints

#### `GET /api/campaigns`
All campaigns.
```json
[{ "id": "12345678", "name": "Mock Campaign", ... }]
```

#### `GET /api/campaigns/{id}`
Single campaign by ID.

#### `GET /api/campaigns/{id}/posts?page=0&limit=20`
Posts for a campaign, paginated.
```json
{ "posts": [...], "total": 3, "page": 0, "limit": 20 }
```

#### `GET /api/posts/{id}`
Single post.

#### `GET /api/posts/{id}/media`
All media associated with a post.

#### `GET /api/search?q={query}`
FTS4 full-text search across post titles, content, and teaser text.
Supports SQLite FTS4 syntax: `word*` (prefix), `"exact phrase"`, `title:word` (column filter).
```json
{ "query": "audio", "results": [...] }
```

#### `GET /api/settings`
Read persisted settings (stored in SharedPreferences).
```json
{ "theme": "dark", "pageSize": "25" }
```

#### `PUT /api/settings`
Write settings. Body: JSON object.
```bash
curl -X PUT http://localhost:8765/api/settings \
     -H "Content-Type: application/json" \
     -d '{"theme":"dark"}'
```

#### `GET /api/media/stream/{id}`
Stream a locally downloaded file by its media ID. Supports HTTP `Range` headers for
video scrubbing. Returns the file with its original MIME type.

---

## Room database

The database is exposed directly for custom queries:

```kotlin
val db = archiver.database

// Get all campaigns
val campaigns = db.campaignDao().getAllOnce()

// Observe posts as Flow
db.postDao().getPostsByCampaign("12345678").collect { posts ->
    // runs on background thread; use withContext(Dispatchers.Main) for UI
}

// Full-text search
val results = db.postDao().search("keyword")

// Get media for a post
val media = db.mediaDao().getMediaForPost("111111")
```

### Schema

| Table | Key columns |
|-------|-------------|
| `campaigns` | id, name, vanity, summary, url, creatorName, rawJson |
| `posts` | id, campaignId, title, content, teaserText, postType, publishedAt, rawJson |
| `posts_fts` | FTS4 virtual table over title, content, teaserText |
| `media` | id, postId, campaignId, mediaType, filename, url, localPath, downloadedAt |
| `tags` | id, name |
| `post_tag_cross_refs` | postId, tagId |
| `comments` | id, postId, authorName, body, publishedAt, parentCommentId |
| `products` | id, campaignId, name, description, priceCents, currency, rawJson |

---

## YouTube downloader hook

To download YouTube embeds, provide a path to `yt-dlp` (or `youtube-dl`):

```kotlin
ArchiverConfig(
    youtubeDownloaderCommand = "/data/data/com.termux/files/usr/bin/yt-dlp",
    cookieFile = File(filesDir, "cookies.txt")
)
```

The binary must be executable on the device. It is invoked via `ProcessBuilder` with:
- `--cookies {cookieFile}` (if configured)
- `--no-playlist`
- `-o {outputDir}/%(title)s.%(ext)s`

---

## patreon-dl compatibility

Output directories match the patrickkfkan/patreon-dl layout exactly:

```
{outputDir}/
└── {creator.vanity} - {campaign.name}/       # e.g. "mock-creator - Mock Campaign"
    ├── campaign_info/
    │   └── campaign_info.json
    ├── posts/
    │   └── {post.id} - {post.title}/         # e.g. "111111 - First Image Post"
    │       ├── post_info/
    │       │   └── post_info.json
    │       ├── images/
    │       ├── audio/
    │       ├── video/
    │       ├── attachments/
    │       └── embed/
    └── shop/
        └── {product.id} - {product.name}/
            ├── product_info/
            │   └── product_info.json
            └── content_media/
```

Sidecar JSON files contain all Patreon API attributes plus a `raw` field holding the full
JSON-API response for maximum compatibility.

---

## Building from source

Prerequisites: JDK 17, Android SDK with API 34.

```bash
./gradlew :lib:assembleDebug      # Build debug AAR
./gradlew :lib:assembleRelease    # Build release AAR
./gradlew :lib:test               # Run JVM unit tests
```

Output AAR: `lib/build/outputs/aar/lib-release.aar`

---

## License

Apache 2.0
