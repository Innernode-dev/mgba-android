package com.retro.mgba.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.retro.mgba.data.db.GameEntity
import com.retro.mgba.scanner.SafPermissionHelper
import com.retro.mgba.ui.theme.OledBackground

/**
 * Master DuckStation-Style Library Screen.
 *
 * Implements:
 * 1. Dark OLED Material 3 theme (#0C0C0E background).
 * 2. Responsive LazyVerticalGrid with GridCells.Adaptive(minSize = 140.dp).
 * 3. Top bar with search, quick clear, sort dropdown, and Add Folder launcher.
 * 4. Filter chips row (All, GBA, GB, GBC, Favorites).
 * 5. Resume Last Played Hero Banner.
 * 6. 3:4 Box art grid card with authentic cartridge fallback.
 * 7. Long-press context menu and game details dialog.
 * 8. Scanning feedback and empty states.
 * 9. D-Pad & gamepad touchless navigation.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onFilterSelected: (GameFilter) -> Unit,
    onAddFolder: (Uri) -> Unit,
    onRescan: () -> Unit,
    onSettingsClick: () -> Unit,
    onLaunchGame: (GameEntity) -> Unit,
    onToggleFavorite: (GameEntity) -> Unit,
    onAssignCustomCover: (GameEntity, Uri) -> Unit,
    onRemoveCustomCover: (GameEntity) -> Unit,
    onDeleteGame: (GameEntity) -> Unit,
    onShowDetails: (GameEntity) -> Unit,
    onDismissDetails: () -> Unit,
    onShowContextMenu: (GameEntity) -> Unit,
    onDismissContextMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingCoverGameUri by rememberSaveable { mutableStateOf<String?>(null) }

    // SAF Directory Picker Launcher for adding ROM directories
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            SafPermissionHelper.takePersistablePermissions(context, treeUri)
            onAddFolder(treeUri)
        }
    }

    // Photo Gallery Image Picker Launcher for custom box art assignment
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { imageUri: Uri? ->
        val targetUri = pendingCoverGameUri
        if (imageUri != null && targetUri != null) {
            val targetGame = state.filteredGames.find { it.uri == targetUri }
                ?: GameEntity(uri = targetUri, title = "", system = "", fileSize = 0L)
            onAssignCustomCover(targetGame, imageUri)
        }
        pendingCoverGameUri = null
    }

    Scaffold(
        topBar = {
            LibraryTopBar(
                searchQuery = state.searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onClearSearch = onClearSearch,
                currentSort = state.sortOrder,
                onSortChange = onSortChange,
                onAddFolderClick = { folderPickerLauncher.launch(null) },
                onRescanClick = onRescan,
                onSettingsClick = onSettingsClick
            )
        },
        containerColor = OledBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(OledBackground)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 115.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Header Span Item 1: Filter Chips Row
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FilterChipsRow(
                        selectedFilter = state.selectedFilter,
                        onFilterSelected = onFilterSelected,
                        countsByFilter = state.systemCounts
                    )
                }

                // Header Span Item 2: Active Scanning Feedback Pill
                if (state.isScanning) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ScanFeedbackBar(scanProgress = state.scanProgress)
                    }
                }


                // Empty State Span Item
                if (state.filteredGames.isEmpty() && !state.isScanning) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyLibraryState(
                            onSelectFolderClick = { folderPickerLauncher.launch(null) }
                        )
                    }
                }

                // Responsive Game Grid Cards
                items(
                    items = state.filteredGames,
                    key = { it.uri }
                ) { game ->
                    Box {
                        GameGridCard(
                            game = game,
                            onClick = { onLaunchGame(game) },
                            onLongClick = { onShowContextMenu(game) },
                            onToggleFavorite = { onToggleFavorite(game) }
                        )

                        // Long-Press Context Menu anchored to Card
                        if (state.activeGameContextMenu?.uri == game.uri) {
                            GameContextMenu(
                                game = game,
                                expanded = true,
                                onDismiss = onDismissContextMenu,
                                onPlay = { onLaunchGame(game) },
                                onToggleFavorite = { onToggleFavorite(game) },
                                onAssignCover = {
                                    pendingCoverGameUri = game.uri
                                    imagePickerLauncher.launch("image/*")
                                },
                                onRemoveCover = { onRemoveCustomCover(game) },
                                onOpenDetails = { onShowDetails(game) },
                                onRemove = { onDeleteGame(game) }
                            )
                        }
                    }
                }
            }

            // Modal Game Details Dialog
            if (state.selectedGameForDetails != null) {
                GameDetailsDialog(
                    game = state.selectedGameForDetails,
                    onDismiss = onDismissDetails
                )
            }
        }
    }
}
