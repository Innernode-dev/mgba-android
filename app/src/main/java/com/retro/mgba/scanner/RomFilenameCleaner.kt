package com.retro.mgba.scanner

import java.util.regex.Pattern

/**
 * High-performance string utility for parsing and normalizing ROM filenames.
 * Strips GoodTools/No-Intro dump tags, release regions, revisions, and translation badges.
 */
object RomFilenameCleaner {

    // Matches standard bracketed dump tags: [!], [b1], [a], [o], [t], [h], [p], [f], [c], [T+Eng], etc.
    private val BRACKETED_TAG_PATTERN: Pattern = Pattern.compile(
        "\\[([!*+a-zA-Z0-9_\\-\\.\\s]+)\\]",
        Pattern.CASE_INSENSITIVE
    )

    // Matches standard parenthetical region and revision tags: (USA), (Europe), (Japan), (Rev 1), etc.
    private val PAREN_TAG_PATTERN: Pattern = Pattern.compile(
        "\\(([a-zA-Z0-9_,\\-\\.\\s]+)\\)"
    )

    // Matches consecutive whitespace characters
    private val MULTI_SPACE_PATTERN: Pattern = Pattern.compile("\\s{2,}")

    // Known region identifier tokens for metadata extraction
    private val REGION_KEYWORDS = mapOf(
        "USA" to "USA",
        "US" to "USA",
        "U" to "USA",
        "EUROPE" to "EUR",
        "EUR" to "EUR",
        "EU" to "EUR",
        "E" to "EUR",
        "JAPAN" to "JPN",
        "JAP" to "JPN",
        "J" to "JPN",
        "WORLD" to "WLD",
        "W" to "WLD",
        "USA, EUROPE" to "USA/EUR",
        "USA,EUR" to "USA/EUR",
        "EN,FR,DE,ES,IT" to "EUR",
        "EN,FR,DE" to "EUR",
        "EN,JA" to "WLD",
        "GERMANY" to "GER",
        "FRANCE" to "FRA",
        "SPAIN" to "SPA",
        "ITALY" to "ITA",
        "AUSTRALIA" to "AUS"
    )

    /**
     * Cleans a raw ROM filename into a human-readable game title.
     *
     * Example:
     * "Pokemon - Emerald Version (USA, Europe) [!].gba" -> "Pokemon - Emerald Version"
     * "Super_Mario_Advance_4_-_Super_Mario_Bros._3_(USA)_(Rev_1).gba" -> "Super Mario Advance 4 - Super Mario Bros. 3"
     * "Metroid_Fusion.gba" -> "Metroid Fusion"
     *
     * @param rawFilename The filename including or excluding extension.
     * @return Cleaned, trimmed display title.
     */
    fun cleanTitle(rawFilename: String): String {
        if (rawFilename.isBlank()) return "Unknown Game"

        // 1. Strip extension
        val stem = if (rawFilename.contains('.')) {
            rawFilename.substringBeforeLast('.')
        } else {
            rawFilename
        }

        if (stem.isBlank()) return "Unknown Game"

        // 2. Replace underscores with spaces
        var working = stem.replace('_', ' ')

        // 3. Strip bracketed dump tags like [!], [b1], [T+Eng]
        working = BRACKETED_TAG_PATTERN.matcher(working).replaceAll("")

        // 4. Strip parenthetical tags like (USA), (Rev 1), (Europe)
        working = PAREN_TAG_PATTERN.matcher(working).replaceAll("")

        // 5. Clean up redundant hyphens, trailing punctuation, and whitespace
        working = MULTI_SPACE_PATTERN.matcher(working).replaceAll(" ").trim()

        // Remove trailing hyphens or dangling punctuation
        working = working.trimEnd(' ', '-', ':', ',')

        // If stripping removed everything, fallback to raw stem with underscores replaced
        if (working.isBlank()) {
            val fallback = stem.replace('_', ' ').trim()
            return if (fallback.isNotBlank()) fallback else "Unknown Game"
        }

        return working
    }

    /**
     * Extracts geographic region string from filename parenthetical tags.
     *
     * @param rawFilename Filename like "Castlevania - Aria of Sorrow (USA).gba"
     * @return Resolved standard region code (e.g. "USA", "EUR", "JPN", "WLD"), or null.
     */
    fun extractRegion(rawFilename: String): String? {
        val matcher = PAREN_TAG_PATTERN.matcher(rawFilename)
        while (matcher.find()) {
            val content = matcher.group(1)?.trim() ?: continue
            val upper = content.uppercase()
            // Direct keyword match
            REGION_KEYWORDS[upper]?.let { return it }

            // Partial token inspection
            val tokens = upper.split(',', '/', ' ').map { it.trim() }
            for (token in tokens) {
                REGION_KEYWORDS[token]?.let { return it }
            }
        }
        return null
    }

    /**
     * Extracts version or revision tag from filename (e.g. "Rev 1", "v1.1").
     */
    fun extractRevision(rawFilename: String): String? {
        val matcher = PAREN_TAG_PATTERN.matcher(rawFilename)
        while (matcher.find()) {
            val content = matcher.group(1)?.trim() ?: continue
            val lower = content.lowercase()
            if (lower.startsWith("rev") || lower.startsWith("v1.") || lower.startsWith("v2.")) {
                return content
            }
        }
        return null
    }
}
