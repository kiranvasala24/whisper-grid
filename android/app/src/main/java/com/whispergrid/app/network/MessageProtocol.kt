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
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
enum class MessageType {
    TEXT,
    SYSTEM,
    HANDSHAKE
}

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
        content: String
    ): WireMessage {
        return WireMessage(
            type = MessageType.TEXT,
            senderId = senderId,
            senderName = senderName,
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
}