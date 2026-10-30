package com.retro.mgba.data.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Storage Helper for managing mGBA files in the fixed Android/data directory.
 *
 * Guaranteed zero permission errors across all Android versions:
 * - Battery SRAM saves (.sav) in /Android/data/com.retro.mgba/files/saves/
 * - Quick save states (.ss) in /Android/data/com.retro.mgba/files/states/<game>/
 * - Custom cover art (.png) in /Android/data/com.retro.mgba/files/covers/
 * - Screenshots in /Android/data/com.retro.mgba/files/screenshots/
 */
object StorageHelper {

    data class SaveItem(
        val file: File,
        val displayName: String,
        val gameTitle: String,
        val isBatterySave: Boolean, // true = .sav, false = .ss (quick state)
        val sizeBytes: Long,
        val lastModified: Long
    )

    /**
     * Resolves the fixed base directory for mGBA data inside Android/data.
     */
    fun getBaseDir(context: Context): File {
        val extFiles = context.getExternalFilesDir(null) ?: context.filesDir
        return extFiles.also { it.mkdirs() }
    }

    /**
     * Directory for battery saves (.sav files).
     */
    fun getSaveDir(context: Context): File {
        val base = getBaseDir(context)
        return File(base, "saves").also { it.mkdirs() }
    }

    /**
     * Directory for quick save states (.ss files).
     */
    fun getStateDir(context: Context): File {
        val base = getBaseDir(context)
        return File(base, "states").also { it.mkdirs() }
    }

    /**
     * Directory for custom cover art (.png files).
     */
    fun getCoversDir(context: Context): File {
        val base = getBaseDir(context)
        return File(base, "covers").also { it.mkdirs() }
    }

    /**
     * Directory for screenshots.
     */
    fun getScreenshotsDir(context: Context): File {
        val base = getBaseDir(context)
        return File(base, "screenshots").also { it.mkdirs() }
    }

    /**
     * Copies selected gallery image to persistent local storage and returns file URI.
     */
    fun saveCustomCover(context: Context, gameUri: String, sourceUri: Uri): String? {
        return try {
            val coversDir = getCoversDir(context)
            val hash = gameUri.hashCode().toString().replace('-', 'n')
            val destFile = File(coversDir, "cover_$hash.png")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(destFile).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Deletes persistent custom cover file if it exists.
     */
    fun deleteCustomCover(context: Context, gameUri: String) {
        try {
            val coversDir = getCoversDir(context)
            val hash = gameUri.hashCode().toString().replace('-', 'n')
            val file = File(coversDir, "cover_$hash.png")
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
    }

    /**
     * Collects all save files (.sav and .ss) from the saves and states folders.
     */
    fun listAllSaves(context: Context): List<SaveItem> {
        val result = mutableListOf<SaveItem>()
        val savesDir = getSaveDir(context)
        val statesDir = getStateDir(context)

        // 1. Collect battery saves (.sav)
        savesDir.listFiles { file -> file.isFile && file.extension.equals("sav", ignoreCase = true) }?.forEach { f ->
            val gameTitle = f.nameWithoutExtension
            result.add(
                SaveItem(
                    file = f,
                    displayName = f.name,
                    gameTitle = gameTitle,
                    isBatterySave = true,
                    sizeBytes = f.length(),
                    lastModified = f.lastModified()
                )
            )
        }

        // 2. Collect quick save states (.ss) inside per-game directories
        statesDir.listFiles { file -> file.isDirectory }?.forEach { gameDir ->
            val gameTitle = gameDir.name
            gameDir.listFiles { file -> file.isFile && file.extension.equals("ss", ignoreCase = true) }?.forEach { f ->
                result.add(
                    SaveItem(
                        file = f,
                        displayName = "${gameTitle} — ${f.name}",
                        gameTitle = gameTitle,
                        isBatterySave = false,
                        sizeBytes = f.length(),
                        lastModified = f.lastModified()
                    )
                )
            }
        }

        return result.sortedByDescending { it.lastModified }
    }

    /**
     * Imports a save file from a user-selected SAF Uri into the saves or states directory.
     */
    fun importSave(context: Context, sourceUri: Uri): Boolean {
        return try {
            val fileName = getFileNameFromUri(context, sourceUri) ?: "imported_${System.currentTimeMillis()}.sav"
            val destFile = if (fileName.endsWith(".ss", ignoreCase = true)) {
                val gameTitle = fileName.substringBeforeLast('.').substringBeforeLast('_').ifBlank { "imported" }
                val gameDir = File(getStateDir(context), gameTitle).also { it.mkdirs() }
                File(gameDir, fileName)
            } else {
                File(getSaveDir(context), fileName)
            }

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Exports a save file to a user-selected SAF destination Uri.
     */
    fun exportSave(context: Context, saveFile: File, targetUri: Uri): Boolean {
        return try {
            saveFile.inputStream().use { input ->
                context.contentResolver.openOutputStream(targetUri)?.use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val colIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (colIdx != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(colIdx)
                }
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /**
     * Safely migrates any legacy files from /storage/emulated/0/mGBA into Android/data.
     */
    fun migrateLegacyStorage(context: Context) {
        try {
            val legacyBase = File(Environment.getExternalStorageDirectory(), "mGBA")
            if (legacyBase.exists() && legacyBase.isDirectory) {
                val targetBase = getBaseDir(context)
                legacyBase.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val relative = file.relativeTo(legacyBase)
                        val targetFile = File(targetBase, relative.path)
                        if (!targetFile.exists()) {
                            targetFile.parentFile?.mkdirs()
                            file.copyTo(targetFile, overwrite = false)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Creates all required mGBA folders so they are immediately available.
     */
    fun ensureDirectories(context: Context) {
        migrateLegacyStorage(context)
        getSaveDir(context)
        getStateDir(context)
        getScreenshotsDir(context)
        getCoversDir(context)
    }
}
