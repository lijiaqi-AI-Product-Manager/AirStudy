package com.projectfocus.app.booking

import java.util.Locale


internal object SourceFlightNumber {
    private val upPrefixes = arrayOf("FF", "FA", "FG", "FJ", "FQ", "FV", "FX", "FY")
    private val downPrefixes = arrayOf("CA", "CZ", "MU", "ZH", "HU", "SC", "FM", "MF", "KN", "GJ", "JD", "BK")
    private val whitespace = Regex("\\s+")

    fun generate(origin: String?, destination: String?, upMode: Boolean): String {
        val prefixes = if (upMode) upPrefixes else downPrefixes
        val seed = (if (upMode) "UP|" else "DOWN|") + normalize(origin) + ">" + normalize(destination)
        val unsignedHash = seed.hashCode().toLong() and 0xffff_ffffL
        val prefix = prefixes[(unsignedHash % prefixes.size).toInt()]
        val suffix = ((unsignedHash / prefixes.size) % 9_000L).toInt() + 1_000
        return prefix + suffix
    }

    private fun normalize(value: String?): String = value.orEmpty()
        .trim { it <= '\u0020' }
        .uppercase(Locale.ENGLISH)
        .replace(whitespace, "")
        .ifEmpty { "XX" }
}
