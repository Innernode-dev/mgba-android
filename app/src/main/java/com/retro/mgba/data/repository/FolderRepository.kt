package com.retro.mgba.data.repository

import com.retro.mgba.data.db.FolderDao
import com.retro.mgba.data.db.FolderEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository managing persistent SAF directory tree URIs.
 */
class FolderRepository(
    private val folderDao: FolderDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val folders: Flow<List<FolderEntity>> = folderDao.getAllFolders()

    suspend fun getFolderList(): List<FolderEntity> = withContext(ioDispatcher) {
        folderDao.getFolderList()
    }

    suspend fun addFolder(treeUri: String, displayName: String) = withContext(ioDispatcher) {
        folderDao.insertFolder(
            FolderEntity(
                treeUri = treeUri,
                displayName = displayName,
                addedTimestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeFolder(treeUri: String) = withContext(ioDispatcher) {
        folderDao.deleteFolderByUri(treeUri)
    }

    suspend fun folderExists(treeUri: String): Boolean = withContext(ioDispatcher) {
        folderDao.folderExists(treeUri)
    }
}
