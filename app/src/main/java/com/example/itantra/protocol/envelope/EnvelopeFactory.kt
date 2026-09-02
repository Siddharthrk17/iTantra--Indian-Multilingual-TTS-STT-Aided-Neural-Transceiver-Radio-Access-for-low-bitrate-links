package com.example.itantra.protocol.envelope

import com.example.itantra.protocol.ITantraEnvelope
import com.example.itantra.protocol.ITantraMessage
import java.util.UUID

object EnvelopeFactory {
    fun createTextMessage(
        senderId: String,
        payload: String,
        originalLang: String,
        targetLang: String
    ): ITantraMessage {
        val envelope = ITantraEnvelope.newBuilder()
            .setMessageId("MSG-${UUID.randomUUID()}")
            .setSenderId(senderId)
            .setTimestamp(System.currentTimeMillis().toString())
            .setType(ITantraEnvelope.MessageType.TEXT)
            .setOriginalLanguage(originalLang)
            .setTargetLanguage(targetLang)
            .setPayload(payload)
            .setAckRequired(true)
            .build()

        return ITantraMessage.newBuilder()
            .setEnvelope(envelope)
            .build()
    }

    fun createSystemMessage(
        senderId: String,
        payload: String
    ): ITantraMessage {
        val envelope = ITantraEnvelope.newBuilder()
            .setMessageId("SYS-${UUID.randomUUID()}")
            .setSenderId(senderId)
            .setTimestamp(System.currentTimeMillis().toString())
            .setType(ITantraEnvelope.MessageType.SYSTEM)
            .setPayload(payload)
            .setAckRequired(false)
            .build()

        return ITantraMessage.newBuilder()
            .setEnvelope(envelope)
            .build()
    }
}