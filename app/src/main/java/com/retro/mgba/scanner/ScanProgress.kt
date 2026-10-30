package com.retro.mgba.scanner

import com.retro.mgba.data.db.GameEntity

/**
 * Reactive state events emitted during recursive SAF ROM directory scanning.
 * Used by LibraryViewModel to drive Compose UI progress bars and snackbars.
 */
sealed class ScanProgress {

    /**
     * Scanner is idle and ready for a new scan request.
     */
    data object Idle : ScanProgress()

    /**
     * Scanner has started scanning a designated root storage tree.
     */
    data class Started(
        val rootUri: String,
        val folderDisplayName: String
    ) : ScanProgress()

    /**
     * Active traversal update emitted as directories are traversed.
     */
    data class Scanning(
        val currentDirectory: String,
        val totalDiscovered: Int,
        val directoriesScanned: Int = 0,
        val currentFile: String = ""
    ) : ScanProgress()

    /**
     * A batch chunk of discovered ROM entities (typically 50 items) is ready
     * and has been inserted into the Room database.
     */
    data class BatchDiscovered(
        val batch: List<GameEntity>,
        val totalDiscovered: Int
    ) : ScanProgress()

    /**
     * Directory traversal has completed successfully.
     */
    data class Completed(
        val totalDiscovered: Int,
        val totalDirectoriesScanned: Int = 0,
        val durationMs: Long = 0L,
        val newGamesAdded: Int = totalDiscovered
    ) : ScanProgress()

    /**
     * Traversal terminated due to an unrecoverable exception (e.g. revoked root permission).
     */
    data class Failed(
        val throwable: Throwable? = null,
        val message: String
    ) : ScanProgress()
}
