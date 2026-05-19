package com.patreonkt

import java.io.File
import java.time.LocalDate

/**
 * Represents a single item queued or in-progress for download.
 *
 * @property id Patreon media/post/product identifier.
 * @property url Remote URL being fetched.
 * @property localPath Intended destination path on device storage.
 * @property type Broad category of the item being downloaded.
 * @property parentPostId Post that owns this media item, or null for campaign-level items.
 * @property campaignId Campaign this item belongs to.
 */
data class DownloadItem(
    val id: String,
    val url: String,
    val localPath: File,
    val type: MediaType,
    val parentPostId: String? = null,
    val campaignId: String? = null,
    val displayName: String = id
)

/** Discriminated union of events emitted by [DownloadManager][com.patreonkt.download.DownloadManager]. */
sealed class DownloadEvent {
    data class Start(val item: DownloadItem) : DownloadEvent()
    data class Progress(val item: DownloadItem, val bytesDownloaded: Long, val totalBytes: Long) : DownloadEvent()
    data class Complete(val item: DownloadItem, val file: File) : DownloadEvent()
    data class Error(val item: DownloadItem, val error: Throwable) : DownloadEvent()
    data class DbWrite(val entity: Any) : DownloadEvent()
}

/**
 * Broad categories of downloadable Patreon media.
 */
enum class MediaType {
    IMAGE, AUDIO, VIDEO, ATTACHMENT, FILE, EMBED;

    companion object {
        /** Convenience set containing every [MediaType]. */
        val ALL: Set<MediaType> = values().toSet()
    }
}

/**
 * Proxy configuration for OkHttp.
 *
 * @property host Proxy hostname or IP address.
 * @property port Proxy port number.
 * @property type Protocol type: HTTP, HTTPS, or SOCKS.
 * @property username Optional proxy authentication username.
 * @property password Optional proxy authentication password.
 */
data class ProxyConfig(
    val host: String,
    val port: Int,
    val type: ProxyType = ProxyType.HTTP,
    val username: String? = null,
    val password: String? = null
)

/** Supported proxy protocol types. */
enum class ProxyType { HTTP, SOCKS }

/**
 * Conditions under which the downloader stops before exhausting all pages.
 */
enum class EarlyStopCondition {
    /** Never stop early; download everything. */
    NONE,
    /** Stop when the first already-downloaded post is encountered. */
    ALREADY_DOWNLOADED,
    /** Stop after processing the first page of results. */
    FIRST_PAGE
}

/**
 * Inclusive date range filter for post publication dates.
 *
 * @property from Earliest publication date (inclusive).
 * @property to Latest publication date (inclusive).
 */
data class DateRange(val from: LocalDate, val to: LocalDate)
