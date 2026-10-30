package com.retro.mgba.scanner

/**
 * Enumerates the retro gaming systems supported by the mGBA core.
 * Provides extension mapping, display metadata, and system identification.
 */
enum class RomSystem(
    val id: String,
    val displayName: String,
    val shortName: String,
    val extensions: Set<String>,
    val headerOffsetTitle: Int,
    val headerTitleLength: Int
) {
    GBA(
        id = "GBA",
        displayName = "Game Boy Advance",
        shortName = "GBA",
        extensions = setOf("gba"),
        headerOffsetTitle = 0x00A0,
        headerTitleLength = 12
    ),
    GB(
        id = "GB",
        displayName = "Game Boy",
        shortName = "GB",
        extensions = setOf("gb"),
        headerOffsetTitle = 0x0134,
        headerTitleLength = 16
    ),
    GBC(
        id = "GBC",
        displayName = "Game Boy Color",
        shortName = "GBC",
        extensions = setOf("gbc"),
        headerOffsetTitle = 0x0134,
        headerTitleLength = 16
    );

    companion object {
        private val EXTENSION_LOOKUP: Map<String, RomSystem> = buildMap {
            for (system in RomSystem.values()) {
                for (ext in system.extensions) {
                    put(ext.lowercase(), system)
                }
            }
        }

        /**
         * Resolves the RomSystem from a raw file extension or full filename.
         * Case-insensitive, strips leading dots.
         *
         * @param extensionOrFileName Either ".gba", "gba", or "Pokemon.GBA"
         * @return Matching RomSystem, or null if unsupported.
         */
        fun fromExtension(extensionOrFileName: String?): RomSystem? {
            if (extensionOrFileName.isNullOrBlank()) return null
            val ext = if (extensionOrFileName.contains('.')) {
                extensionOrFileName.substringAfterLast('.', "")
            } else {
                extensionOrFileName
            }.lowercase().trim()

            if (ext.isEmpty()) return null
            return EXTENSION_LOOKUP[ext]
        }

        /**
         * Checks whether the given filename has a supported ROM extension (.gba, .gb, .gbc).
         */
        fun isSupported(filename: String?): Boolean {
            return fromExtension(filename) != null
        }
    }
}
