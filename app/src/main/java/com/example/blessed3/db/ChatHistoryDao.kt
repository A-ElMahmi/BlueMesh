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
}
