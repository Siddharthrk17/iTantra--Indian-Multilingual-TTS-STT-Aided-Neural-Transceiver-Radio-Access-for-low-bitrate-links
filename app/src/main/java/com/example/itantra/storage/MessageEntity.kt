package com.example.itantra.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val senderId: String,
    val timestamp: Long,
    val type: String, // TEXT or VOICE
    val originalLanguage: String,
    val targetLanguage: String,
    val payload: String,
    val isOutgoing: Boolean,
    val isAcked: Boolean = false,
    val deliveryStatus: String = "PENDING" // PENDING, DELIVERED, FAILED
)