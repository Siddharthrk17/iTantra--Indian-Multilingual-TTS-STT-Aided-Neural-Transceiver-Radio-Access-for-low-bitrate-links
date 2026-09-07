package com.example.itantra.transport.nearby

import android.content.Context
import com.example.itantra.util.Logger
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class NearbyTransport(
    private val context: Context,
    private val onDeviceFound: (String, String) -> Unit,
    private val onMessageReceived: (ByteArray) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val strategy = Strategy.P2P_STAR // Best for 1-to-many or 1-to-1 rooms
    private val serviceId = "com.example.itantra.SERVICE_ID"
    
    private var activeEndpointId: String? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Logger.d("Nearby: Connection initiated with ${info.endpointName}")
            // Automatically accept the connection
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Logger.d("Nearby: Connected to $endpointId")
                    activeEndpointId = endpointId
                    onConnected(endpointId)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Logger.e("Nearby: Connection rejected")
                }
                else -> {
                    Logger.e("Nearby: Connection failed with status ${result.status}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Logger.d("Nearby: Disconnected from $endpointId")
            activeEndpointId = null
            onDisconnected()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Logger.d("Nearby: Endpoint found: ${info.endpointName} [$endpointId]")
            onDeviceFound(info.endpointName, endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            Logger.d("Nearby: Endpoint lost: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                onMessageReceived(it)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Can track progress if needed
        }
    }

    fun startAdvertising(name: String) {
        connectionsClient.stopAdvertising()
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(name, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { Logger.d("Nearby: Advertising started as $name") }
            .addOnFailureListener { e -> Logger.e("Nearby: Advertising failed", e) }
    }

    fun startDiscovery() {
        connectionsClient.stopDiscovery()
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Logger.d("Nearby: Discovery started") }
            .addOnFailureListener { e -> Logger.e("Nearby: Discovery failed", e) }
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }

    fun connect(localName: String, endpointId: String) {
        connectionsClient.requestConnection(localName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { Logger.d("Nearby: Connection requested from $localName to $endpointId") }
            .addOnFailureListener { e -> Logger.e("Nearby: Connection request failed", e) }
    }

    fun sendMessage(data: ByteArray) {
        val endpoint = activeEndpointId
        if (endpoint != null) {
            Logger.d("Nearby: Sending payload of size ${data.size} to $endpoint")
            connectionsClient.sendPayload(endpoint, Payload.fromBytes(data))
                .addOnFailureListener { e -> Logger.e("Nearby: Failed to send payload", e) }
        } else {
            Logger.e("Nearby: No active endpoint to send message")
        }
    }
}
