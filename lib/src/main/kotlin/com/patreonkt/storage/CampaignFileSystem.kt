package com.patreonkt.storage

import com.patreonkt.ArchiverConfig
import com.patreonkt.MediaType
import com.patreonkt.db.entities.CampaignEntity
import com.patreonkt.db.entities.PostEntity
import com.patreonkt.db.entities.ProductEntity
import java.io.File

/**
 * Creates and resolves the on-disk directory hierarchy that mirrors patrickkfkan/patreon-dl output.
 *
 * All directory names follow the patreon-dl convention:
 * - Campaign: `{creator.vanity} - {campaign.name}`  (fallback: `campaign-{id}`)
 * - Post:     `{post.id} - {post.title}`             (fallback: `post-{id}`)
 * - Product:  `{product.id} - {product.name}`        (fallback: `product-{id}`)
 *
 * @param config Archiver configuration providing [ArchiverConfig.outputDir].
 */
class CampaignFileSystem(private val config: ArchiverConfig) {

    /**
     * Returns (and creates) the campaign root directory.
     * E.g. `{outputDir}/mock-creator - Mock Campaign/`
     */
    fun getCampaignDir(campaign: CampaignEntity): File {
        val name = if (!campaign.vanity.isNullOrBlank() && campaign.name.isNotBlank()) {
            sanitize("${campaign.vanity} - ${campaign.name}")
        } else {
            "campaign-${campaign.id}"
        }
        return config.outputDir.resolve(name).also { it.mkdirs() }
    }

    /**
     * Returns (and creates) the `posts/` subdirectory for a campaign.
     */
    fun getPostsDir(campaignDir: File): File =
        campaignDir.resolve("posts").also { it.mkdirs() }

    /**
     * Returns (and creates) the directory for a specific post inside `posts/`.
     * E.g. `{campaignDir}/posts/111111 - First Image Post/`
     */
    fun getPostDir(post: PostEntity, campaignDir: File): File {
        val name = if (post.title.isNotBlank()) {
            sanitize("${post.id} - ${post.title}")
        } else {
            "post-${post.id}"
        }
        return getPostsDir(campaignDir).resolve(name).also { it.mkdirs() }
    }

    /**
     * Returns (and creates) the `shop/` subdirectory for a campaign.
     */
    fun getShopDir(campaignDir: File): File =
        campaignDir.resolve("shop").also { it.mkdirs() }

    /**
     * Returns (and creates) the directory for a specific shop product.
     * E.g. `{campaignDir}/shop/p001 - Mock Product/`
     */
    fun getProductDir(product: ProductEntity, campaignDir: File): File {
        val name = if (product.name.isNotBlank()) {
            sanitize("${product.id} - ${product.name}")
        } else {
            "product-${product.id}"
        }
        return getShopDir(campaignDir).resolve(name).also { it.mkdirs() }
    }

    /**
     * Returns (and creates) the typed media subdirectory inside a post or product directory.
     *
     * Maps [MediaType] to the folder name used by patreon-dl:
     * - IMAGE      → `images/`
     * - AUDIO      → `audio/`
     * - VIDEO      → `video/`
     * - ATTACHMENT → `attachments/`
     * - FILE       → `attachments/`
     * - EMBED      → `embed/`
     */
    fun getMediaDir(parentDir: File, type: MediaType): File {
        val subdir = when (type) {
            MediaType.IMAGE      -> "images"
            MediaType.AUDIO      -> "audio"
            MediaType.VIDEO      -> "video"
            MediaType.ATTACHMENT,
            MediaType.FILE       -> "attachments"
            MediaType.EMBED      -> "embed"
        }
        return parentDir.resolve(subdir).also { it.mkdirs() }
    }

    /**
     * Returns (and creates) the `content_media/` directory inside a product directory.
     */
    fun getContentMediaDir(productDir: File): File =
        productDir.resolve("content_media").also { it.mkdirs() }

    /**
     * Strips characters illegal in most filesystems and collapses runs of whitespace.
     * Truncates the result to 200 characters to avoid PATH_MAX issues.
     */
    fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(200)
}
