package com.patreonkt.download

import com.patreonkt.ArchiverConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Downloads HLS (`.m3u8`) playlists and video files.
 *
 * When FFmpegKit is present on the runtime classpath, it is used for proper HLS segment
 * demuxing and remuxing into a single MP4. When absent, the URL is fetched as a direct
 * stream via [MediaDownloader] (suitable for direct-link video files, not adaptive playlists).
 *
 * @param config Archiver configuration.
 * @param mediaDownloader Fallback downloader for when FFmpegKit is unavailable.
 */
class HlsDownloader(
    private val config: ArchiverConfig,
    private val mediaDownloader: MediaDownloader
) {

    private val ffmpegAvailable: Boolean by lazy {
        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    /**
     * Downloads the HLS stream or video file at [url] to [outputFile].
     *
     * @param url Remote `.m3u8` playlist or direct video URL.
     * @param outputFile Destination file (will be created; parent dirs created automatically).
     * @param cookieHeader Value for the `Cookie:` header to pass to FFmpegKit.
     * @return true on success, false on failure.
     */
    suspend fun download(
        url: String,
        outputFile: File,
        cookieHeader: String = ""
    ): Boolean = if (ffmpegAvailable) {
        downloadWithFfmpeg(url, outputFile, cookieHeader)
    } else {
        downloadDirect(url, outputFile)
    }

    private suspend fun downloadWithFfmpeg(
        url: String,
        outputFile: File,
        cookieHeader: String
    ): Boolean = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            outputFile.parentFile?.mkdirs()
            val cookieArgs = if (cookieHeader.isNotBlank())
                "-headers \"Cookie: $cookieHeader\" " else ""
            val cmd = "${cookieArgs}-i \"$url\" -c copy -bsf:a aac_adtstoasc \"${outputFile.absolutePath}\""

            // Reflective invocation to avoid hard dependency at compile time
            val ffmpegKitClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            val returnCodeClass = Class.forName("com.arthenica.ffmpegkit.ReturnCode")
            val callbackClass = Class.forName("com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback")

            val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, _, args ->
                val session = args[0]
                val getReturnCode = session::class.java.getMethod("getReturnCode")
                val returnCode = getReturnCode.invoke(session)
                val isSuccess = returnCodeClass.getMethod("isSuccess", returnCode::class.java)
                    .invoke(null, returnCode) as Boolean
                val getSessionId = session::class.java.getMethod("getSessionId")
                if (cont.isActive) cont.resume(isSuccess)
                null
            }

            val executeAsync = ffmpegKitClass.getMethod(
                "executeAsync",
                String::class.java,
                callbackClass
            )
            executeAsync.invoke(null, cmd, callbackProxy)

            cont.invokeOnCancellation {
                // Best-effort cancel — session ID unavailable in this callback shape
            }
        }
    }

    private suspend fun downloadDirect(url: String, outputFile: File): Boolean {
        return try {
            val item = com.patreonkt.DownloadItem(
                id = outputFile.nameWithoutExtension,
                url = url,
                localPath = outputFile,
                type = com.patreonkt.MediaType.VIDEO
            )
            mediaDownloader.download(item)
            true
        } catch (_: Exception) {
            false
        }
    }
}
