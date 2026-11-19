package com.projectfocus.feature.airport.search

import com.projectfocus.core.database.entity.AirportEntity

/** Test-only DAO fixture. The production query contract is tested against real SQLite. */
internal fun sourceSqlRows(
    rows: List<AirportEntity>,
    query: String,
    limit: Int,
    hasPremium: Boolean,
): List<AirportEntity> {
    fun asciiFold(value: String) = value.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
    fun like(value: String, pattern: String): Boolean {
        val regex = buildString {
            append('^')
            for (char in asciiFold(pattern)) append(when (char) {
                '%' -> ".*"
                '_' -> "."
                else -> Regex.escape(char.toString())
            })
            append('$')
        }
        return Regex(regex, RegexOption.DOT_MATCHES_ALL).matches(asciiFold(value))
    }
    fun rank(row: AirportEntity) = when {
        row.iataCode == query -> 0
        like(row.iataCode, "$query%") -> 1
        like(row.name, "$query%") -> 2
        else -> 3
    }
    val ordered = rows.filter { row ->
        (hasPremium || !row.isPremiumOnly) && listOf(row.iataCode, row.icaoCode, row.name, row.city)
            .any { like(it, "%$query%") }
    }.sortedWith(compareBy<AirportEntity> { rank(it) }.thenByDescending { it.popularity })
    return if (limit < 0) ordered else ordered.take(limit)
}
