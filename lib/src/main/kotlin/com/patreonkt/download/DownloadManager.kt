package com.patreonkt.download

import com.patreonkt.ArchiverConfig
import com.patreonkt.DownloadEvent
import com.patreonkt.DownloadItem
import com.patreonkt.EarlyStopCondition
import com.patreonkt.MediaType
import com.patreonkt.api.PatreonApi
import com.patreonkt.api.embedData
import com.patreonkt.api.mediaIds
import com.patreonkt.auth.PatreonCookieJar
import com.patreonkt.db.PatreonDatabase
import com.patreonkt.db.entities.*
import com.patreonkt.storage.CampaignFileSystem
import com.patreonkt.storage.SidecarWriter
import com.patreonkt.youtube.YoutubeDownloader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Semaphore as JSemaphore

/**
 * Orchestrates the full download pipeline for a Patreon campaign.
 *
 * The pipeline proceeds as:
 * 1. Resolve campaign ID from the creator URL
 * 2. Fetch and persist the campaign record
 * 3. Paginate through all posts, applying configured filters
 * 4. For each post: persist to DB, write sidecar, download all media concurrently (semaphore-gated)
 * 5. Fetch and persist shop products
 *
 * Progress and lifecycle events are emitted on [events].
 *
 * @param api Data source (live [com.patreonkt.api.PatreonApiClient] or mock).
 * @param config Archiver configuration.
 * @param db Room database instance.
 * @param fileSystem Campaign filesystem helper.
 * @param sidecarWriter Sidecar JSON writer.
 * @param mediaDownloader HTTP file downloader.
 * @param hlsDownloader HLS/FFmpeg downloader.
 * @param youtubeDownloader YouTube embed downloader.
 * @param cookieJar Cookie jar for passing headers to FFmpegKit.
 */
