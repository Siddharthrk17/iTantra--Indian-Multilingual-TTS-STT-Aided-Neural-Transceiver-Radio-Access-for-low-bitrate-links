package com.example.itantra.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isAcked = 0 AND isOutgoing = 1")
    suspend fun getUnackedOutgoingMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET isAcked = 1, deliveryStatus = 'DELIVERED' WHERE messageId = :messageId")
    suspend fun markAsAcked(messageId: String)

    @Query("DELETE FROM messages WHERE isAcked = 1 AND timestamp < :threshold")
    suspend fun pruneOldMessages(threshold: Long)

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}