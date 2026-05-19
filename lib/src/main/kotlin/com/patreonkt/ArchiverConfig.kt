package com.patreonkt

import java.io.File

/**
 * Complete configuration for a [PatreonArchiver] instance.
 *
 * All fields have sensible defaults so the archiver works immediately with the mock backend
 * without any configuration. Override individual fields as needed.
 *
 * @property outputDir Base directory where campaign folders are created.
 * @property maxParallelJobs Maximum number of concurrent file downloads.
 * @property requestSpacingMs Minimum milliseconds between Patreon API requests.
 * @property maxRetries Maximum retry attempts for a failed download before giving up.
 * @property tierFilter Patreon reward tier titles to include; empty set means all tiers.
 * @property mediaTypeFilter Set of [MediaType]s to download; defaults to all types.
 * @property dateRange Optional publication date filter; null means no date restriction.
 * @property includePreviewContent Whether to download teaser/preview media for locked posts.
 * @property downloadThumbnails Whether to download post cover images and thumbnails.
 * @property downloadComments Whether to fetch and store post comments.
 * @property dryRun When true, logs intended actions but writes no files and makes no DB writes.
 * @property earlyStopCondition Condition under which pagination stops before all posts are seen.
 * @property proxy Optional proxy to route all OkHttp traffic through.
 * @property userAgent HTTP User-Agent header sent with every request.
 * @property cookieFile Optional Netscape-format cookie file for session authentication.
 * @property useWebViewFallback When true, a hidden WebView is used to retrieve pages that block
 *   direct HTTP access. Requires a UI context.
 * @property youtubeDownloaderCommand Shell command for the external YouTube downloader
 *   (e.g. `"/data/data/com.termux/files/usr/bin/yt-dlp"`). Null disables YouTube downloads.
 * @property browseServerPort TCP port for the embedded [BrowseServer][com.patreonkt.server.BrowseServer].
 */
data class ArchiverConfig(
    val outputDir: File = File(System.getProperty("java.io.tmpdir"), "patreon-archive"),
    val maxParallelJobs: Int = 3,
    val requestSpacingMs: Long = 500L,
    val maxRetries: Int = 3,
    val tierFilter: Set<String> = emptySet(),
    val mediaTypeFilter: Set<MediaType> = MediaType.ALL,
    val dateRange: DateRange? = null,
    val includePreviewContent: Boolean = false,
    val downloadThumbnails: Boolean = true,
    val downloadComments: Boolean = false,
    val dryRun: Boolean = false,
    val earlyStopCondition: EarlyStopCondition = EarlyStopCondition.NONE,
    val proxy: ProxyConfig? = null,
    val userAgent: String = DEFAULT_USER_AGENT,
    val cookieFile: File? = null,
    val useWebViewFallback: Boolean = false,
    val youtubeDownloaderCommand: String? = null,
    val browseServerPort: Int = 8765
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }

    /** Fluent builder for [ArchiverConfig]. */
    class Builder {
        private var config = ArchiverConfig()

        fun outputDir(dir: File) = apply { config = config.copy(outputDir = dir) }
        fun maxParallelJobs(n: Int) = apply { config = config.copy(maxParallelJobs = n) }
        fun requestSpacingMs(ms: Long) = apply { config = config.copy(requestSpacingMs = ms) }
        fun maxRetries(n: Int) = apply { config = config.copy(maxRetries = n) }
        fun tierFilter(vararg tiers: String) = apply { config = config.copy(tierFilter = tiers.toSet()) }
        fun mediaTypeFilter(vararg types: MediaType) = apply { config = config.copy(mediaTypeFilter = types.toSet()) }
        fun dateRange(range: DateRange?) = apply { config = config.copy(dateRange = range) }
        fun includePreviewContent(v: Boolean) = apply { config = config.copy(includePreviewContent = v) }
        fun downloadThumbnails(v: Boolean) = apply { config = config.copy(downloadThumbnails = v) }
        fun downloadComments(v: Boolean) = apply { config = config.copy(downloadComments = v) }
        fun dryRun(v: Boolean) = apply { config = config.copy(dryRun = v) }
        fun earlyStopCondition(c: EarlyStopCondition) = apply { config = config.copy(earlyStopCondition = c) }
        fun proxy(p: ProxyConfig?) = apply { config = config.copy(proxy = p) }
        fun userAgent(ua: String) = apply { config = config.copy(userAgent = ua) }
        fun cookieFile(f: File?) = apply { config = config.copy(cookieFile = f) }
        fun useWebViewFallback(v: Boolean) = apply { config = config.copy(useWebViewFallback = v) }
        fun youtubeDownloaderCommand(cmd: String?) = apply { config = config.copy(youtubeDownloaderCommand = cmd) }
        fun browseServerPort(port: Int) = apply { config = config.copy(browseServerPort = port) }

        fun build(): ArchiverConfig = config
    }
}
