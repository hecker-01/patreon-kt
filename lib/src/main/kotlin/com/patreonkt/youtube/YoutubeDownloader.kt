package com.patreonkt.youtube

import com.patreonkt.ArchiverConfig
import com.patreonkt.db.entities.PostEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads YouTube embeds detected in post content by invoking an external downloader binary
 * via [ProcessBuilder].
 *
 * The binary (e.g. `yt-dlp`) must be accessible on the device and its path must be set in
 * [ArchiverConfig.youtubeDownloaderCommand]. If the command is not configured, [download] is
 * a no-op.
 *
 * @param config Archiver configuration.
 */
class YoutubeDownloader(private val config: ArchiverConfig) {

    /**
     * Downloads a YouTube embed for [post] into [outputDir] if:
     * - [ArchiverConfig.youtubeDownloaderCommand] is configured, and
     * - the post contains a recognised YouTube embed URL.
     *
     * @return The downloaded file, or null if skipped or if the command failed.
     */
    suspend fun download(post: PostEntity, outputDir: File): File? = withContext(Dispatchers.IO) {
        val cmd = config.youtubeDownloaderCommand ?: return@withContext null
        val embedUrl = post.embedUrl ?: return@withContext null
        if (!isYoutubeUrl(embedUrl)) return@withContext null

        outputDir.mkdirs()

        val cookieArgs = config.cookieFile?.let {
            listOf("--cookies", it.absolutePath)
        } ?: emptyList()

        val args = cmd.split(" ") +
            cookieArgs +
            listOf(
                "--no-playlist",
                "-o", "${outputDir.absolutePath}/%(title)s.%(ext)s",
                embedUrl
            )

        val process = ProcessBuilder(args)
            .directory(outputDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode == 0) {
            outputDir.listFiles()?.maxByOrNull { it.lastModified() }
        } else {
            android.util.Log.w("YoutubeDownloader", "yt-dlp exited $exitCode:\n$output")
            null
        }
    }

    private fun isYoutubeUrl(url: String): Boolean =
        url.contains("youtube.com/") || url.contains("youtu.be/")
}
