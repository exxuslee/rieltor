package com.rieltor.domain.repository

interface PostJobRepository {
    fun tryStart(telegramUpdateId: Long): Boolean
    fun markPublished(telegramUpdateId: Long, publishId: String)
    fun markFailed(telegramUpdateId: Long, error: String)
}