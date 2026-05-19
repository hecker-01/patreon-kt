package com.patreonkt.api

/**
 * Abstraction over all Patreon data-fetch operations.
 *
 * Implemented by [PatreonApiClient] for live network calls and by
 * [com.patreonkt.mock.MockPatreonBackend] for hermetic testing without credentials.
 */
interface PatreonApi {

    /**
     * Resolves a Patreon creator URL (e.g. `https://www.patreon.com/creator`) to its numeric
     * campaign ID by scraping the page HTML for `__NEXT_DATA__` bootstrap JSON.
     */
    suspend fun resolveCampaignId(url: String): Result<String>

    /**
     * Fetches the campaign record (including creator relationship) for the given [campaignId].
     */
    suspend fun fetchCampaign(campaignId: String): Result<CampaignResponse>

    /**
     * Fetches a single page of posts for [campaignId].
     *
     * @param cursor Opaque pagination cursor returned by the previous page; null for the first page.
     */
    suspend fun fetchPostsPage(campaignId: String, cursor: String?): Result<PostsPageResponse>

    /**
     * Fetches all shop products for [campaignId].
     */
    suspend fun fetchProducts(campaignId: String): Result<ProductsResponse>

    /**
     * Fetches top-level comments for a post.
     */
    suspend fun fetchComments(postId: String): Result<CommentsResponse>
}
