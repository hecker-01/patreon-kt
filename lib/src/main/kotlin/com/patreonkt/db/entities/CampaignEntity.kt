package com.patreonkt.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted campaign record.
 *
 * @property id Patreon campaign identifier (numeric string).
 * @property name Display name of the campaign.
 * @property vanity Creator's vanity URL slug (e.g. `"my-creator"`).
 * @property summary Campaign description / about text.
 * @property url Canonical campaign URL.
 * @property currency Patron billing currency code (e.g. `"USD"`).
 * @property createdAt ISO-8601 creation timestamp.
 * @property publishedAt ISO-8601 publish timestamp.
 * @property patronCount Number of active patrons at last fetch.
 * @property avatarImageUrl Creator avatar image URL.
 * @property coverPhotoUrl Campaign cover photo URL.
 * @property creatorId Linked Patreon user identifier.
 * @property creatorName Full display name of the creator.
 * @property rawJson Full Patreon JSON-API response stored verbatim for sidecar output.
 */
@Entity(tableName = "campaigns")
data class CampaignEntity(
    @PrimaryKey val id: String,
    val name: String,
    val vanity: String?,
    val summary: String?,
    val url: String?,
    val currency: String?,
    val createdAt: String?,
    val publishedAt: String?,
    val patronCount: Int = 0,
    val avatarImageUrl: String?,
    val coverPhotoUrl: String?,
    val creatorId: String?,
    val creatorName: String?,
    val rawJson: String
)
