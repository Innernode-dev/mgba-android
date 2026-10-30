package com.retro.mgba.scanner

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Utility helper for managing Storage Access Framework (SAF) tree permissions.
 * Guarantees persistable read/write access across device reboots and activity lifecycles.
 */
object SafPermissionHelper {
    private const val TAG = "SafPermissionHelper"

    /**
     * Takes and persists read/write URI permissions for the given SAF folder tree URI.
     * Falls back to read-only permission if write access is restricted by the provider.
     *
     * @param context Application or Activity context.
     * @param treeUri SAF Tree URI returned by ActivityResultContracts.OpenDocumentTree.
     * @return True if persistable permission was successfully acquired; false otherwise.
     */
    fun takePersistablePermissions(context: Context, treeUri: Uri): Boolean {
        val contentResolver = context.contentResolver
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        return try {
            contentResolver.takePersistableUriPermission(treeUri, readWriteFlags)
            Log.d(TAG, "Acquired persistable read/write permission for $treeUri")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Write permission denied for $treeUri, attempting read-only fallback: ${e.message}")
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Log.d(TAG, "Acquired persistable read-only permission for $treeUri")
                true
            } catch (fallbackEx: SecurityException) {
                Log.e(TAG, "Failed to acquire any persistable permission for $treeUri", fallbackEx)
                false
            }
        }
    }

    /**
     * Checks whether the application holds an active, persisted read permission for the tree URI.
     */
    fun hasPersistedPermission(context: Context, treeUri: Uri): Boolean {
        val persistedList: List<UriPermission> = context.contentResolver.persistedUriPermissions
        return persistedList.any { it.uri == treeUri && it.isReadPermission }
    }

    /**
     * Releases persisted permissions for a folder when removed from library settings.
     */
    fun releasePersistablePermissions(context: Context, treeUri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(treeUri, flags)
            Log.d(TAG, "Released persistable permission for $treeUri")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release permission for $treeUri: ${e.message}")
            false
        }
    }

    /**
     * Retrieves all currently held persisted SAF tree URIs.
     */
    fun getPersistedTreeUris(context: Context): List<Uri> {
        return context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
    }

    /**
     * Resolves a user-friendly display name for the directory represented by treeUri.
     */
    fun getFolderDisplayName(context: Context, treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
            // Fallback: extract last path segment or document ID
            docId.substringAfterLast(':').substringAfterLast('/')
        } catch (e: Exception) {
            treeUri.lastPathSegment ?: "ROM Directory"
        }
    }
}
