package com.retro.mgba.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.retro.mgba.data.db.GameDao
import com.retro.mgba.data.db.GameEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.ArrayDeque

/**
 * High-Performance SAF Recursive Scanner.
 *
 * Implements high-speed recursive traversal of Android Storage Access Framework directory trees
 * using direct DocumentsContract cursor queries, explicit BFS work deque, and minimal Binder IPC projection.
 *
 * Key Architectural Features:
 * - Direct DocumentsContract.buildChildDocumentsUriUsingTree traversal without DocumentFile overhead.
 * - Strict 5-column projection: [COLUMN_DOCUMENT_ID, COLUMN_DISPLAY_NAME, COLUMN_MIME_TYPE, COLUMN_SIZE, COLUMN_LAST_MODIFIED].
 * - .nomedia file detection: automatically skips private or excluded directories.
 * - Hidden directory pruning: ignores directories starting with '.'.
 * - Cyclic symlink & depth guard: limits recursion depth to 15 levels and tracks visited document IDs.
 * - Batch insertion: emits and persists ROM entities in chunks of 50 to maintain smooth 60 FPS UI.
 * - SecurityException resilience: catches permission denials on corrupted/revoked subfolders without halting entire scan.
 * - Non-blocking execution on Dispatchers.IO with full cooperative cancellation support.
 */
