package com.retro.mgba.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for persisted user-selected SAF folders.
 */
@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY addedTimestamp ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY addedTimestamp ASC")
    suspend fun getFolderList(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Delete
    suspend fun deleteFolder(folder: FolderEntity): Int

    @Query("DELETE FROM folders WHERE treeUri = :treeUri")
    suspend fun deleteFolderByUri(treeUri: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE treeUri = :treeUri)")
    suspend fun folderExists(treeUri: String): Boolean
}
