package com.patreonkt

import java.io.File

/**
 * Callback interface for [PatreonArchiver] progress and lifecycle events.
 *
 * All methods are called on a background coroutine dispatcher; callers must switch to the main
 * thread themselves if UI updates are required (e.g. `withContext(Dispatchers.Main) { ... }`).
 *
 * Provide a [DefaultArchiverListener] instance and override only the methods you need.
 */
interface ArchiverListener {

    /**
     * Fired immediately before a download begins.
     *
     * @param item Metadata for the item about to be fetched.
     */
    fun onDownloadStart(item: DownloadItem)

    /**
     * Fired periodically while bytes are being written to disk.
     *
     * @param item The item being downloaded.
     * @param bytesDownloaded Bytes written so far.
     * @param totalBytes Total expected bytes, or -1 if the server did not send Content-Length.
     */
    fun onProgress(item: DownloadItem, bytesDownloaded: Long, totalBytes: Long)

    /**
     * Fired when a download finishes successfully.
     *
     * @param item The item that was downloaded.
     * @param file The local file that was written.
     */
    fun onDownloadComplete(item: DownloadItem, file: File)

    /**
     * Fired when a download fails after all retry attempts are exhausted.
     *
     * @param item The item that failed.
     * @param error The last exception encountered.
     */
    fun onError(item: DownloadItem, error: Throwable)

    /**
     * Fired after each entity (campaign, post, media, comment) is persisted to the Room database.
     *
     * @param entity The Room entity that was written. Cast to the appropriate type as needed.
     */
    fun onDatabaseWrite(entity: Any)
}

/**
 * No-op implementation of [ArchiverListener]. Override only the events you care about.
 */
open class DefaultArchiverListener : ArchiverListener {
    override fun onDownloadStart(item: DownloadItem) = Unit
    override fun onProgress(item: DownloadItem, bytesDownloaded: Long, totalBytes: Long) = Unit
    override fun onDownloadComplete(item: DownloadItem, file: File) = Unit
    override fun onError(item: DownloadItem, error: Throwable) = Unit
    override fun onDatabaseWrite(entity: Any) = Unit
}