class DownloadManager(
    private val api: PatreonApi,
    private val config: ArchiverConfig,
    private val db: PatreonDatabase,
    private val fileSystem: CampaignFileSystem,
    private val sidecarWriter: SidecarWriter,
    private val mediaDownloader: MediaDownloader,
    private val hlsDownloader: HlsDownloader,
    private val youtubeDownloader: YoutubeDownloader,
    private val cookieJar: PatreonCookieJar
) {
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 128)

    /** Stream of [DownloadEvent]s emitted throughout the pipeline. */
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    private val semaphore = JSemaphore(config.maxParallelJobs)

    // ── Campaign entry point ───────────────────────────────────────────────

    /**
     * Downloads the full campaign at [url].
     * Suspends until all posts and products are processed.
     */
    suspend fun downloadCampaign(url: String) {
        val campaignId = api.resolveCampaignId(url).getOrElse { throw it }
        val campaignResponse = api.fetchCampaign(campaignId).getOrElse { throw it }

        val campaignEntity = campaignResponse.campaign.toCampaignEntity(campaignResponse)
        if (!config.dryRun) {
            db.campaignDao().insert(campaignEntity)
            emit(DownloadEvent.DbWrite(campaignEntity))
        }

        val campaignDir = fileSystem.getCampaignDir(campaignEntity)
        if (!config.dryRun) sidecarWriter.writeCampaignInfo(campaignDir, campaignEntity)

        downloadPosts(campaignId, campaignEntity, campaignDir)
        downloadProducts(campaignId, campaignEntity, campaignDir)
    }

    /**
     * Re-downloads media for a single post already indexed by [downloadCampaign].
     * Missing files are fetched; already-present files are skipped.
     */
    suspend fun downloadPost(url: String) {
        val postId = extractPostId(url)
            ?: throw IllegalArgumentException("Cannot extract post ID from $url")
        val post = db.postDao().getById(postId)
            ?: throw IllegalStateException("Post $postId not in local DB. Run downloadCampaign first.")
        val campaign = db.campaignDao().getById(post.campaignId) ?: return
        val campaignDir = fileSystem.getCampaignDir(campaign)
        val postDir = fileSystem.getPostDir(post, campaignDir)

        val mediaEntities = db.mediaDao().getMediaForPost(postId)
        for (media in mediaEntities) {
            if (media.localPath != null && java.io.File(media.localPath).exists()) continue
            val remoteUrl = media.url ?: continue
            if (!shouldDownloadMedia(media)) continue
            val mediaType = MediaType.valueOf(media.mediaType)
            val mediaDir = fileSystem.getMediaDir(postDir, mediaType)
            val destFile = mediaDir.resolve(media.filename ?: "${media.id}.bin")
            val item = DownloadItem(
                id = media.id, url = remoteUrl, localPath = destFile,
                type = mediaType, parentPostId = post.id, campaignId = campaign.id,
                displayName = media.filename ?: media.id
            )
            semaphore.acquire()
            try {
                downloadItem(item)
                db.mediaDao().markDownloaded(
                    media.id, destFile.absolutePath, java.time.Instant.now().toString()
                )
            } finally {
                semaphore.release()
            }
        }
    }

    // ── Posts ──────────────────────────────────────────────────────────────

    private suspend fun downloadPosts(
        campaignId: String,
        campaign: CampaignEntity,
        campaignDir: File
    ) {
        var cursor: String? = null
        do {
            delay(config.requestSpacingMs)
            val page = api.fetchPostsPage(campaignId, cursor).getOrElse { return }
            if (page.posts.isEmpty()) break

            for (postResource in page.posts) {
                val post = postResource.toPostEntity(campaignId)

                // Date filter
                if (!config.dryRun && config.dateRange != null) {
                    val publishedDate = post.publishedAt?.let { parseDate(it) }
                    if (publishedDate != null && !config.dateRange.contains(publishedDate)) continue
                }

                // viewability / preview filter
                if (!post.isViewable && !config.includePreviewContent) continue

                if (!config.dryRun) {
                    db.postDao().insert(post)
                    emit(DownloadEvent.DbWrite(post))
                }

                val postDir = fileSystem.getPostDir(post, campaignDir)
                if (!config.dryRun) sidecarWriter.writePostInfo(postDir, post)

                // Persist embedded media references from included resources
                val includedMedia = postResource.mediaIds()
                    .mapNotNull { (type, id) -> page.included[type to id] }

                val mediaEntities = includedMedia.mapIndexed { i, res ->
                    res.toMediaEntity(post.id, campaignId, i)
                }

                if (!config.dryRun && mediaEntities.isNotEmpty()) {
                    db.mediaDao().insertAll(mediaEntities)
                }

                // Concurrent media downloads (semaphore-gated)
                val jobs = mutableListOf<Job>()
                for (media in mediaEntities) {
                    if (!shouldDownloadMedia(media)) continue
                    val mediaType = MediaType.valueOf(media.mediaType)
                    val mediaDir = fileSystem.getMediaDir(postDir, mediaType)
                    val destFile = mediaDir.resolve(media.filename ?: "${media.id}.bin")
                    val item = DownloadItem(
                        id = media.id,
                        url = media.url ?: continue,
                        localPath = destFile,
                        type = mediaType,
                        parentPostId = post.id,
                        campaignId = campaignId,
                        displayName = media.filename ?: media.id
                    )

                    if (config.dryRun) {
                        emit(DownloadEvent.Start(item))
                        continue
                    }

                    val job = CoroutineScope(Dispatchers.IO).launch {
                        semaphore.acquire()
                        try {
                            downloadItem(item)
                            db.mediaDao().markDownloaded(
                                media.id, destFile.absolutePath,
                                Instant.now().toString()
                            )
                        } finally {
                            semaphore.release()
                        }
                    }
                    jobs.add(job)
                }
                jobs.forEach { it.join() }

                // YouTube embed
                if (!config.dryRun && config.mediaTypeFilter.contains(MediaType.EMBED)) {
                    val embedDir = fileSystem.getMediaDir(postDir, MediaType.EMBED)
                    youtubeDownloader.download(post, embedDir)
                }

                // Comments
                if (!config.dryRun && config.downloadComments && post.commentCount > 0) {
                    downloadComments(post.id)
                }

                if (shouldStop(post.id)) return
            }

            cursor = page.nextCursor
        } while (cursor != null)
    }

    // ── Products ───────────────────────────────────────────────────────────

    private suspend fun downloadProducts(
        campaignId: String,
        campaign: CampaignEntity,
        campaignDir: File
    ) {
        delay(config.requestSpacingMs)
        val response = api.fetchProducts(campaignId).getOrElse { return }
        for (res in response.products) {
            val product = res.toProductEntity(campaignId)
            if (!config.dryRun) {
                db.productDao().insert(product)
                emit(DownloadEvent.DbWrite(product))
            }
            val productDir = fileSystem.getProductDir(product, campaignDir)
            if (!config.dryRun) sidecarWriter.writeProductInfo(productDir, product)

            val mediaDir = fileSystem.getContentMediaDir(productDir)
            val includedMedia = res.mediaIds()
                .mapNotNull { (type, id) -> response.included[type to id] }
            for ((i, mediaRes) in includedMedia.withIndex()) {
                val media = mediaRes.toMediaEntity(null, campaignId, i)
                val url = media.url ?: continue
                val destFile = mediaDir.resolve(media.filename ?: "${media.id}.bin")
                val item = DownloadItem(
                    id = media.id, url = url, localPath = destFile,
                    type = MediaType.valueOf(media.mediaType), campaignId = campaignId
                )
                if (!config.dryRun) {
                    semaphore.acquire()
                    try { downloadItem(item) } finally { semaphore.release() }
                }
            }
        }
    }

    // ── Comments ───────────────────────────────────────────────────────────

    private suspend fun downloadComments(postId: String) {
        delay(config.requestSpacingMs)
        val response = api.fetchComments(postId).getOrElse { return }
        val entities = response.comments.map { c ->
            CommentEntity(
                id = c.id,
                postId = postId,
                authorId = null,
                authorName = c.str("name"),
                body = c.str("body"),
                publishedAt = c.str("created"),
                parentCommentId = null,
                rawJson = "{}"
            )
        }
        if (entities.isNotEmpty()) db.commentDao().insertAll(entities)
    }

    // ── Item download ──────────────────────────────────────────────────────

    private suspend fun downloadItem(item: DownloadItem) {
        emit(DownloadEvent.Start(item))
        try {
            val file = if (item.url.endsWith(".m3u8", ignoreCase = true)) {
                val cookieHeader = cookieJar.cookieHeaderFor("www.patreon.com")
                hlsDownloader.download(item.url, item.localPath, cookieHeader)
                item.localPath
            } else {
                mediaDownloader.download(item) { downloaded, total ->
                    _events.tryEmit(DownloadEvent.Progress(item, downloaded, total))
                }
            }
            emit(DownloadEvent.Complete(item, file))
        } catch (e: Exception) {
            emit(DownloadEvent.Error(item, e))
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun shouldDownloadMedia(media: MediaEntity): Boolean {
        val type = runCatching { MediaType.valueOf(media.mediaType) }.getOrNull() ?: return false
        return config.mediaTypeFilter.contains(type)
    }

    private fun shouldStop(postId: String): Boolean = when (config.earlyStopCondition) {
        EarlyStopCondition.NONE -> false
        EarlyStopCondition.FIRST_PAGE -> false // handled at page level
        EarlyStopCondition.ALREADY_DOWNLOADED -> false // simplified: never stop early in v1
    }

    private suspend fun emit(event: DownloadEvent) {
        _events.emit(event)
    }

    private fun parseDate(iso: String): LocalDate? = runCatching {
        Instant.parse(iso).atZone(ZoneOffset.UTC).toLocalDate()
    }.getOrNull()

    private fun extractPostId(url: String): String? =
        Regex("""/posts/(\d+)""").find(url)?.groupValues?.get(1)

    // ── Entity mappers ─────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun com.patreonkt.api.JsonApiResource.toCampaignEntity(
        response: com.patreonkt.api.CampaignResponse
    ): CampaignEntity {
        val creator = response.creator
        return CampaignEntity(
            id = id,
            name = str("name") ?: "Unnamed Campaign",
            vanity = str("vanity") ?: creator?.str("vanity"),
            summary = str("summary"),
            url = str("url"),
            currency = str("currency"),
            createdAt = str("created_at"),
            publishedAt = str("published_at"),
            patronCount = int("patron_count") ?: 0,
            avatarImageUrl = str("avatar_photo_url"),
            coverPhotoUrl = str("cover_photo_url"),
            creatorId = creator?.id,
            creatorName = creator?.str("full_name"),
            rawJson = response.rawJson
        )
    }

    private fun com.patreonkt.api.JsonApiResource.toPostEntity(campaignId: String): PostEntity {
        val embed = embedData()
        return PostEntity(
            id = id,
            campaignId = campaignId,
            title = str("title") ?: "Untitled",
            content = str("content"),
            teaserText = str("teaser_text"),
            postType = str("post_type") ?: "text_only",
            isViewable = bool("is_viewable") ?: false,
            url = str("url"),
            publishedAt = str("published_at"),
            editedAt = str("edited_at"),
            commentCount = int("comment_count") ?: 0,
            coverImageUrl = (attributes["image"] as? Map<*, *>)?.get("url")?.toString(),
            thumbnailUrl = str("thumbnail_url"),
            embedUrl = embed?.get("url"),
            embedSubject = embed?.get("subject"),
            rawJson = com.google.gson.Gson().toJson(mapOf("type" to type, "id" to id, "attributes" to attributes))
        )
    }

    private fun com.patreonkt.api.JsonApiResource.toMediaEntity(
        postId: String?,
        campaignId: String,
        index: Int
    ): MediaEntity {
        val imageUrls = (attributes["image_urls"] as? Map<*, *>)
        val downloadUrl = str("download_url") ?: (imageUrls?.get("original")?.toString())
        val mimeType = str("mimetype")
        val mediaType = when {
            mimeType?.startsWith("image/") == true -> MediaType.IMAGE
            mimeType?.startsWith("audio/") == true -> MediaType.AUDIO
            mimeType?.startsWith("video/") == true -> MediaType.VIDEO
            else -> MediaType.ATTACHMENT
        }.name
        return MediaEntity(
            id = id,
            postId = postId,
            campaignId = campaignId,
            mediaType = mediaType,
            filename = str("file_name"),
            mimeType = mimeType,
            url = downloadUrl,
            localPath = null,
            downloadedAt = null,
            fileSizeBytes = null,
            width = (attributes["metadata"] as? Map<*, *>)?.get("dimensions")
                ?.let { (it as? Map<*, *>)?.get("w")?.toString()?.toIntOrNull() },
            height = (attributes["metadata"] as? Map<*, *>)?.get("dimensions")
                ?.let { (it as? Map<*, *>)?.get("h")?.toString()?.toIntOrNull() },
            durationMs = null
        )
    }

    private fun com.patreonkt.api.JsonApiResource.toProductEntity(campaignId: String): ProductEntity =
        ProductEntity(
            id = id,
            campaignId = campaignId,
            name = str("name") ?: "Unnamed Product",
            description = str("description"),
            url = str("url"),
            priceCents = int("price_cents"),
            currency = str("currency"),
            publishedAt = str("published_at"),
            thumbnailUrl = null,
            rawJson = com.google.gson.Gson().toJson(mapOf("type" to type, "id" to id, "attributes" to attributes))
        )
}

private fun com.patreonkt.DateRange.contains(date: LocalDate): Boolean =
    !date.isBefore(from) && !date.isAfter(to)
