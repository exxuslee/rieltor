package com.rieltor.web.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(val error: String)

@Serializable
data class ApiAcknowledgement(val ok: Boolean)
