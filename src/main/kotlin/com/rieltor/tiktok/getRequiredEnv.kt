package com.rieltor.tiktok

import io.github.cdimascio.dotenv.Dotenv

val dotenv: Dotenv = Dotenv.configure().ignoreIfMissing().load()

fun getRequiredEnv(key: String, dotenv: Dotenv): String {
        val fromSystem = System.getenv(key)
        val value = fromSystem ?: dotenv.get(key)
        return value ?: throw IllegalStateException(
            "Missing required environment variable '$key'. Create a .env file (or set env var) with $key."
        )
    }