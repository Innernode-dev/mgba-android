package com.retro.mgba.ui.library

import com.retro.mgba.data.db.FolderEntity
import com.retro.mgba.data.db.GameEntity
import com.retro.mgba.scanner.ScanProgress

typealias GameUiModel = GameEntity

/**
 * Platform and category filter options for the library cover grid.
 */
enum class GameFilter(val labelResId: Int) {
    ALL(com.retro.mgba.R.string.tab_all),
    GBA(com.retro.mgba.R.string.tab_gba),
    GB(com.retro.mgba.R.string.tab_gb),
    GBC(com.retro.mgba.R.string.tab_gbc),
    FAVORITES(com.retro.mgba.R.string.tab_favorites);

    val displayName: String get() = name
    val label: String get() = name
}

/**
 * Sort options for game presentation in DuckStation grid.
 */
enum class SortOrder(val labelResId: Int) {
    TITLE_ASC(com.retro.mgba.R.string.sort_title_asc),
    TITLE_DESC(com.retro.mgba.R.string.sort_title_desc),
    RECENT_DESC(com.retro.mgba.R.string.sort_recent),
    SIZE_DESC(com.retro.mgba.R.string.sort_size_desc),
    SIZE_ASC(com.retro.mgba.R.string.sort_size_asc);

    val displayName: String get() = name
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
