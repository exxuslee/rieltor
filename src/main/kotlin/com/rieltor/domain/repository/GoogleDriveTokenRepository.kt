package com.rieltor.domain.repository

import com.rieltor.domain.model.StoredGoogleDriveTokens

interface GoogleDriveTokenRepository {
    fun save(tokens: StoredGoogleDriveTokens)
    fun load(): StoredGoogleDriveTokens?
}