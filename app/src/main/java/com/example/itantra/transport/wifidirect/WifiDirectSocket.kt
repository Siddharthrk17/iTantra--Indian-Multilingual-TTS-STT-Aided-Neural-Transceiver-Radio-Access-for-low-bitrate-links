package com.example.itantra.transport.wifidirect

import com.example.itantra.protocol.ITantraMessage
import com.example.itantra.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class WifiDirectSocket(
    private val onMessageReceived: (ITantraMessage) -> Unit
) {

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null

    companion object {
        const val PORT = 8888
    }

    suspend fun startServer() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(PORT)
            Logger.d("Socket Server started on port $PORT. Waiting for connection...")
            val socket = serverSocket?.accept()
            activeSocket = socket
            Logger.d("Socket Server: Client connected from ${socket?.inetAddress?.hostAddress}")
            // Run listener in a separate job
            CoroutineScope(Dispatchers.IO).launch {
                listenForMessages(socket)
            }
        } catch (e: Exception) {
            Logger.e("Socket Server error: ${e.message}")
        }
    }

    suspend fun connectToServer(host: String) = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            activeSocket = socket
            Logger.d("Socket Client: Attempting to connect to $host:$PORT...")
            socket.connect(InetSocketAddress(host, PORT), 10000)
            Logger.d("Socket Client: Connected to server")
            // Run listener in a separate job
            CoroutineScope(Dispatchers.IO).launch {
                listenForMessages(socket)
            }
        } catch (e: Exception) {
            Logger.e("Socket Client error: ${e.message}")
        }
    }

    private fun listenForMessages(socket: Socket?) {
        if (socket == null) return
        try {
            val input = socket.getInputStream()
            while (!socket.isClosed) {
                val message = ITantraMessage.parseDelimitedFrom(input)
                if (message != null) {
                    onMessageReceived(message)
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            if (!socket.isClosed) {
                Logger.e("Socket listening error: ${e.message}")
            }
        } finally {
            Logger.d("Socket connection closed")
        }
    }

    suspend fun sendMessage(message: ITantraMessage) = withContext(Dispatchers.IO) {
        try {
            val socket = activeSocket
            if (socket != null && !socket.isClosed) {
                val output = socket.getOutputStream()
                message.writeDelimitedTo(output)
                output.flush()
                Logger.d("Socket Sent: ${message.contentCase}")
            } else {
                Logger.e("Socket not connected, cannot send message")
            }
        } catch (e: Exception) {
            Logger.e("Socket send error: ${e.message}")
        }
    }

    fun close() {
        try {
            serverSocket?.close()
            activeSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}