package com.rieltor.application.port

fun interface RepostMasterLimiter {
    suspend fun awaitSlot()

    fun waitUntilMillis(now: Long): Long? = null
}