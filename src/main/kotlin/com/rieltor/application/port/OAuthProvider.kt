package com.rieltor.application.port

/** One external account that can be connected through an OAuth redirect flow. */
interface OAuthProvider {
    /** Stable url segment, e.g. `tiktok` in `/auth/tiktok/login`. */
    val id: String

    /** Human readable provider name used in callback messages. */
    val title: String

    fun authorizeUrl(state: String): String

    /** Exchanges the callback code for tokens and returns optional details to show to the operator. */
    suspend fun exchangeCode(code: String): String?
}
