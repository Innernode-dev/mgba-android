package com.retro.mgba.ui.library

import com.retro.mgba.data.db.FolderEntity
import com.retro.mgba.data.db.GameEntity
import com.retro.mgba.scanner.ScanProgress

typealias GameUiModel = GameEntity

/**
 * Platform and category filter options for the library cover grid.
 */
enum class GameFilter(val displayName: String, val label: String = displayName) {
    ALL("Все"),
    GBA("GBA"),
    GB("GB"),
    GBC("GBC"),
    FAVORITES("Избранное")
}

/**
 * Sort options for game presentation in DuckStation grid.
 */
enum class SortOrder(val displayName: String) {
    TITLE_ASC("По названию (А-Я)"),
    TITLE_DESC("По названию (Я-А)"),
    RECENT_DESC("Недавно запущенные"),
    SIZE_DESC("По размеру (убывание)"),
    SIZE_ASC("По размеру (возрастание)")
}

typealias SortOption = SortOrder

/**
 * Complete immutable reactive UI state for DuckStation library screen.
 */
data class LibraryUiState(
    val games: List<GameEntity> = emptyList(),
    val filteredGames: List<GameEntity> = emptyList(),
    val resumeGame: GameEntity? = null,
    val recentGames: List<GameEntity> = emptyList(),
    val selectedFilter: GameFilter = GameFilter.ALL,
    val sortOrder: SortOrder = SortOrder.TITLE_ASC,
    val searchQuery: String = "",
    val isScanning: Boolean = false,
    val scanProgress: ScanProgress = ScanProgress.Idle,
    val folders: List<FolderEntity> = emptyList(),
    val systemCounts: Map<GameFilter, Int> = emptyMap(),
    val isInitialLoading: Boolean = true,
    val selectedGameForDetails: GameEntity? = null,
    val activeGameContextMenu: GameEntity? = null
)
