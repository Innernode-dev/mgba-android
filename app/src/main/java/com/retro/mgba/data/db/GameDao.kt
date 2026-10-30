package com.retro.mgba.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [GameEntity].
 * Provides reactive [Flow] queries for library grids, search, filters, and safe upserts.
 */
@Dao
interface GameDao {

    // --- Reactive Flow Queries ---

    @Query("SELECT * FROM games ORDER BY title COLLATE NOCASE ASC")
    fun getAllGames(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE system = :system ORDER BY title COLLATE NOCASE ASC")
    fun getGamesBySystem(system: String): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE ASC")
    fun getFavorites(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT :limit")
    fun getRecentGames(limit: Int = 10): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT 1")
    fun getResumeGame(): Flow<GameEntity?>

    @Query("""
        SELECT * FROM games 
        WHERE title LIKE '%' || :query || '%' 
           OR gameCode LIKE '%' || :query || '%' 
        ORDER BY title COLLATE NOCASE ASC
    """)
    fun searchGames(query: String): Flow<List<GameEntity>>

    @Query("SELECT * FROM games ORDER BY lastPlayed DESC, title COLLATE NOCASE ASC")
    fun getAllGamesSortedByRecent(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games ORDER BY fileSize DESC, title COLLATE NOCASE ASC")
    fun getAllGamesSortedBySizeDesc(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games ORDER BY fileSize ASC, title COLLATE NOCASE ASC")
    fun getAllGamesSortedBySizeAsc(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games ORDER BY title COLLATE NOCASE DESC")
    fun getAllGamesSortedByTitleDesc(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games ORDER BY dateAdded DESC, title COLLATE NOCASE ASC")
    fun getAllGamesSortedByDateAddedDesc(): Flow<List<GameEntity>>

    // --- Direct Suspend Lookups ---

    @Query("SELECT * FROM games WHERE uri = :uri LIMIT 1")
    suspend fun getGameByUri(uri: String): GameEntity?

    @Query("SELECT * FROM games WHERE uri = :uri LIMIT 1")
    fun observeGameByUri(uri: String): Flow<GameEntity?>

    @Query("SELECT * FROM games")
    suspend fun getAllGamesSync(): List<GameEntity>

    @Query("SELECT COUNT(*) FROM games")
    fun getGameCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM games WHERE system = :system")
    fun getGameCountBySystem(system: String): Flow<Int>

    // --- Metadata-Preserving Upserts (Android API 26-28+ SQLite Compatible) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(game: GameEntity)

    @Query("""
        UPDATE games 
        SET title = :title, 
            system = :system, 
            fileSize = :fileSize, 
            gameCode = COALESCE(:gameCode, gameCode), 
            region = COALESCE(:region, region) 
        WHERE uri = :uri
    """)
    suspend fun updateScannedMetadata(
        uri: String,
        title: String,
        system: String,
        fileSize: Long,
        gameCode: String?,
        region: String
    )

    /**
     * Inserts or updates a ROM detected during storage scans.
     * CRITICAL: Preserves user-assigned metadata (isFavorite, lastPlayed, playTimeSeconds,
     * customCoverUri, sramPath) if the game already exists in the database.
     * Fully compatible with Android 8.0-9.0 (SQLite < 3.24.0).
     */
    @Transaction
    suspend fun upsertScannedGame(game: GameEntity) {
        val existing = getGameByUri(game.uri)
        if (existing == null) {
            insert(game)
        } else {
            updateScannedMetadata(
                uri = game.uri,
                title = game.title,
                system = game.system,
                fileSize = game.fileSize,
                gameCode = game.gameCode,
                region = game.region
            )
        }
    }

    @Transaction
    suspend fun upsertScannedGame(
        uri: String,
        title: String,
        system: String,
        gameCode: String?,
        fileSize: Long,
        lastPlayed: Long = 0L,
        playTimeSeconds: Long = 0L,
        isFavorite: Boolean = false,
        customCoverUri: String? = null,
        sramPath: String? = null,
        dateAdded: Long = System.currentTimeMillis(),
        region: String = "USA"
    ) {
        upsertScannedGame(
            GameEntity(
                uri = uri,
                title = title,
                system = system,
                gameCode = gameCode,
                fileSize = fileSize,
                lastPlayed = lastPlayed,
                playTimeSeconds = playTimeSeconds,
                isFavorite = isFavorite,
                customCoverUri = customCoverUri,
                sramPath = sramPath,
                dateAdded = dateAdded,
                region = region
            )
        )
    }

    @Transaction
    suspend fun upsertScannedGames(games: List<GameEntity>) {
        for (game in games) {
            upsertScannedGame(game)
        }
    }

    /**
     * Interface contract implementation for inserting scanned games batch.
     * Delegates to [upsertScannedGames] to guarantee user-set metadata is never overwritten.
     */
    @Transaction
    suspend fun insertAll(games: List<GameEntity>) {
        upsertScannedGames(games)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(game: GameEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(game: GameEntity): Long

    @Update
    suspend fun update(game: GameEntity): Int

    // --- User Actions & State Mutation ---

    @Query("UPDATE games SET isFavorite = CASE WHEN isFavorite = 1 THEN 0 ELSE 1 END WHERE uri = :uri")
    suspend fun toggleFavorite(uri: String): Int

    @Query("UPDATE games SET isFavorite = :isFavorite WHERE uri = :uri")
    suspend fun setFavorite(uri: String, isFavorite: Boolean): Int

    @Query("""
        UPDATE games 
        SET lastPlayed = :timestamp, 
            playTimeSeconds = playTimeSeconds + :addedPlayTimeSeconds 
        WHERE uri = :uri
    """)
    suspend fun updatePlayHistory(uri: String, timestamp: Long, addedPlayTimeSeconds: Long): Int

    @Query("UPDATE games SET customCoverUri = :customCoverUri WHERE uri = :uri")
    suspend fun updateCustomCover(uri: String, customCoverUri: String?): Int

    @Query("UPDATE games SET sramPath = :sramPath WHERE uri = :uri")
    suspend fun updateSramPath(uri: String, sramPath: String?): Int

    // --- Deletions ---

    @Delete
    suspend fun delete(game: GameEntity): Int

    @Query("DELETE FROM games WHERE uri = :uri")
    suspend fun deleteByUri(uri: String): Int

    @Query("DELETE FROM games WHERE uri NOT IN (:retainedUris)")
    suspend fun pruneMissingGames(retainedUris: List<String>): Int

    @Query("DELETE FROM games")
    suspend fun deleteAll(): Int
}
