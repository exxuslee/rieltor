package com.rieltor.application.model

import com.rieltor.infrastructure.database.model.CatalogListingRow

/** One page of catalog rows plus the cursor that fetches the next page. */
data class CatalogListingPage(
    val items: List<CatalogListingRow>,
    val nextCursor: String?,
)
