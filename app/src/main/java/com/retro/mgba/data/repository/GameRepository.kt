package com.retro.mgba.data.repository

import com.retro.mgba.data.db.GameDao
import com.retro.mgba.data.db.GameEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository mediating access to game metadata and cover assignments.
 */
class GameRepository(
    private val gameDao: GameDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val allGames: Flow<List<GameEntity>> = gameDao.getAllGames()
    val resumeGame: Flow<GameEntity?> = gameDao.getResumeGame()
    val recentGames: Flow<List<GameEntity>> = gameDao.getRecentGames(10)
    val favorites: Flow<List<GameEntity>> = gameDao.getFavorites()

    fun getGamesBySystem(system: String): Flow<List<GameEntity>> =
        gameDao.getGamesBySystem(system)

    fun searchGames(query: String): Flow<List<GameEntity>> =
        gameDao.searchGames(query)

    suspend fun getGame(uri: String): GameEntity? = withContext(ioDispatcher) {
        gameDao.getGameByUri(uri)
    }

    suspend fun upsertScannedGames(games: List<GameEntity>) = withContext(ioDispatcher) {
        gameDao.upsertScannedGames(games)
    }

    suspend fun toggleFavorite(uri: String) = withContext(ioDispatcher) {
        gameDao.toggleFavorite(uri)
    }

    suspend fun updatePlayHistory(uri: String, sessionSeconds: Long) = withContext(ioDispatcher) {
        gameDao.updatePlayHistory(
            uri = uri,
            timestamp = System.currentTimeMillis(),
            addedPlayTimeSeconds = sessionSeconds
        )
    }

    suspend fun setCustomCover(uri: String, coverUri: String?) = withContext(ioDispatcher) {
        gameDao.updateCustomCover(uri, coverUri)
    }

    suspend fun deleteGame(uri: String) = withContext(ioDispatcher) {
        gameDao.deleteByUri(uri)
    }

    suspend fun pruneMissingGames(retainedUris: List<String>) = withContext(ioDispatcher) {
        gameDao.pruneMissingGames(retainedUris)
    }
}
