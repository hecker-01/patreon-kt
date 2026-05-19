package com.patreonkt.storage

import com.google.gson.GsonBuilder
import com.patreonkt.db.entities.CampaignEntity
import com.patreonkt.db.entities.PostEntity
import com.patreonkt.db.entities.ProductEntity
import java.io.File

/**
 * Writes JSON sidecar metadata files in the patrickkfkan/patreon-dl format.
 *
 * patreon-dl creates a *directory* named after each info type, containing a single JSON file.
 * This class replicates that structure exactly so archives are compatible with downstream tools:
 *
 * ```
 * {campaignDir}/campaign_info/campaign_info.json
 * {postDir}/post_info/post_info.json
 * {productDir}/product_info/product_info.json
 * ```
 */
class SidecarWriter {

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    /**
     * Writes `{campaignDir}/campaign_info/campaign_info.json`.
     */
    fun writeCampaignInfo(campaignDir: File, campaign: CampaignEntity) {
        val infoDir = campaignDir.resolve("campaign_info").also { it.mkdirs() }
        val payload = buildCampaignPayload(campaign)
        writeJson(infoDir.resolve("campaign_info.json"), payload)
    }

    /**
     * Writes `{postDir}/post_info/post_info.json`.
     */
    fun writePostInfo(postDir: File, post: PostEntity) {
        val infoDir = postDir.resolve("post_info").also { it.mkdirs() }
        val payload = buildPostPayload(post)
        writeJson(infoDir.resolve("post_info.json"), payload)
    }

    /**
     * Writes `{productDir}/product_info/product_info.json`.
     */
    fun writeProductInfo(productDir: File, product: ProductEntity) {
        val infoDir = productDir.resolve("product_info").also { it.mkdirs() }
        val payload = buildProductPayload(product)
        writeJson(infoDir.resolve("product_info.json"), payload)
    }

    // ── Payload builders ───────────────────────────────────────────────────

    private fun buildCampaignPayload(c: CampaignEntity): Map<String, Any?> = mapOf(
        "id"               to c.id,
        "type"             to "campaign",
        "name"             to c.name,
        "vanity"           to c.vanity,
        "summary"          to c.summary,
        "url"              to c.url,
        "currency"         to c.currency,
        "created_at"       to c.createdAt,
        "published_at"     to c.publishedAt,
        "patron_count"     to c.patronCount,
        "avatar_image_url" to c.avatarImageUrl,
        "cover_photo_url"  to c.coverPhotoUrl,
        "creator"          to mapOf(
            "id"        to c.creatorId,
            "full_name" to c.creatorName
        ),
        "raw"              to rawObject(c.rawJson)
    )

    private fun buildPostPayload(p: PostEntity): Map<String, Any?> = mapOf(
        "id"            to p.id,
        "type"          to "post",
        "campaign_id"   to p.campaignId,
        "title"         to p.title,
        "content"       to p.content,
        "teaser_text"   to p.teaserText,
        "post_type"     to p.postType,
        "is_viewable"   to p.isViewable,
        "url"           to p.url,
        "published_at"  to p.publishedAt,
        "edited_at"     to p.editedAt,
        "comment_count" to p.commentCount,
        "embed"         to if (p.embedUrl != null) mapOf(
            "url"     to p.embedUrl,
            "subject" to p.embedSubject
        ) else null,
        "raw"           to rawObject(p.rawJson)
    )

    private fun buildProductPayload(p: ProductEntity): Map<String, Any?> = mapOf(
        "id"           to p.id,
        "type"         to "product",
        "campaign_id"  to p.campaignId,
        "name"         to p.name,
        "description"  to p.description,
        "url"          to p.url,
        "price_cents"  to p.priceCents,
        "currency"     to p.currency,
        "published_at" to p.publishedAt,
        "raw"          to rawObject(p.rawJson)
    )

    private fun rawObject(json: String): Any? = runCatching {
        gson.fromJson(json, Any::class.java)
    }.getOrNull()

    private fun writeJson(file: File, payload: Map<String, Any?>) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(payload))
    }
}
