package com.rieltor.infrastructure.database.mapper

import com.rieltor.domain.model.*
import com.rieltor.domain.usecase.PrepareCatalogListingUseCase
import com.rieltor.infrastructure.database.model.IncomingEntity
import com.rieltor.infrastructure.database.model.ListingEntity
import com.rieltor.infrastructure.google.GoogleDriveLinkExtractor
import kotlinx.serialization.json.*

class CatalogListingMapper(
    private val prepareListing: PrepareCatalogListingUseCase = PrepareCatalogListingUseCase(),
) {
    fun fromIncoming(row: IncomingEntity, type: String?, now: Long): ListingEntity {
        val result = prepareListing(
            ListingImportSource(
                text = row.rawText, typeOfRealty = type,
            photoLinks = GoogleDriveLinkExtractor().extract(row.rawText),
            hasAttachedPhotos = runCatching { Json.decodeFromString<List<SourcePhoto>>(row.sourcePhotos) }
                .getOrDefault(emptyList()).isNotEmpty(),
        ))
        val content = result.content
        return ListingEntity(
            adId = adId(
                row.userId?.toString()
                ?: "unknown-${row.chatId}:${row.messageId}", result.location, type,
                result.price, result.areaM2, result.landAreaSotka
            ),
            chatId = row.chatId, messageId = row.messageId, messageThreadId = row.messageThreadId,
            sourceRevision = sha256("${row.id}:${row.revision}"),
            title = content?.title.orEmpty(),
            description = content?.additionalParameters?.joinToString("\n").orEmpty(),
            rawText = row.rawText, location = result.location, address = content?.address,
            typeOfRealty = result.typeOfRealty,
            tags = Json.encodeToString(content?.hashtags.orEmpty()),
            primeParams = buildJsonObject {
                put("details", JsonArray(content?.keyParameters.orEmpty().map(::JsonPrimitive)))
            }.toString(),
            secondaryParams = buildJsonObject {
                content?.registration?.let { put("registration", it) }
                if (result.hasBargain) put("bargain", "Торг")
            }.toString(),
            governmentPrograms = Json.encodeToString(result.governmentPrograms.toList()),
            googleDriveUrls = Json.encodeToString(result.photoLinks),
            price = result.price, currency = "USD", areaM2 = result.areaM2,
            landAreaSotka = result.landAreaSotka, rooms = result.rooms,
            floor = result.floor, totalFloors = result.totalFloors,
            status = if (result.readyForPublication) ListingStatus.Active else ListingStatus.NeedsReview,
            sourceCreatedAt = maxOf(row.sourceCreatedAt, row.sourceEditedAt),
            createdAt = now, updatedAt = now, publishedAt = now.takeIf { result.readyForPublication },
        )
    }
}
