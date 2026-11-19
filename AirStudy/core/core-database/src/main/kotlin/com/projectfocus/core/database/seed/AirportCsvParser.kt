package com.projectfocus.core.database.seed

import com.projectfocus.core.database.entity.AirportEntity
import java.io.Reader
































object AirportCsvParser {

    /** 与 `AirportEntity` 的 15 个字段一一对应，顺序即 CSV 列序 */
    val HEADER: List<String> = listOf(
        "id", "iataCode", "icaoCode", "name", "city", "country",
        "latitude", "longitude", "elevation", "continent", "type",
        "popularity", "isPremiumOnly", "nameZh", "cityZh",
    )

    /**
     * 解析整个资产。
     *
     * @param reader 由调用方负责关闭（通常包在 `use { }` 里）
     * @throws IllegalArgumentException 资产格式不符合约定时
     */
    fun parse(reader: Reader): Sequence<AirportEntity> = sequence {
        val tokenizer = CsvTokenizer(reader.readText())
        val header = tokenizer.nextRow() ?: error("airport asset is empty")
        require(header == HEADER) {
            "airport asset header mismatch: expected $HEADER, got $header"
        }
        while (true) {
            val fields = tokenizer.nextRow() ?: break
            if (fields.size == 1 && fields[0].isEmpty()) continue // 空行
            yield(toEntity(fields, tokenizer.line))
        }
    }

    private fun toEntity(fields: List<String>, line: Int): AirportEntity {
        fun fail(reason: String): Nothing =
            throw IllegalArgumentException("airport asset line $line: $reason")

        if (fields.size != HEADER.size) {
            fail("expected ${HEADER.size} columns, got ${fields.size}")
        }

        val id = fields[0].toLongOrNull() ?: fail("id is not a number: '${fields[0]}'")
        val iata = fields[1]
        if (iata.length != 3 || !iata.all { it in 'A'..'Z' }) {
            fail("iataCode must be 3 uppercase letters, got '$iata'")
        }
        val latitude = fields[6].toDoubleOrNull() ?: fail("latitude is not a number")
        val longitude = fields[7].toDoubleOrNull() ?: fail("longitude is not a number")
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            fail("coordinate out of range: $latitude,$longitude")
        }
        val elevation = fields[8].toIntOrNull() ?: fail("elevation is not an integer")
        val popularity = fields[11].toIntOrNull() ?: fail("popularity is not an integer")
        val premium = when (fields[12]) {
            "0" -> false
            "1" -> true
            else -> fail("isPremiumOnly must be 0/1, got '${fields[12]}'")
        }

        return AirportEntity(
            id = id,
            iataCode = iata,
            icaoCode = fields[2],
            name = fields[3],
            city = fields[4],
            country = fields[5],
            latitude = latitude,
            longitude = longitude,
            elevation = elevation,
            continent = fields[9],
            type = fields[10],
            popularity = popularity,
            isPremiumOnly = premium,
            nameZh = fields[13].ifEmpty { null },
            cityZh = fields[14].ifEmpty { null },
        )
    }

    /**
     * 逐行切分。只保留「当前行」的字段，不缓存整个文件的行。
     *
     * 这个类故意不实现 `Iterator`：它需要维护行号，且只在
     * [parse] 的 sequence 内部单线程使用，接口越小越不容易被误用。
     */
    private class CsvTokenizer(private val text: String) {

        private var pos = 0

        /** 最近一行**结束**时的文本行号（1 起），用于报错定位 */
        var line: Int = 0
            private set

        fun nextRow(): List<String>? {
            if (pos == 0 && text.isNotEmpty() && text[0] == '\uFEFF') pos = 1 // UTF-8 BOM
            if (pos >= text.length) return null

            line += 1
            val fields = ArrayList<String>(AirportCsvParser.HEADER.size)
            val field = StringBuilder()
            var inQuotes = false
            var quoteClosed = false

            fun endField() {
                fields.add(field.toString())
                field.setLength(0)
                quoteClosed = false
            }

            while (pos < text.length) {
                val c = text[pos++]
                if (inQuotes) {
                    when (c) {
                        '"' -> {
                            if (pos < text.length && text[pos] == '"') {
                                field.append('"')
                                pos++
                            } else {
                                inQuotes = false
                                quoteClosed = true
                            }
                        }
                        else -> field.append(c)
                    }
                    continue
                }

                when (c) {
                    ',' -> endField()
                    '\n', '\r' -> {
                        if (c == '\r' && pos < text.length && text[pos] == '\n') pos++
                        endField()
                        return fields
                    }
                    '"' -> {
                        if (field.isNotEmpty() || quoteClosed) {
                            throw IllegalArgumentException(
                                "airport asset line $line: unexpected quote inside unquoted field",
                            )
                        }
                        inQuotes = true
                    }
                    else -> {
                        if (quoteClosed) {
                            throw IllegalArgumentException(
                                "airport asset line $line: unexpected character after closing quote",
                            )
                        }
                        field.append(c)
                    }
                }
            }

            if (inQuotes) {
                throw IllegalArgumentException("airport asset line $line: unterminated quoted field")
            }
            endField()
            return fields
        }
    }
}
