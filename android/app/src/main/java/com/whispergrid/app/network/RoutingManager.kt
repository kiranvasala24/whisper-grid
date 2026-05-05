package com.whispergrid.app.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class RoutingManager(
    private val myDeviceId: String,
    private val myDeviceName: String
) {

    // Routing table: destination -> RouteInfo
    private val routingTable = ConcurrentHashMap<String, RouteInfo>()

    // Known peers (direct connections)
    private val directPeers = ConcurrentHashMap<String, PeerConnection>()

    private val _networkTopology = MutableStateFlow<Map<String, RouteInfo>>(emptyMap())
    val networkTopology: StateFlow<Map<String, RouteInfo>> = _networkTopology.asStateFlow()

    private val _totalNodes = MutableStateFlow(1) // Start with ourselves
    val totalNodes: StateFlow<Int> = _totalNodes.asStateFlow()

    companion object {
        private const val TAG = "RoutingManager"
        private const val ROUTE_TIMEOUT = 60000L // 1 minute
    }

    data class PeerConnection(
        val peerId: String,
        val peerName: String,
        val connectionManager: ConnectionManager
    )

    fun addDirectPeer(peerId: String, peerName: String, connectionManager: ConnectionManager) {
        directPeers[peerId] = PeerConnection(peerId, peerName, connectionManager)

        // Add route to directly connected peer
        addRoute(peerId, peerId, 0)

        Log.d(TAG, "Added direct peer: $peerName ($peerId)")
    }

    fun removeDirectPeer(peerId: String) {
        directPeers.remove(peerId)

        // Remove all routes through this peer
        val toRemove = routingTable.filter { it.value.nextHop == peerId }
        toRemove.keys.forEach { routingTable.remove(it) }

        updateTopology()
        Log.d(TAG, "Removed peer: $peerId")
    }

    fun addRoute(destination: String, nextHop: String, hopCount: Int) {
        val existing = routingTable[destination]

        // Update if new route is shorter or doesn't exist
        if (existing == null || hopCount < existing.hopCount) {
            routingTable[destination] = RouteInfo(
                destination = destination,
                nextHop = nextHop,
                hopCount = hopCount,
                lastSeen = System.currentTimeMillis()
            )

            updateTopology()
            Log.d(TAG, "Route added: $destination via $nextHop ($hopCount hops)")
        }
    }

    fun getRoute(destination: String): RouteInfo? {
        val route = routingTable[destination]

        // Check if route is stale
        if (route != null) {
            val age = System.currentTimeMillis() - route.lastSeen
            if (age > ROUTE_TIMEOUT) {
                routingTable.remove(destination)
                updateTopology()
                return null
            }
        }

        return route
    }

    fun forwardMessage(message: WireMessage): Boolean {
        // Broadcast message (no specific target)
        if (message.targetId == null) {
            broadcastMessage(message)
            return true
        }

        // Message for me
        if (message.targetId == myDeviceId) {
            return false // Don't forward, deliver locally
        }

        // Find route to target
        val route = getRoute(message.targetId)
        if (route == null) {
            Log.w(TAG, "No route to ${message.targetId}")
            return false
        }

        // Forward to next hop
        val nextHopPeer = directPeers[route.nextHop]
        if (nextHopPeer != null) {
            val forwarded = MessageProtocol.forward(message, myDeviceId)
            if (forwarded != null) {
                nextHopPeer.connectionManager.sendMessage(forwarded)
                Log.d(TAG, "Forwarded message to ${route.nextHop} for ${message.targetId}")
                return true
            }
        }

        return false
    }

    private fun broadcastMessage(message: WireMessage) {
        val forwarded = MessageProtocol.forward(message, myDeviceId) ?: return

        directPeers.values.forEach { peer ->
            // Don't send back to the peer we received from
            if (!message.path.contains(peer.peerId)) {
                peer.connectionManager.sendMessage(forwarded)
                Log.d(TAG, "Broadcast to ${peer.peerName}")
            }
        }
    }

    fun processMessage(message: WireMessage): ProcessResult {
        // Update routing table based on message path
        if (message.path.isNotEmpty()) {
            val origin = message.path.first()
            val lastHop = message.path.last()

            if (origin != myDeviceId) {
                addRoute(origin, lastHop, message.path.size)
            }
        }

        return when {
            // Message is for me
            message.targetId == null || message.targetId == myDeviceId -> {
                ProcessResult.Deliver(message)
            }

            // Message needs forwarding
            else -> {
                if (forwardMessage(message)) {
                    ProcessResult.Forwarded
                } else {
                    ProcessResult.NoRoute
                }
            }
        }
    }

    fun discoverRoute(targetId: String) {
        val discovery = MessageProtocol.createRouteDiscovery(
            myDeviceId,
            myDeviceName,
            targetId
        )
        broadcastMessage(discovery)
        Log.d(TAG, "Route discovery initiated for $targetId")
    }

    private fun updateTopology() {
        _networkTopology.value = routingTable.toMap()
        _totalNodes.value = routingTable.size + 1 // +1 for ourselves
    }

    fun getNetworkStats(): NetworkStats {
        return NetworkStats(
            directPeers = directPeers.size,
            totalNodes = _totalNodes.value,
            routes = routingTable.size,
            avgHopCount = if (routingTable.isEmpty()) 0.0
            else routingTable.values.map { it.hopCount }.average()
        )
    }

    sealed class ProcessResult {
        data class Deliver(val message: WireMessage) : ProcessResult()
        object Forwarded : ProcessResult()
        object NoRoute : ProcessResult()
    }

    data class NetworkStats(
        val directPeers: Int,
        val totalNodes: Int,
        val routes: Int,
        val avgHopCount: Double
    )
}