package com.patreonkt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.patreonkt.api.PatreonApi
import com.patreonkt.api.PatreonApiClient
import com.patreonkt.auth.PatreonCookieJar
import com.patreonkt.db.PatreonDatabase
import com.patreonkt.download.DownloadManager
import com.patreonkt.download.HlsDownloader
import com.patreonkt.download.MediaDownloader
import com.patreonkt.mock.MockPatreonBackend
import com.patreonkt.server.BrowseServerService
import com.patreonkt.storage.CampaignFileSystem
import com.patreonkt.storage.SidecarWriter
import com.patreonkt.youtube.YoutubeDownloader
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

/**
 * Entry point for the patreon-kt library.
 *
 * Construct an instance via [Builder], then call [downloadCampaign] to archive content.
 * The embedded browse server can be started and stopped independently with [startBrowseServer]
 * and [stopBrowseServer].
 *
 * ### Quick start (mock backend)
 * ```kotlin
 * val archiver = PatreonArchiver.Builder(context)
 *     .mockBackend()
 *     .config(ArchiverConfig(outputDir = File(cacheDir, "archive")))
 *     .listener(object : DefaultArchiverListener() {
 *         override fun onDownloadComplete(item: DownloadItem, file: File) {
 *             Log.d("Archiver", "Downloaded: ${file.name}")
 *         }
 *     })
 *     .build()
 *
 * lifecycleScope.launch {
 *     archiver.downloadCampaign("https://www.patreon.com/mock-creator")
 * }
 * ```
 *
 * @property database The Room database. Expose to host apps for direct DAO access.
 */
class PatreonArchiver private constructor(
    private val context: Context,
    private val config: ArchiverConfig,
    private val listener: ArchiverListener,
    private val api: PatreonApi,
    private val cookieJar: PatreonCookieJar
) {
    /** The Room database. Query it directly for custom display logic. */
    val database: PatreonDatabase = PatreonDatabase.getInstance(context, "patreon.db")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileSystem = CampaignFileSystem(config)
    private val sidecarWriter = SidecarWriter()
    private val httpClient = buildOkHttpClient()
    private val mediaDownloader = MediaDownloader(httpClient, config)
    private val hlsDownloader = HlsDownloader(config, mediaDownloader)
    private val youtubeDownloader = YoutubeDownloader(config)
    private val downloadManager = DownloadManager(
        api, config, database, fileSystem, sidecarWriter,
        mediaDownloader, hlsDownloader, youtubeDownloader, cookieJar
    )
    private var serverConnection: ServiceConnection? = null
    private var serverBinder: BrowseServerService.LocalBinder? = null

    init {
        scope.launch {
            downloadManager.events.collect { event ->
                when (event) {
                    is DownloadEvent.Start    -> listener.onDownloadStart(event.item)
                    is DownloadEvent.Progress -> listener.onProgress(event.item, event.bytesDownloaded, event.totalBytes)
                    is DownloadEvent.Complete -> listener.onDownloadComplete(event.item, event.file)
                    is DownloadEvent.Error    -> listener.onError(event.item, event.error)
                    is DownloadEvent.DbWrite  -> listener.onDatabaseWrite(event.entity)
                }
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Downloads the complete campaign at [url].
     *
     * Suspends until all posts, media, and products are processed. Cancellation is cooperative:
     * in-flight downloads are abandoned when the coroutine is cancelled.
     *
     * @param url Patreon creator URL, e.g. `"https://www.patreon.com/creator"`.
     */
    suspend fun downloadCampaign(url: String) {
        downloadManager.downloadCampaign(url)
    }

    /**
     * Downloads media for a single post by URL.
     *
     * The post must have been previously indexed via [downloadCampaign]; this method re-downloads
     * its media files without re-fetching metadata.
     */
    suspend fun downloadPost(url: String) {
        downloadManager.downloadPost(url)
    }

    /**
     * Starts the embedded browse server and returns the port it is listening on.
     *
     * The server binds to `localhost` only. Call [stopBrowseServer] to shut it down.
     *
     * @return The TCP port the server is listening on.
     */
    fun startBrowseServer(): Int {
        var port = 0
        val intent = Intent(context, BrowseServerService::class.java)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val localBinder = binder as BrowseServerService.LocalBinder
                serverBinder = localBinder
                port = localBinder.startServer(config.browseServerPort)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                serverBinder = null
            }
        }
        serverConnection = connection
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        return config.browseServerPort
    }

    /** Stops the embedded browse server. */
    fun stopBrowseServer() {
        serverBinder?.stopServer()
        serverConnection?.let { context.unbindService(it) }
        serverConnection = null
        serverBinder = null
    }

    /** Cancels all in-progress downloads and cleans up coroutine resources. */
    fun cancel() {
        scope.cancel()
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun buildOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", config.userAgent)
                    .build()
            )
        }
        .build()

    // ── Builder ────────────────────────────────────────────────────────────

    /**
     * Fluent builder for [PatreonArchiver].
     *
     * @param context Application context.
     */
    class Builder(private val context: Context) {
        private var config: ArchiverConfig = ArchiverConfig()
        private var listener: ArchiverListener = DefaultArchiverListener()
        private var apiOverride: PatreonApi? = null
        private var cookieJar: PatreonCookieJar? = null

        /** Provides a custom [ArchiverConfig]. */
        fun config(c: ArchiverConfig) = apply { config = c }

        /** Registers a listener for progress and lifecycle events. */
        fun listener(l: ArchiverListener) = apply { listener = l }

        /**
         * Switches to the mock backend, serving synthetic data from bundled JSON assets.
         * No network access or Patreon credentials are required.
         */
        fun mockBackend() = apply { apiOverride = MockPatreonBackend(context) }

        /** Injects a custom [PatreonApi] implementation (advanced; use [mockBackend] for tests). */
        fun api(api: PatreonApi) = apply { apiOverride = api }

        /** Provides an existing [PatreonCookieJar]. If omitted, one is created from [ArchiverConfig.cookieFile]. */
        fun cookieJar(jar: PatreonCookieJar) = apply { cookieJar = jar }

        /** Builds and returns a [PatreonArchiver] instance. */
        fun build(): PatreonArchiver {
            val jar = cookieJar ?: PatreonCookieJar(config.cookieFile)
            val api = apiOverride ?: PatreonApiClient(config, jar)
            return PatreonArchiver(context.applicationContext, config, listener, api, jar)
        }
    }
}
