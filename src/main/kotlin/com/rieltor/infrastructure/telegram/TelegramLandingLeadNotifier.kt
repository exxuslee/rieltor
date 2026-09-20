package com.rieltor.infrastructure.telegram

import com.rieltor.application.port.LandingLeadNotifier
import com.rieltor.domain.model.LandingLead
import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

/** Sends landing leads to the operator chat through the Telegram Bot API. */
class TelegramLandingLeadNotifier(
    private val httpClient: HttpClient,
    private val settings: ApplicationSettings,
) : LandingLeadNotifier {

    override suspend fun notify(lead: LandingLead): Boolean {
        if (!settings.landingTelegramConfigured) return false
        val response = httpClient.post("$API_BASE_URL${settings.landingTelegramBotToken}/sendMessage") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                append("chat_id", settings.landingTelegramChatId)
                append("text", message(lead))
                append("disable_web_page_preview", "true")
            }))
        }
        if (!response.status.isSuccess()) return false
        return response.bodyAsText().contains(SUCCESS_MARKER)
    }

    private fun message(lead: LandingLead): String = buildString {
        append("🔔 ").append(lead.title)
        lead.fields.forEach { field -> append("\n").append(field.label).append(": ").append(field.value) }
        append("\nСторінка: ").append(lead.pageUrl)
    }

    private companion object {
        const val API_BASE_URL = "https://api.telegram.org/bot"
        const val SUCCESS_MARKER = "\"ok\":true"
    }
}
