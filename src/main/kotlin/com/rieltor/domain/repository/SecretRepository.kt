package com.rieltor.domain.repository

interface SecretRepository {
    fun get(name: String): String?
    fun putIfAbsent(name: String, value: String)
}