package com.whispergrid.app.network

import android.net.wifi.p2p.WifiP2pDevice

data class Peer(
    val deviceAddress: String,
    val deviceName: String,
    val isGroupOwner: Boolean = false,
    val status: PeerStatus = PeerStatus.AVAILABLE
)

enum class PeerStatus {
    AVAILABLE,
    INVITED,
    CONNECTED,
    FAILED,
    UNAVAILABLE
}

fun WifiP2pDevice.toPeer(): Peer {
    return Peer(
        deviceAddress = deviceAddress,
        deviceName = deviceName,
        status = when (status) {
            WifiP2pDevice.AVAILABLE -> PeerStatus.AVAILABLE
            WifiP2pDevice.INVITED -> PeerStatus.INVITED
            WifiP2pDevice.CONNECTED -> PeerStatus.CONNECTED
            WifiP2pDevice.FAILED -> PeerStatus.FAILED
            WifiP2pDevice.UNAVAILABLE -> PeerStatus.UNAVAILABLE
            else -> PeerStatus.UNAVAILABLE
        }
    )
}

data class NetworkMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val timestamp: Long
)