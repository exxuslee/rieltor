package com.rieltor.domain.model

import java.math.BigDecimal

/** Identity uses parsed catalog values; missing values are explicit, never zero. */
fun adId(
    sender: String, location: String?, typeOfRealty: String?, price: Long?,
    areaM2: Double?, landAreaSotka: Double?
): String {
    fun decimal(value: Double?) = value?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "null"
    return listOf(
        sender, location ?: "null", typeOfRealty ?: "null", price?.toString() ?: "null",
        decimal(areaM2), decimal(landAreaSotka)
    ).joinToString(":")
}

/** TDLib's persisted toString format. Only inspect senderId, never mentions/forward authors. */
fun senderFromRaw(raw: String): String? = senderIdentity(raw)?.value

fun userIdFromRaw(raw: String): Long? = senderIdentity(raw)?.userId

private fun senderIdentity(raw: String): SenderIdentity? =
    userSender.find(raw)?.groupValues?.get(1)?.let { SenderIdentity(it, it.toLongOrNull()) }
        ?: chatSender.find(raw)?.groupValues?.get(1)?.let { SenderIdentity("chat-$it") }

private data class SenderIdentity(val value: String, val userId: Long? = null)

private val userSender = Regex("senderId\\s*=\\s*MessageSenderUser\\s*\\{\\s*userId\\s*=\\s*(\\d+)")
private val chatSender = Regex("senderId\\s*=\\s*MessageSenderChat\\s*\\{\\s*chatId\\s*=\\s*(-?\\d+)")
