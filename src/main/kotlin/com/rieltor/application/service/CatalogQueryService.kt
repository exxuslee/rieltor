package com.rieltor.application.service

import com.rieltor.domain.model.CatalogCursor
import com.rieltor.domain.model.CatalogFilter
import com.rieltor.domain.model.CatalogListingPage
import com.rieltor.domain.model.CatalogSort
import com.rieltor.infrastructure.database.model.CatalogListingRow
import com.rieltor.infrastructure.database.repository.CatalogListingQueryFactory
import com.rieltor.infrastructure.database.repository.CatalogRepository

/** Reads the public catalog: one extra row is fetched to detect whether a next page exists. */
class CatalogQueryService(
    private val repository: CatalogRepository,
    private val cursors: CatalogCursorCodec = CatalogCursorCodec(),
) {
    fun page(filter: CatalogFilter): CatalogListingPage {
        val rows = repository.query(CatalogListingQueryFactory.create(filter))
        val items = rows.take(filter.limit)
        val nextCursor = items.lastOrNull()
            ?.takeIf { rows.size > filter.limit }
            ?.let { last -> cursors.encode(CatalogCursor(filter.signature, sortValue(filter.sort, last), last.id)) }
        return CatalogListingPage(items, nextCursor)
    }

    fun listing(id: Long): CatalogListingRow? = repository.publicListing(id)

    private fun sortValue(sort: CatalogSort, row: CatalogListingRow): Long =
        if (sort == CatalogSort.NEWEST) row.timestamp else requireNotNull(row.price)
}
