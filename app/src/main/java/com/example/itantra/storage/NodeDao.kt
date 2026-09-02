package com.example.itantra.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes ORDER BY lastSeen DESC")
    fun getAllNodes(): Flow<List<NodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: NodeEntity)

    @Query("UPDATE nodes SET isConnected = :connected WHERE nodeId = :nodeId")
    suspend fun updateConnectionStatus(nodeId: String, connected: Boolean)
}