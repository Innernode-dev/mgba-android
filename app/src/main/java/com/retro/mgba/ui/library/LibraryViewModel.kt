package com.retro.mgba.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.retro.mgba.data.db.FolderEntity
import com.retro.mgba.data.db.GameEntity
import com.retro.mgba.data.repository.FolderRepository
import com.retro.mgba.data.repository.GameRepository
import com.retro.mgba.scanner.FastRomScanner
import com.retro.mgba.scanner.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Jetpack Compose ViewModel driving DuckStation Library UI.
 * Combines Room DB changes, filters, sort orders, and search queries into a reactive StateFlow.
 */
class LibraryViewModel(
    private val gameRepository: GameRepository,
    private val folderRepository: FolderRepository,
    private val fastRomScanner: FastRomScanner? = null
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow(GameFilter.ALL)
    private val _sortOrder = MutableStateFlow(SortOrder.TITLE_ASC)
    private val _searchQuery = MutableStateFlow("")
    private val _isScanning = MutableStateFlow(false)
    private val _scanProgress = MutableStateFlow<ScanProgress>(ScanProgress.Idle)
    private val _detailsGame = MutableStateFlow<GameEntity?>(null)
    private val _activeContextMenuGame = MutableStateFlow<GameEntity?>(null)

    /**
     * Unified reactive UI State flow.
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        combine(
            gameRepository.allGames,
            gameRepository.resumeGame,
            gameRepository.recentGames,
            folderRepository.folders
        ) { allGames, resumeGame, recentGames, folders ->
            Quad(allGames, resumeGame, recentGames, folders)
        },
        combine(
            _selectedFilter,
            _sortOrder,
            _searchQuery,
            _isScanning,
            _scanProgress
        ) { filter, sortOrder, query, isScanning, scanProgress ->
            Quint(filter, sortOrder, query, isScanning, scanProgress)
        },
        combine(
            _detailsGame,
            _activeContextMenuGame
        ) { detailsGame, menuGame ->
            Pair(detailsGame, menuGame)
        }
    ) { quad, quint, pair ->
        val allGames = quad.first
        val resumeGame = quad.second
        val recentGames = quad.third
        val folders = quad.fourth

        val filter = quint.first
        val sortOrder = quint.second
        val query = quint.third
        val isScanning = quint.fourth
        val scanProgress = quint.fifth

        val detailsGame = pair.first
        val menuGame = pair.second

        // Compute system badge counts across the whole library
        val counts = mutableMapOf<GameFilter, Int>()
        counts[GameFilter.ALL] = allGames.size
        counts[GameFilter.GBA] = allGames.count { it.isGba }
        counts[GameFilter.GB] = allGames.count { it.isGb }
        counts[GameFilter.GBC] = allGames.count { it.isGbc }
        counts[GameFilter.FAVORITES] = allGames.count { it.isFavorite }

        // Resolve effective active platform filters
        val platformFiltersWithGames = listOf(GameFilter.GBA, GameFilter.GB, GameFilter.GBC).filter {
            (counts[it] ?: 0) > 0
        }

        val effectiveFilter = when {
            // When library is empty
            platformFiltersWithGames.isEmpty() -> GameFilter.ALL

            // Exactly one platform with games: hide "ALL", force the active platform
            platformFiltersWithGames.size == 1 -> {
                if (filter == GameFilter.FAVORITES && (counts[GameFilter.FAVORITES] ?: 0) > 0) {
                    GameFilter.FAVORITES
                } else {
                    platformFiltersWithGames.first()
                }
            }

            // Two or more platforms with games: "ALL" is available
            else -> {
                if (filter == GameFilter.ALL) {
                    GameFilter.ALL
                } else if (filter == GameFilter.FAVORITES && (counts[GameFilter.FAVORITES] ?: 0) > 0) {
                    GameFilter.FAVORITES
                } else if (filter in platformFiltersWithGames) {
                    filter
                } else {
                    GameFilter.ALL
                }
            }
        }

        // Filter by platform / favorites
        val filteredBySystem = when (effectiveFilter) {
            GameFilter.ALL -> allGames
            GameFilter.GBA -> allGames.filter { it.isGba }
            GameFilter.GB -> allGames.filter { it.isGb }
            GameFilter.GBC -> allGames.filter { it.isGbc }
            GameFilter.FAVORITES -> allGames.filter { it.isFavorite }
        }

        // Filter by search query
        val filteredByQuery = if (query.isNotBlank()) {
            val q = query.trim()
            filteredBySystem.filter { game ->
                game.title.contains(q, ignoreCase = true) ||
                        (game.gameCode != null && game.gameCode.contains(q, ignoreCase = true)) ||
                        game.region.contains(q, ignoreCase = true)
            }
        } else {
            filteredBySystem
        }

        // Apply sort order
        val sortedGames = when (sortOrder) {
            SortOrder.TITLE_ASC -> filteredByQuery.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            SortOrder.TITLE_DESC -> filteredByQuery.sortedWith(
                compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            SortOrder.RECENT_DESC -> filteredByQuery.sortedWith(
                compareByDescending<GameEntity> { it.lastPlayed }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            SortOrder.SIZE_DESC -> filteredByQuery.sortedWith(
                compareByDescending<GameEntity> { it.fileSize }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            SortOrder.SIZE_ASC -> filteredByQuery.sortedWith(
                compareBy<GameEntity> { it.fileSize }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
        }

        LibraryUiState(
            games = allGames,
            filteredGames = sortedGames,
            resumeGame = resumeGame,
            recentGames = recentGames,
            selectedFilter = effectiveFilter,
            sortOrder = sortOrder,
            searchQuery = query,
            isScanning = isScanning,
            scanProgress = scanProgress,
            folders = folders,
            systemCounts = counts,
            isInitialLoading = false,
            selectedGameForDetails = detailsGame,
            activeGameContextMenu = menuGame
        )
    }.flowOn(Dispatchers.Default)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    // --- User Intent Handlers ---

    fun onFilterSelected(filter: GameFilter) {
        _selectedFilter.value = filter
    }

    fun onSortOrderSelected(order: SortOrder) {
        _sortOrder.value = order
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onClearSearch() {
        _searchQuery.value = ""
    }

    fun onToggleFavorite(game: GameEntity) {
        viewModelScope.launch {
            gameRepository.toggleFavorite(game.uri)
        }
    }

    fun onCustomCoverAssigned(gameUri: String, coverUri: Uri) {
        viewModelScope.launch {
            gameRepository.setCustomCover(gameUri, coverUri.toString())
        }
    }

    fun onRemoveCustomCover(gameUri: String) {
        viewModelScope.launch {
            gameRepository.setCustomCover(gameUri, null)
        }
    }

    fun onGameLaunched(game: GameEntity) {
        viewModelScope.launch {
            gameRepository.updatePlayHistory(game.uri, sessionSeconds = 0L)
        }
    }

    fun onDeleteGame(game: GameEntity) {
        viewModelScope.launch {
            gameRepository.deleteGame(game.uri)
            if (_activeContextMenuGame.value?.uri == game.uri) {
                _activeContextMenuGame.value = null
            }
        }
    }

    fun onShowDetails(game: GameEntity) {
        _detailsGame.value = game
    }

    fun onDismissDetails() {
        _detailsGame.value = null
    }

    fun onShowContextMenu(game: GameEntity) {
        _activeContextMenuGame.value = game
    }

    fun onDismissContextMenu() {
        _activeContextMenuGame.value = null
    }

    fun addFolder(treeUri: Uri, displayName: String = treeUri.lastPathSegment ?: "ROM Directory") {
        viewModelScope.launch {
            folderRepository.addFolder(treeUri.toString(), displayName)
            startScan(treeUri)
        }
    }

    fun removeFolder(folder: FolderEntity) {
        viewModelScope.launch {
            folderRepository.removeFolder(folder.treeUri)
        }
    }

    fun rescanAllFolders() {
        viewModelScope.launch {
            val list = folderRepository.getFolderList()
            for (folder in list) {
                startScan(Uri.parse(folder.treeUri))
            }
        }
    }

    private fun startScan(treeUri: Uri) {
        val scanner = fastRomScanner ?: return
        viewModelScope.launch {
            _isScanning.value = true
            scanner.scanFolder(treeUri).collect { progress ->
                _scanProgress.value = progress
                when (progress) {
                    is ScanProgress.Completed, is ScanProgress.Failed -> {
                        _isScanning.value = false
                    }
                    else -> {}
                }
            }
        }
    }

    fun setScanProgress(progress: ScanProgress) {
        _scanProgress.value = progress
        _isScanning.value = progress is ScanProgress.Scanning || progress is ScanProgress.BatchDiscovered
    }

    // Helper data holders for combine
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
}

/**
 * Standard ViewModelProvider.Factory for instantiating LibraryViewModel.
 */
class LibraryViewModelFactory(
    private val gameRepository: GameRepository,
    private val folderRepository: FolderRepository,
    private val fastRomScanner: FastRomScanner? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            return LibraryViewModel(gameRepository, folderRepository, fastRomScanner) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
