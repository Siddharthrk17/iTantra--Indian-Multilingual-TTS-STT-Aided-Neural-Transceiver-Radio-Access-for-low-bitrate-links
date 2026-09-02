package com.example.itantra.storage

import com.example.itantra.protocol.ITantraEnvelope
import com.example.itantra.util.Logger
import kotlinx.coroutines.flow.Flow

class MessageRepository(private val messageDao: MessageDao) {

    val allMessages: Flow<List<MessageEntity>> = messageDao.getAllMessages()

    suspend fun saveIncomingEnvelope(envelope: ITantraEnvelope) {
        val entity = MessageEntity(
            messageId = envelope.messageId,
            senderId = envelope.senderId,
            timestamp = envelope.timestamp.toLongOrNull() ?: System.currentTimeMillis(),
            type = envelope.type.name,
            originalLanguage = envelope.originalLanguage,
            targetLanguage = envelope.targetLanguage,
            payload = envelope.payload,
            isOutgoing = false,
            isAcked = true, // We received it, so it's "acked" from our perspective
            deliveryStatus = "DELIVERED"
        )
        messageDao.insertMessage(entity)
        Logger.d("DB: Saved incoming message ${envelope.messageId}")
    }

    suspend fun saveOutgoingEnvelope(envelope: ITantraEnvelope) {
        val entity = MessageEntity(
            messageId = envelope.messageId,
            senderId = envelope.senderId,
            timestamp = envelope.timestamp.toLongOrNull() ?: System.currentTimeMillis(),
            type = envelope.type.name,
            originalLanguage = envelope.originalLanguage,
            targetLanguage = envelope.targetLanguage,
            payload = envelope.payload,
            isOutgoing = true,
            isAcked = false,
            deliveryStatus = "PENDING"
        )
        messageDao.insertMessage(entity)
        Logger.d("DB: Saved outgoing message ${envelope.messageId}")
    }

    suspend fun handleAck(messageId: String) {
        messageDao.markAsAcked(messageId)
        Logger.d("DB: Marked message $messageId as ACKED")
    }

    suspend fun pruneMessages() {
        // Prune delivered messages older than 7 days
        val threshold = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        messageDao.pruneOldMessages(threshold)
        Logger.d("DB: Pruned old messages")
    }

    suspend fun getUnackedMessages(): List<MessageEntity> {
        return messageDao.getUnackedOutgoingMessages()
    }

    suspend fun deleteAllMessages() {
        messageDao.deleteAllMessages()
        Logger.d("DB: All messages deleted")
    }
}