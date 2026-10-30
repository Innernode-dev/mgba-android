package com.retro.mgba

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.retro.mgba.data.db.AppDatabase
import com.retro.mgba.data.repository.FolderRepository
import com.retro.mgba.data.repository.GameRepository
import com.retro.mgba.scanner.FastRomScanner
import com.retro.mgba.ui.library.LibraryScreen
import com.retro.mgba.ui.library.LibraryViewModel
import com.retro.mgba.ui.library.LibraryViewModelFactory
import com.retro.mgba.ui.theme.RetroMgbaTheme

/**
 * Main Activity hosting the DuckStation-style Jetpack Compose UI.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: LibraryViewModel by viewModels {
        val db = AppDatabase.getInstance(applicationContext)
        val gameRepo = GameRepository(db.gameDao())
        val folderRepo = FolderRepository(db.folderDao())
        val scanner = FastRomScanner(applicationContext, db.gameDao())
        LibraryViewModelFactory(gameRepo, folderRepo, scanner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure public /mGBA directories (saves, states, screenshots) exist on storage
        com.retro.mgba.data.storage.StorageHelper.ensureDirectories(applicationContext)

        setContent {
            RetroMgbaTheme {
                val state by viewModel.uiState.collectAsState()

                LibraryScreen(
                    state = state,
                    onSearchQueryChange = { viewModel.onSearchQueryChanged(it) },
                    onClearSearch = { viewModel.onClearSearch() },
                    onSortChange = { viewModel.onSortOrderSelected(it) },
                    onFilterSelected = { viewModel.onFilterSelected(it) },
                    onAddFolder = { uri -> viewModel.addFolder(uri) },
                    onRescan = { viewModel.rescanAllFolders() },
                    onSettingsClick = {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    },
                    onLaunchGame = { game ->
                        viewModel.onGameLaunched(game)
                        val intent = Intent(this@MainActivity, EmulationActivity::class.java).apply {
                            data = Uri.parse(game.uri)
                            putExtra("EXTRA_GAME_ID", game.uri)
                            putExtra("EXTRA_GAME_TITLE", game.title)
                            putExtra("EXTRA_GAME_SYSTEM", game.system)
                        }
                        startActivity(intent)
                    },
                    onToggleFavorite = { viewModel.onToggleFavorite(it) },
                    onAssignCustomCover = { game, coverUri ->
                        val persistentUri = com.retro.mgba.data.storage.StorageHelper.saveCustomCover(applicationContext, game.uri, coverUri)
                        if (persistentUri != null) {
                            viewModel.onCustomCoverAssigned(game.uri, Uri.parse(persistentUri))
                            android.widget.Toast.makeText(this@MainActivity, "Обложка сохранена", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, "Не удалось загрузить изображение", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRemoveCustomCover = { game ->
                        com.retro.mgba.data.storage.StorageHelper.deleteCustomCover(applicationContext, game.uri)
                        viewModel.onRemoveCustomCover(game.uri)
                        android.widget.Toast.makeText(this@MainActivity, "Обложка сброшена", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onDeleteGame = { viewModel.onDeleteGame(it) },
                    onShowDetails = { viewModel.onShowDetails(it) },
                    onDismissDetails = { viewModel.onDismissDetails() },
                    onShowContextMenu = { viewModel.onShowContextMenu(it) },
                    onDismissContextMenu = { viewModel.onDismissContextMenu() }
                )
            }
        }
    }
}
