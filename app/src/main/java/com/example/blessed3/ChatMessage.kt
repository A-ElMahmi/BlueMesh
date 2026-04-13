package com.example.blessed3

data class ChatMessage(
    val id: String,
    val text: String,
    val isFromMe: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)
