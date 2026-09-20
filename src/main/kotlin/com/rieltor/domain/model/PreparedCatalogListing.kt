package com.rieltor.domain.model

/** Source content and media availability, independent of transport and persistence. */
data class ListingImportSource(
    val text: String,
    val typeOfRealty: String?,
    val photoLinks: List<String> = emptyList(),
    val hasAttachedPhotos: Boolean = false,
)

data class PreparedCatalogListing(
    val content: ListingMessage?,
    val location: String?,
    val typeOfRealty: String?,
    val price: Long?,
    val areaM2: Double?,
    val landAreaSotka: Double?,
    val rooms: Int?,
    val floor: Int?,
    val totalFloors: Int?,
    val governmentPrograms: Set<String>,
    val hasBargain: Boolean,
    val photoLinks: List<String>,
    val warnings: List<String>,
) {
    val readyForPublication: Boolean get() = warnings.isEmpty()
}
