package com.rieltor.domain.repository

import com.rieltor.domain.model.StoredThreadsTokens

interface ThreadsTokenRepository {
    fun save(tokens: StoredThreadsTokens)
    fun load(): StoredThreadsTokens?
}
