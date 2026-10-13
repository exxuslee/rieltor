package com.rieltor.infrastructure.database.model

internal fun allowsRepost(text: String): Boolean = !text.contains("без реклами", ignoreCase = true)
