package com.whispergrid.app.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID

@Serializable
data class WireMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: MessageType,
    val senderId: String,
    val senderName: String,
    val targetId: String? = null, // null = broadcast
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = 10, // Time To Live - max hops
    val hopCount: Int = 0,
    val path: List<String> = emptyList() // Track route taken
)

@Serializable
enum class MessageType {
    TEXT,
    SYSTEM,
    HANDSHAKE,
    ROUTE_DISCOVERY,
    ROUTE_RESPONSE,
    PING,
    PONG
}

@Serializable
data class RouteInfo(
    val destination: String,
    val nextHop: String,
    val hopCount: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

object MessageProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun serialize(message: WireMessage): String {
        return json.encodeToString(message)
    }

    fun deserialize(data: String): WireMessage? {
        return try {
            json.decodeFromString<WireMessage>(data)
        } catch (e: Exception) {
            null
        }
    }

    fun createTextMessage(
        senderId: String,
        senderName: String,
        content: String,
        targetId: String? = null
    ): WireMessage {
        return WireMessage(
            type = MessageType.TEXT,
            senderId = senderId,
            senderName = senderName,
            targetId = targetId,
            content = content
        )
    }

    fun createHandshake(
        senderId: String,
        senderName: String
    ): WireMessage {
        return WireMessage(
            type = MessageType.HANDSHAKE,
            senderId = senderId,
            senderName = senderName,
            content = "HELLO"
        )
    }

    fun createRouteDiscovery(
        senderId: String,
        senderName: String,
        targetId: String
    ): WireMessage {
        return WireMessage(
            type = MessageType.ROUTE_DISCOVERY,
            senderId = senderId,
            senderName = senderName,
            targetId = targetId,
            content = "ROUTE_REQ",
            path = listOf(senderId)
        )
    }

    fun createPing(
        senderId: String,
        senderName: String
    ): WireMessage {
        return WireMessage(
            type = MessageType.PING,
            senderId = senderId,
            senderName = senderName,
            content = "PING"
        )
    }

    fun forward(message: WireMessage, myId: String): WireMessage? {
        // Check TTL
        if (message.ttl <= 0) return null

        // Don't forward if we're already in the path (loop detection)
        if (message.path.contains(myId)) return null

        return message.copy(
            ttl = message.ttl - 1,
            hopCount = message.hopCount + 1,
            path = message.path + myId
        )
    }
}