package com.rieltor.web

import androidx.room.RoomRawQuery
import com.rieltor.domain.model.CatalogCodes
import com.rieltor.domain.model.CatalogPhoto
import com.rieltor.domain.model.sha256
import com.rieltor.infrastructure.database.model.ListingEntity
import com.rieltor.infrastructure.database.repository.CatalogRepository
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.util.*

@Serializable
data class PublicListing(
    val id: String, val title: String, val description: String, val location: String,
    val locationCode: String?, val typeOfRealty: String?, val category: String,
    val price: String, val currency: String?, val pricePeriod: String, val transactionType: String,
    val area: Double?, val rooms: Int?, val floor: String?, val landAreaSotka: Double?,
    val image: String, val photos: List<String>, val governmentPrograms: List<String>,
    val tags: List<String>, val primeParams: JsonObject, val secondaryParams: JsonObject,
    val cdt: Long,
)

@Serializable data class ListingPage(val items: List<PublicListing>, val nextCursor: String?)
@Serializable private data class CatalogCursor(val signature: String, val value: Long, val id: Long)

class CatalogQuery(private val repository: CatalogRepository, private val publicBaseUrl: String) {
    private val json = Json
    fun list(parameters: Parameters): ListingPage {
        val where = mutableListOf("status = 'ACTIVE'", "transactionType = 'SALE'", "currency = 'USD'", "pricePeriod = 'TOTAL'", "price > 0")
        val arguments = mutableListOf<Any>()
        fun add(sql: String, vararg values: Any) { where += sql; arguments.addAll(values) }
        fun values(name: String, allowed: Set<String>, column: String = name) {
            val values = parameters.getAll(name).orEmpty().flatMap { it.split(',') }.filter { it.isNotEmpty() }.distinct()
            require(values.all { it in allowed }) { "Invalid $name" }
            if (values.isNotEmpty()) add("$column IN (${values.joinToString { "?" }})", *values.toTypedArray())
        }
        values("location", CatalogCodes.locations)
        values("typeOfRealty", CatalogCodes.types)
        val programs = parameters.getAll("governmentPrograms").orEmpty().flatMap { it.split(',') }.filter { it.isNotEmpty() }.distinct()
        require(programs.all { it in CatalogCodes.programs }) { "Invalid governmentPrograms" }
        if (programs.isNotEmpty()) add("EXISTS (SELECT 1 FROM json_each(listings.governmentPrograms) WHERE value IN (${programs.joinToString { "?" }}))", *programs.toTypedArray())
        fun money(key: String): Long? = parameters[key]?.takeIf { it.isNotEmpty() }?.let {
            val result = runCatching { BigDecimal(it).movePointRight(2).longValueExact() }.getOrNull()
            require(result != null && result >= 0) { "Invalid $key" }; result
        }
        val min = money("priceMin"); val max = money("priceMax")
        require(min == null || max == null || min <= max) { "priceMin must not exceed priceMax" }
        val sort = parameters["sort"] ?: "newest"
        require(sort in setOf("newest", "priceAsc", "priceDesc")) { "Invalid sort" }
        if (min != null) add("price >= ?", min)
        if (max != null) add("price <= ?", max)
        parameters["rooms"]?.takeIf { it.isNotBlank() }?.let {
            val rooms = it.toIntOrNull(); require(rooms != null && rooms in 1..30) { "Invalid rooms" }; add("rooms = ?", rooms)
        }
        parameters["query"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            require(it.length <= 200)
            add("(instr(lower(title), lower(?)) > 0 OR instr(lower(COALESCE(address,'')), lower(?)) > 0)", it, it)
        }
        val limit = parameters["limit"]?.let { requireNotNull(it.toIntOrNull()) { "Invalid limit" } } ?: 24
        require(limit in 1..100) { "limit must be 1..100" }
        val signature = sha256(parameters.entries().filter { it.key !in setOf("cursor", "limit") }.sortedBy { it.key }.joinToString { "${it.key}=${it.value.sorted()}" })
        val column = if (sort == "newest") "sourceCreatedAt" else "price"
        val ascending = sort == "priceAsc"
        parameters["cursor"]?.let { encoded ->
            require(encoded.length < 1024) { "Invalid cursor" }
            val cursor = runCatching { json.decodeFromString<CatalogCursor>(Base64.getUrlDecoder().decode(encoded).decodeToString()) }.getOrNull()
            require(cursor != null && cursor.signature == signature) { "Invalid cursor for current filters" }
            add("($column ${if (ascending) ">" else "<"} ? OR ($column = ? AND id < ?))", cursor.value, cursor.value, cursor.id)
        }
        val query = RoomRawQuery("SELECT * FROM listings WHERE ${where.joinToString(" AND ")} ORDER BY $column ${if (ascending) "ASC" else "DESC"}, id DESC LIMIT ?") { statement ->
            (arguments + (limit + 1).toLong()).forEachIndexed { index, value ->
                when (value) { is Long -> statement.bindLong(index + 1, value); is Int -> statement.bindLong(index + 1, value.toLong()); else -> statement.bindText(index + 1, value.toString()) }
            }
        }
        val result = repository.query(query)
        val shown = result.take(limit)
        val cursor = if (result.size > limit) shown.last().let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(CatalogCursor(signature,
                if (sort == "newest") it.sourceCreatedAt else requireNotNull(it.price), it.id)).toByteArray())
        } else null
        return ListingPage(shown.map(::public), cursor)
    }
    fun one(id: Long): PublicListing? = repository.listing(id)?.takeIf {
        it.status == "ACTIVE" && it.transactionType == "SALE" && it.currency == "USD" && it.pricePeriod == "TOTAL" && (it.price ?: 0) > 0
    }?.let(::public)
    fun public(row: ListingEntity): PublicListing {
        val city = mapOf("IRPIN" to "Ірпінь", "BUCHA" to "Буча", "VORZEL" to "Ворзель", "HOSTOMEL" to "Гостомель")[row.location].orEmpty()
        val urls = json.decodeFromString<List<CatalogPhoto>>(row.photos).map { "${publicBaseUrl.trimEnd('/')}/media/${it.fileName}" }
        return PublicListing(row.id.toString(), row.title, row.description, listOfNotNull(city.takeIf { it.isNotEmpty() }, row.address).joinToString(", "),
            row.location, row.typeOfRealty, mapOf("APARTMENT" to "apartments", "NEW_BUILD" to "new-buildings", "HOUSE" to "houses", "LAND" to "land", "COMMERCIAL" to "commercial")[row.typeOfRealty].orEmpty(),
            BigDecimal(requireNotNull(row.price)).movePointLeft(2).toPlainString(), row.currency, row.pricePeriod, row.transactionType,
            row.areaM2, row.rooms, row.floor?.let { "$it${row.totalFloors?.let { total -> " із $total" }.orEmpty()}" }, row.landAreaSotka,
            urls.firstOrNull().orEmpty(), urls, json.decodeFromString(row.governmentPrograms), json.decodeFromString(row.tags),
            json.parseToJsonElement(row.primeParams).jsonObject, json.parseToJsonElement(row.secondaryParams).jsonObject, row.cdt)
    }
}

fun Route.catalogRoutes(query: CatalogQuery) {
    get("/api/listings") {
        try { call.respond(query.list(call.request.queryParameters)) }
        catch (error: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Invalid filters"))) }
    }
    get("/api/listings/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        val listing = id?.let(query::one)
        if (listing == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Listing not found")) else call.respond(listing)
    }
}
