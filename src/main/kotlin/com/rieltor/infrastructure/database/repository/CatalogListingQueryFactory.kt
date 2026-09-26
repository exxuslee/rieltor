package com.rieltor.infrastructure.database.repository

import androidx.room.RoomRawQuery
import com.rieltor.domain.model.CatalogFilter
import com.rieltor.infrastructure.database.model.CatalogListingRow

/** Builds the SQL behind a [CatalogFilter]: SQL stays in the persistence layer. */
object CatalogListingQueryFactory {

    private val BASE_CONDITIONS = listOf("status = 'ACTIVE'", "currency = 'USD'", "price > 0")

    fun create(filter: CatalogFilter): RoomRawQuery {
        val conditions = BASE_CONDITIONS.toMutableList()
        val arguments = mutableListOf<Any>()

        fun add(sql: String, vararg values: Any) {
            conditions += sql
            arguments.addAll(values)
        }

        if (filter.chatIds.isNotEmpty()) {
            add("chatId IN (${placeholders(filter.chatIds.size)})", *filter.chatIds.toTypedArray())
        }
        if (filter.locations.isNotEmpty()) {
            add("location IN (${placeholders(filter.locations.size)})", *filter.locations.toTypedArray())
        }
        if (filter.types.isNotEmpty()) {
            add("typeOfRealty IN (${placeholders(filter.types.size)})", *filter.types.toTypedArray())
        }
        if (filter.programs.isNotEmpty()) {
            add(
                "EXISTS (SELECT 1 FROM json_each(adsTab.governmentPrograms) " +
                        "WHERE value IN (${placeholders(filter.programs.size)}))",
                *filter.programs.toTypedArray(),
            )
        }
        filter.priceMin?.let { add("price >= ?", it) }
        filter.priceMax?.let { add("price <= ?", it) }
        filter.rooms?.let { add("rooms = ?", it) }
        filter.search?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.distinct()?.forEach { word ->
            // SQLite lower() handles ASCII only. Fold Ukrainian/Russian capitals explicitly.
            val text = word.filter { it in "абвгґдеєёжзиіїйклмнопрстуфхцчшщъыьэюя" }.toSet()
                .fold("lower(COALESCE(rawText,''))") { sql, letter ->
                    "replace($sql, '${letter.uppercaseChar()}', '$letter')"
                }
            add("instr($text, ?) > 0", word)
        }
        filter.cursor?.let { cursor ->
            val comparison = if (filter.sort.ascending) ">" else "<"
            add(
                "(${filter.sort.column} $comparison ? OR (${filter.sort.column} = ? AND id < ?))",
                cursor.value, cursor.value, cursor.id,
            )
        }

        val direction = if (filter.sort.ascending) "ASC" else "DESC"
        val sql = "SELECT ${CatalogListingRow.COLUMNS} FROM adsTab " +
                "WHERE ${conditions.joinToString(" AND ")} " +
                "ORDER BY ${filter.sort.column} $direction, id DESC LIMIT ?"
        val values = arguments + (filter.limit + 1).toLong()

        return RoomRawQuery(sql) { statement ->
            values.forEachIndexed { index, value ->
                when (value) {
                    is Long -> statement.bindLong(index + 1, value)
                    is Int -> statement.bindLong(index + 1, value.toLong())
                    else -> statement.bindText(index + 1, value.toString())
                }
            }
        }
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString()
}
