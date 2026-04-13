package com.example.blessed3.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_messages",
    indices = [
        Index(value = ["peerAppId"]),
        Index(value = ["dedupeKey"])
    ]
)
data class ConversationMessageEntity(
    @PrimaryKey val messageId: String,
    val peerAppId: String,
    val timestampMs: Long,
    val text: String,
    val fromMe: Boolean,
    /** Server `messageId` or relay id for deduplication; null for plain BLE sends */
    val dedupeKey: String?
)
