package com.example.itantra.protocol.ack

import com.example.itantra.protocol.ITantraAck
import com.example.itantra.protocol.ITantraMessage

object AckFactory {
    fun createAck(messageId: String, status: ITantraAck.AckStatus): ITantraMessage {
        val ack = ITantraAck.newBuilder()
            .setAckOf(messageId)
            .setStatus(status)
            .setTimestamp(System.currentTimeMillis().toString())
            .build()

        return ITantraMessage.newBuilder()
            .setAck(ack)
            .build()
    }
}