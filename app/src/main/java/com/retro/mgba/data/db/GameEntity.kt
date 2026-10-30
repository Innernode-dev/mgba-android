package com.retro.mgba.data.db

import androidx.compose.ui.graphics.Color
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

/**
 * Room entity representing a scanned ROM game item in the library.
 *
 * Indexed on [system], [isFavorite], [lastPlayed], and [title] to guarantee
 * sub-millisecond query performance for DuckStation cover grids and hero cards.
 */
@Entity(
    tableName = "games",
    indices = [
        Index(value = ["system"]),
        Index(value = ["isFavorite"]),
        Index(value = ["lastPlayed"]),
        Index(value = ["title"])
    ]
)
data class GameEntity(
    /**
     * Unique identifier for the ROM: SAF Document URI string or absolute file path.
     */
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,

    /**
     * Cleaned display title of the game (e.g. "The Legend of Zelda - The Minish Cap").
     */
    @ColumnInfo(name = "title")
    val title: String,

    /**
     * Platform/console system identifier: "GBA", "GB", or "GBC".
     */
    @ColumnInfo(name = "system")
    val system: String,

    /**
     * 4-character internal GBA game code or serial from ROM header (e.g. "BZMP", "AGBE"), or null for GB.
     */
    @ColumnInfo(name = "gameCode")
    val gameCode: String? = null,

    /**
     * ROM file size in bytes.
     */
    @ColumnInfo(name = "fileSize")
    val fileSize: Long = 0L,

    /**
     * Epoch timestamp in milliseconds of when the game was last launched (0L if never played).
     */
    @ColumnInfo(name = "lastPlayed")
    val lastPlayed: Long = 0L,

    /**
     * Accumulated total playtime in seconds across all sessions.
     */
    @ColumnInfo(name = "playTimeSeconds")
    val playTimeSeconds: Long = 0L,

    /**
     * Favorite toggle flag.
     */
    @ColumnInfo(name = "isFavorite")
    val isFavorite: Boolean = false,

    /**
     * Optional custom box art URI/path assigned manually from device gallery.
     */
    @ColumnInfo(name = "customCoverUri")
    val customCoverUri: String? = null,

    /**
     * Explicit path or filename to persistent battery save file (.sav).
     */
    @ColumnInfo(name = "sramPath")
    val sramPath: String? = null,

    /**
     * Timestamp when game was first scanned and added to the library.
     */
    @ColumnInfo(name = "dateAdded")
    val dateAdded: Long = System.currentTimeMillis(),

    /**
     * Inferred geographic region (e.g. USA, EUR, JPN, WLD).
     */
    @ColumnInfo(name = "region")
    val region: String = "USA"
) {
    /**
     * Helper returning true if the platform is Game Boy Advance.
     */
    val isGba: Boolean
        get() = system.equals("GBA", ignoreCase = true)

    /**
     * Helper returning true if the platform is original Game Boy.
     */
    val isGb: Boolean
        get() = system.equals("GB", ignoreCase = true)

    /**
     * Helper returning true if the platform is Game Boy Color.
     */
    val isGbc: Boolean
        get() = system.equals("GBC", ignoreCase = true)

    /**
     * Returns formatted human-readable file size (e.g. "16.0 MB", "512 KB").
     */
    val formattedFileSize: String
        get() {
            if (fileSize <= 0) return "0 B"
            val kb = fileSize / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
                else -> "$fileSize B"
            }
        }

    /**
     * Returns formatted human-readable total playtime (e.g. "2h 15m", "45m", "Never played").
     */
    val formattedPlayTime: String
        get() {
            if (playTimeSeconds <= 0L) return "Never played"
            val hours = playTimeSeconds / 3600
            val minutes = (playTimeSeconds % 3600) / 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m"
                else -> "< 1m"
            }
        }

    /**
     * Returns relative time description for last played session.
     */
    val formattedLastPlayed: String
        get() {
            if (lastPlayed <= 0L) return "Never played"
            val now = System.currentTimeMillis()
            val diffMs = now - lastPlayed
            val minutes = diffMs / (1000 * 60)
            val hours = minutes / 60
            val days = hours / 24
            return when {
                minutes < 1 -> "Just now"
                minutes < 60 -> "${minutes}m ago"
                hours < 24 -> "${hours}h ago"
                days == 1L -> "Yesterday"
                days < 30 -> "${days}d ago"
                else -> "Long ago"
            }
        }


    /**
     * Accent badge color for platform identification.
     */
    val badgeColor: Color
        get() = when {
            isGba -> Color(0xFF8B5CF6) // GBA Purple
            isGb -> Color(0xFF84CC16)  // GB Green
            isGbc -> Color(0xFF06B6D4) // GBC Cyan
            else -> Color(0xFF8B5CF6)
        }
}
