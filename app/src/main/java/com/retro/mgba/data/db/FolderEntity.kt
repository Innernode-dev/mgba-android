package com.retro.mgba.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a persisted user-selected SAF directory tree URI.
 * Storing these URIs allows the app to retain and scan game directories across launches.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    /**
     * Persisted SAF Tree URI string (e.g. content://com.android.externalstorage.documents/tree/...).
     */
    @PrimaryKey
    @ColumnInfo(name = "treeUri")
    val treeUri: String,

    /**
     * Human-friendly directory display name (e.g. "GBA Roms", "Downloads/Emulation").
     */
    @ColumnInfo(name = "displayName")
    val displayName: String,

    /**
     * Epoch timestamp in milliseconds when the folder was added to the library.
     */
    @ColumnInfo(name = "addedTimestamp")
    val addedTimestamp: Long = System.currentTimeMillis()
)
