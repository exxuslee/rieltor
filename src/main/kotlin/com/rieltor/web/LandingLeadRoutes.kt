package com.rieltor.web

import com.rieltor.infrastructure.config.ApplicationSettings
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Duration
import java.time.Instant

@Serializable
data class LandingLeadRequest(
    val formType: String,
    val fields: Map<String, String>,
    val pageUrl: String,
    val website: String = "",
)

internal data class ValidatedLead(val title: String, val fields: List<Pair<String, String>>, val pageUrl: String)

internal class LandingLeadValidator {
    fun validate(request: LandingLeadRequest): ValidatedLead? {
        if (request.website.isNotBlank() || request.fields.size > 8) return null
        val definition = definitions[request.formType] ?: return null
        if (request.fields.keys.any { it !in definition.labels }) return null

        val fields = definition.labels.mapNotNull { (name, label) ->
            request.fields[name]?.let(::clean)?.takeIf(String::isNotBlank)?.let { label to it }
        }
        if (!definition.required.all { required -> fields.any { it.first == definition.labels.getValue(required) } }) return null
        if (fields.any { (_, value) -> value.length > MAX_FIELD_LENGTH }) return null
        if (!fields.any { (label, value) -> label == definition.labels.getValue("phone") && PHONE.matches(value) }) return null

        return normalisePageUrl(request.pageUrl)?.let { ValidatedLead(definition.title, fields, it) }
    }

    private fun normalisePageUrl(value: String): String? = runCatching {
        URI(value.trim()).let { uri ->
            if (uri.scheme !in setOf("https", "http") || uri.host !in allowedHosts) return null
            URI(uri.scheme, uri.authority, uri.path, null, null).toString().take(MAX_PAGE_URL_LENGTH)
        }
    }.getOrNull()

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private data class Definition(
        val title: String,
        val labels: LinkedHashMap<String, String>,
        val required: Set<String>,
    )

    private companion object {
        const val MAX_FIELD_LENGTH = 1_000
        const val MAX_PAGE_URL_LENGTH = 500
        val PHONE = Regex("^[0-9+()\\- ]{7,25}$")
        val allowedHosts = setOf("rieltor.dpdns.org", "localhost", "127.0.0.1")
        val definitions = mapOf(
            "selection" to Definition(
                "Новий запит: підбір нерухомості",
                linkedMapOf("category" to "Що хочете купити", "location" to "Де шукаєте", "phone" to "Телефон"),
                setOf("category", "location", "phone"),
            ),
            "valuation" to Definition(
                "Нова заявка: оцінка нерухомості",
                linkedMapOf(
                    "name" to "Ім’я", "phone" to "Телефон", "address" to "Локація об’єкта",
                    "type" to "Тип нерухомості", "note" to "Коротко про об’єкт",
                ),
                setOf("name", "phone", "address"),
            ),
            "question" to Definition(
                "Нове питання із сайту",
                linkedMapOf("name" to "Ім’я", "phone" to "Телефон", "topic" to "Тема", "message" to "Повідомлення"),
                setOf("name", "phone"),
            ),
        )
    }
}

internal class LandingLeadRateLimiter(
    private val limit: Int = 4,
    private val window: Duration = Duration.ofMinutes(15),
) {
    private val attempts = mutableMapOf<String, ArrayDeque<Instant>>()

    @Synchronized
    fun allow(clientId: String, now: Instant = Instant.now()): Boolean {
        val cutoff = now.minus(window)
        attempts.values.forEach { queue -> while (queue.firstOrNull()?.isBefore(cutoff) == true) queue.removeFirst() }
        attempts.entries.removeIf { it.value.isEmpty() }
        val queue = attempts.getOrPut(clientId) { ArrayDeque() }
        if (queue.size >= limit) return false
        queue.addLast(now)
        return true
    }
}

internal class LandingLeadSender(
    private val httpClient: HttpClient,
    private val settings: ApplicationSettings,
) {
    suspend fun send(lead: ValidatedLead): Boolean {
        if (!settings.landingTelegramConfigured) return false
        val text = buildString {
            append("🔔 ").append(lead.title)
            lead.fields.forEach { (label, value) -> append("\n").append(label).append(": ").append(value) }
            append("\nСторінка: ").append(lead.pageUrl)
        }
        val response = httpClient.post("https://api.telegram.org/bot${settings.landingTelegramBotToken}/sendMessage") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                append("chat_id", settings.landingTelegramChatId)
                append("text", text)
                append("disable_web_page_preview", "true")
            }))
        }
        if (!response.status.isSuccess()) return false
        return response.bodyAsText().contains("\"ok\":true")
    }
}

internal fun Route.landingLeadRoutes(
    sender: LandingLeadSender,
    validator: LandingLeadValidator = LandingLeadValidator(),
    rateLimiter: LandingLeadRateLimiter = LandingLeadRateLimiter(),
) {
    post("/v1/landing/leads") {
        val clientId = call.request.headers["X-Real-IP"] ?: call.request.origin.remoteHost
        if (!rateLimiter.allow(clientId)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("ok" to false))
            return@post
        }

        val lead = validator.validate(call.receive<LandingLeadRequest>())
        if (lead == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false))
            return@post
        }
        if (!sender.send(lead)) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false))
            return@post
        }
        call.respond(HttpStatusCode.OK, mapOf("ok" to true))
    }
}
