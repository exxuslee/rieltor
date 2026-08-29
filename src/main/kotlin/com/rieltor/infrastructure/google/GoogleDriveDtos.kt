package com.rieltor.infrastructure.google

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class DriveFileMetadata(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: String? = null,
    val capabilities: DriveCapabilities? = null,
)

@Serializable
data class DriveCapabilities(
    val canDownload: Boolean = true,
)

@Serializable
data class DriveFileList(
    val files: List<DriveFileMetadata> = emptyList(),
    val nextPageToken: String? = null,
)
