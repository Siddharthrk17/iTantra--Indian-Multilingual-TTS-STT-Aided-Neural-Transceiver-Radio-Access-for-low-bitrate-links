package com.example.itantra.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val nodeId: String,
    val deviceName: String?,
    val lastSeen: Long,
    val isConnected: Boolean = false
)