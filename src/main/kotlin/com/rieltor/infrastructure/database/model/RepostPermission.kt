package com.rieltor.infrastructure.database.model

internal fun allowsRepost(text: String): Boolean = !text.contains("без реклами", ignoreCase = true)
        && !text.contains("без рекламы", ignoreCase = true)
        && !text.contains("0672165514", ignoreCase = true)
        && !text.contains("067-216-55-14", ignoreCase = true)
        && !text.contains("бартер", ignoreCase = true)
