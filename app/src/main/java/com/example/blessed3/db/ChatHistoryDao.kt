package com.example.blessed3.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatHistoryDao {

    @Query(
        "SELECT * FROM conversation_messages WHERE peerAppId = :peerAppId ORDER BY timestampMs ASC"
    )
    fun observeForPeer(peerAppId: String): Flow<List<ConversationMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ConversationMessageEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE dedupeKey = :key LIMIT 1)")
    suspend fun existsWithDedupeKey(key: String): Boolean

    /**
     * One row per peer: the latest message in that conversation (by [ConversationMessageEntity.timestampMs]).
     */
    @Query(
        """
        SELECT * FROM conversation_messages AS m
        WHERE m.timestampMs = (
            SELECT MAX(m2.timestampMs) FROM conversation_messages AS m2
            WHERE m2.peerAppId = m.peerAppId
        )
        """
    )
    fun observeLatestMessagePerPeer(): Flow<List<ConversationMessageEntity>>
}
