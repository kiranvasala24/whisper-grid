package com.whispergrid.app.network

import android.net.wifi.p2p.WifiP2pInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class ConnectionManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<WireMessage>>(emptyList())
    val receivedMessages: StateFlow<List<WireMessage>> = _receivedMessages.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    private val deviceId = android.os.Build.MODEL + "_" + System.currentTimeMillis().toString().takeLast(4)
    private val deviceName = android.os.Build.MODEL

    companion object {
        private const val TAG = "ConnectionManager"
        private const val PORT = 8888
        private const val SOCKET_TIMEOUT = 10000
    }

    fun startServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                _connectionState.value = ConnectionState.Listening
                Log.d(TAG, "Server started on port $PORT")

                while (isActive) {
                    try {
                        val client = serverSocket?.accept()
                        client?.let {
                            handleClient(it)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Server accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start error: ${e.message}")
                _connectionState.value = ConnectionState.Error(e.message ?: "Server failed")
            }
        }
    }

    fun connectToServer(groupOwnerAddress: String) {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.Connecting
                Log.d(TAG, "Connecting to server at $groupOwnerAddress:$PORT")

                val socket = Socket()
                socket.connect(InetSocketAddress(groupOwnerAddress, PORT), SOCKET_TIMEOUT)

                handleClient(socket)

                // Send handshake
                val handshake = MessageProtocol.createHandshake(deviceId, deviceName)
                sendMessage(handshake)

            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                clientSocket = socket
                writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                _connectionState.value = ConnectionState.Connected(
                    socket.inetAddress.hostAddress ?: "unknown"
                )

                Log.d(TAG, "Client connected: ${socket.inetAddress.hostAddress}")

                // Read messages
                while (isActive && !socket.isClosed) {
                    try {
                        val line = reader?.readLine()
                        if (line != null) {
                            handleReceivedMessage(line)
                        } else {
                            break
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Read error: ${e.message}")
                        }
                        break
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Handle client error: ${e.message}")
            } finally {
                disconnect()
            }
        }
    }

    private fun handleReceivedMessage(data: String) {
        val message = MessageProtocol.deserialize(data)
        if (message != null) {
            Log.d(TAG, "Received message: ${message.type} from ${message.senderName}")
            _receivedMessages.value = _receivedMessages.value + message
        }
    }

    fun sendMessage(message: WireMessage) {
        scope.launch {
            try {
                val data = MessageProtocol.serialize(message)
                writer?.write(data)
                writer?.newLine()
                writer?.flush()
                Log.d(TAG, "Sent message: ${message.type}")
            } catch (e: Exception) {
                Log.e(TAG, "Send error: ${e.message}")
                _connectionState.value = ConnectionState.Error(e.message ?: "Send failed")
            }
        }
    }

    fun sendTextMessage(content: String) {
        val message = MessageProtocol.createTextMessage(deviceId, deviceName, content)
        sendMessage(message)
    }

    fun handleP2pConnection(info: WifiP2pInfo) {
        if (info.groupFormed) {
            if (info.isGroupOwner) {
                Log.d(TAG, "I am group owner - starting server")
                startServer()
            } else {
                Log.d(TAG, "I am client - connecting to ${info.groupOwnerAddress.hostAddress}")
                info.groupOwnerAddress?.let { address ->
                    connectToServer(address.hostAddress)
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                writer?.close()
                reader?.close()
                clientSocket?.close()
                serverSocket?.close()

                writer = null
                reader = null
                clientSocket = null
                serverSocket = null

                _connectionState.value = ConnectionState.Disconnected
                Log.d(TAG, "Disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error: ${e.message}")
            }
        }
    }

    fun getDeviceId() = deviceId
    fun getDeviceName() = deviceName
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Listening : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val peerAddress: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}