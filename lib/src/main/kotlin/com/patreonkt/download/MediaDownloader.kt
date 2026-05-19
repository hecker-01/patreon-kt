package com.patreonkt.download

import com.patreonkt.ArchiverConfig
import com.patreonkt.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File

/**
 * Downloads a single media file via OkHttp with streaming I/O and progress callbacks.
 *
 * @param client Shared [OkHttpClient] instance (carries cookies and proxy settings).
 * @param config Archiver configuration (provides retry limit and user-agent).
 */
class MediaDownloader(
    private val client: OkHttpClient,
    private val config: ArchiverConfig
) {

    /**
     * Downloads [item] to [item.localPath], reporting byte progress via [onProgress].
     *
     * Automatically retries up to [ArchiverConfig.maxRetries] times with exponential backoff.
     * Creates parent directories if they do not exist.
     *
     * @param item The item to download.
     * @param onProgress Called periodically with `(bytesDownloaded, totalBytes)`.
     * @return The file that was written.
     * @throws Exception if all retry attempts fail.
     */
    suspend fun download(
        item: DownloadItem,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        withRetry(config.maxRetries) {
            fetchAndWrite(item, onProgress)
        }
    }

    private fun fetchAndWrite(
        item: DownloadItem,
        onProgress: (Long, Long) -> Unit
    ): File {
        val request = Request.Builder()
            .url(item.url)
            .header("User-Agent", config.userAgent)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code} downloading ${item.url}")
            }
            val totalBytes = response.body?.contentLength() ?: -1L
            val destFile = item.localPath
            destFile.parentFile?.mkdirs()

            var bytesRead = 0L
            response.body?.source()?.let { source ->
                val sink = destFile.sink().buffer()
                val buffer = okio.Buffer()
                try {
                    while (true) {
                        val read = source.read(buffer, 8192L)
                        if (read == -1L) break
                        sink.write(buffer, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                    sink.flush()
                } finally {
                    sink.close()
                }
            }
            destFile
        }
    }
}
