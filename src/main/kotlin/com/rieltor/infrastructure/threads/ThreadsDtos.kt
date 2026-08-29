package com.rieltor.infrastructure.threads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThreadsTokenResponse(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val error: ThreadsApiError? = null,
)

@Serializable
data class ThreadsIdResponse(val id: String? = null, val error: ThreadsApiError? = null)

@Serializable
data class ThreadsContainerStatus(
    val id: String? = null,
    val status: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    val error: ThreadsApiError? = null,
)

@Serializable
data class ThreadsApiError(
    val message: String = "Unknown Threads API error",
    val type: String? = null,
    val code: Int? = null,
    @SerialName("error_subcode") val errorSubcode: Int? = null,
)