class FastRomScanner(
    private val context: Context,
    private val gameDao: GameDao? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "FastRomScanner"

        /** Maximum directory nesting depth to prevent infinite circular symlink loops */
        const val MAX_DIRECTORY_DEPTH = 15

        /** Chunk size for Room DB batch insertion and UI emission */
        const val BATCH_CHUNK_SIZE = 50

        /**
         * Required minimal projection columns.
         * Minimizing projection columns ensures IPC transactions stay far below Android's 2MB CursorWindow limit.
         */
        val CURSOR_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    /**
     * Internal BFS work queue item.
     */
    private data class DirectoryTask(
        val documentId: String,
        val depth: Int,
        val displayName: String
    )

    /**
     * Intermediate representation of a queried document row.
     */
    private data class CursorDocEntry(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean
    )

    /**
     * Recursively scans a user-selected SAF folder tree for .gba, .gb, and .gbc ROMs.
     *
     * @param treeUri SAF Tree URI returned by OpenDocumentTree.
     * @param inspectHeaders Optional flag to force binary header inspection for game codes.
     * @return Cold Flow emitting ScanProgress updates and batch chunks.
     */
    fun scanFolder(treeUri: Uri, inspectHeaders: Boolean = false): Flow<ScanProgress> = flow {
        val startTime = System.currentTimeMillis()
        val folderDisplayName = SafPermissionHelper.getFolderDisplayName(context, treeUri)
        emit(ScanProgress.Started(treeUri.toString(), folderDisplayName))

        val contentResolver: ContentResolver = context.contentResolver
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve root document ID for tree: $treeUri", e)
            emit(ScanProgress.Failed(e, "Invalid storage tree URI: ${e.message}"))
            return@flow
        }

        val queue = ArrayDeque<DirectoryTask>()
        val visitedDocIds = HashSet<String>()
        val batchBuffer = ArrayList<GameEntity>(BATCH_CHUNK_SIZE)

        var totalDiscovered = 0
        var totalDirectoriesScanned = 0

        // Enqueue root directory task
        queue.add(DirectoryTask(documentId = rootDocId, depth = 0, displayName = folderDisplayName))
        visitedDocIds.add(rootDocId)

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()

            val currentTask = queue.removeFirst()
            totalDirectoriesScanned++

            emit(
                ScanProgress.Scanning(
                    currentDirectory = currentTask.displayName,
                    totalDiscovered = totalDiscovered,
                    directoriesScanned = totalDirectoriesScanned
                )
            )

            // Query child documents of current directory
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentTask.documentId)
            val childEntries = queryChildren(contentResolver, childrenUri) ?: continue

            // 1. Check for .nomedia exclusion
            val hasNoMedia = childEntries.any { it.displayName.equals(".nomedia", ignoreCase = true) }
            if (hasNoMedia) {
                Log.d(TAG, "Skipping directory with .nomedia: ${currentTask.displayName}")
                continue
            }

            // 2. Process child entries
            for (child in childEntries) {
                currentCoroutineContext().ensureActive()

                if (child.isDirectory) {
                    // Check depth limit and hidden folder flag
                    if (currentTask.depth + 1 < MAX_DIRECTORY_DEPTH) {
                        if (!child.displayName.startsWith(".")) {
                            if (visitedDocIds.add(child.documentId)) {
                                queue.add(
                                    DirectoryTask(
                                        documentId = child.documentId,
                                        depth = currentTask.depth + 1,
                                        displayName = child.displayName
                                    )
                                )
                            }
                        }
                    } else {
                        Log.w(TAG, "Depth limit reached ($MAX_DIRECTORY_DEPTH), pruning: ${child.displayName}")
                    }
                } else {
                    // Regular file: verify ROM extension
                    val system = RomSystem.fromExtension(child.displayName) ?: continue

                    // Build concrete Document URI for this ROM
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
                    val rawFilename = child.displayName
                    val cleanTitle = RomFilenameCleaner.cleanTitle(rawFilename)
                    val extractedRegion = RomFilenameCleaner.extractRegion(rawFilename) ?: "USA"

                    // Optional header inspection fallback
                    var gameCode: String? = null
                    if (inspectHeaders || cleanTitle == "Unknown Game") {
                        val headerInfo = RomHeaderParser.parseHeader(context, documentUri, system)
                        if (headerInfo != null) {
                            gameCode = headerInfo.gameCode
                        }
                    }

                    val entity = GameEntity(
                        uri = documentUri.toString(),
                        title = cleanTitle,
                        system = system.id,
                        gameCode = gameCode,
                        fileSize = child.size,
                        lastPlayed = 0L,
                        playTimeSeconds = 0L,
                        isFavorite = false,
                        customCoverUri = null,
                        sramPath = null,
                        dateAdded = if (child.lastModified > 0) child.lastModified else System.currentTimeMillis(),
                        region = extractedRegion
                    )

                    batchBuffer.add(entity)
                    totalDiscovered++

                    // Flush batch when threshold is reached
                    if (batchBuffer.size >= BATCH_CHUNK_SIZE) {
                        val batchToInsert = ArrayList(batchBuffer)
                        batchBuffer.clear()

                        // Batch insert into Room DB if DAO is provided
                        gameDao?.insertAll(batchToInsert)

                        emit(
                            ScanProgress.BatchDiscovered(
                                batch = batchToInsert,
                                totalDiscovered = totalDiscovered
                            )
                        )
                    }
                }
            }
        }

        // Flush any remaining buffered items
        if (batchBuffer.isNotEmpty()) {
            val remainingBatch = ArrayList(batchBuffer)
            batchBuffer.clear()

            gameDao?.insertAll(remainingBatch)

            emit(
                ScanProgress.BatchDiscovered(
                    batch = remainingBatch,
                    totalDiscovered = totalDiscovered
                )
            )
        }

        val durationMs = System.currentTimeMillis() - startTime
        Log.i(TAG, "Scan completed in ${durationMs}ms: $totalDiscovered ROMs discovered across $totalDirectoriesScanned directories")

        emit(
            ScanProgress.Completed(
                totalDiscovered = totalDiscovered,
                totalDirectoriesScanned = totalDirectoriesScanned,
                durationMs = durationMs
            )
        )
    }.flowOn(ioDispatcher)

    /**
     * Executes single ContentResolver query on child documents with exception safety.
     * Catches SecurityException on unreadable/revoked subfolders so scanning continues for siblings.
     */
    private fun queryChildren(contentResolver: ContentResolver, childrenUri: Uri): List<CursorDocEntry>? {
        return try {
            contentResolver.query(
                childrenUri,
                CURSOR_PROJECTION,
                null,
                null,
                null
            )?.use { cursor ->
                parseCursorRows(cursor)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException reading subfolder $childrenUri (access denied): ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading subfolder $childrenUri: ${e.message}")
            null
        }
    }

    /**
     * Converts raw Cursor rows into strongly-typed POJOs before the cursor is closed.
     */
    private fun parseCursorRows(cursor: Cursor): List<CursorDocEntry> {
        val results = ArrayList<CursorDocEntry>(cursor.count)

        val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val modIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

        while (cursor.moveToNext()) {
            val docId = if (idIndex != -1) cursor.getString(idIndex) else null
            val displayName = if (nameIndex != -1) cursor.getString(nameIndex) else null
            val mimeType = if (mimeIndex != -1) cursor.getString(mimeIndex) else ""
            val size = if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
            val lastModified = if (modIndex != -1 && !cursor.isNull(modIndex)) cursor.getLong(modIndex) else 0L

            if (!docId.isNullOrBlank() && !displayName.isNullOrBlank()) {
                val isDir = (mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
                results.add(
                    CursorDocEntry(
                        documentId = docId,
                        displayName = displayName,
                        mimeType = mimeType,
                        size = size,
                        lastModified = lastModified,
                        isDirectory = isDir
                    )
                )
            }
        }
        return results
    }
}
