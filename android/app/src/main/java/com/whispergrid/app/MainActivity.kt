package com.whispergrid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.whispergrid.app.network.ConnectionManager
import com.whispergrid.app.network.PermissionsHelper
import com.whispergrid.app.network.Peer
import com.whispergrid.app.network.RoutingManager
import com.whispergrid.app.network.WiFiDirectManager
import com.whispergrid.app.ui.theme.WhisperGridTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import com.whispergrid.app.ai.AIMessageProcessor
import com.whispergrid.app.ai.OllamaService
import com.whispergrid.app.ai.TrustLevel
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var wifiDirectManager: WiFiDirectManager
    private lateinit var connectionManager: ConnectionManager
    private lateinit var routingManager: RoutingManager
    private lateinit var ollamaService: OllamaService
    private lateinit var aiProcessor: AIMessageProcessor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        wifiDirectManager = WiFiDirectManager(this)
        connectionManager = ConnectionManager()

        // Initialize routing manager
        val deviceId = connectionManager.getDeviceId()
        val deviceName = connectionManager.getDeviceName()
        routingManager = RoutingManager(deviceId, deviceName)

        // Initialize AI services
        ollamaService = OllamaService()
        aiProcessor = AIMessageProcessor(ollamaService)

        // Connect routing manager to connection manager
        connectionManager.setRoutingManager(routingManager)

        // Handle connection events
        lifecycleScope.launch {
            wifiDirectManager.connectionInfo.collect { info ->
                info?.let {
                    connectionManager.handleP2pConnection(it)

                    // Add to routing table when connected
                    if (it.groupFormed && it.groupOwnerAddress != null) {
                        val peerId = it.groupOwnerAddress.hostAddress ?: "unknown"
                        routingManager.addDirectPeer(
                            peerId = peerId,
                            peerName = "Peer_${peerId.takeLast(4)}",
                            connectionManager = connectionManager
                        )
                    }
                }
            }
        }

        setContent {
            WhisperGridTheme {
                MainScreen(wifiDirectManager, connectionManager, routingManager, aiProcessor)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wifiDirectManager.registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        wifiDirectManager.unregisterReceiver()
    }
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean = false
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    wifiDirectManager: WiFiDirectManager,
    connectionManager: ConnectionManager,
    routingManager: RoutingManager,
    aiProcessor: AIMessageProcessor
) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = PermissionsHelper.getRequiredPermissions().toList()
    )

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    ChatScreen(wifiDirectManager, connectionManager, routingManager, aiProcessor)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    wifiDirectManager: WiFiDirectManager,
    connectionManager: ConnectionManager,
    routingManager: RoutingManager,
    aiProcessor: AIMessageProcessor
) {
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var messageText by remember { mutableStateOf("") }

    val peers by wifiDirectManager.peers.collectAsState()
    val isDiscovering by wifiDirectManager.isDiscovering.collectAsState()
    val connectionState by connectionManager.connectionState.collectAsState()
    val receivedMessages by connectionManager.receivedMessages.collectAsState()
    val networkStats by routingManager.totalNodes.collectAsState()
    val isOllamaAvailable by aiProcessor.isOllamaAvailable.collectAsState()
    val translationEnabled by aiProcessor.translationEnabled.collectAsState()

    var showPeerList by remember { mutableStateOf(false) }
    var showAISettings by remember { mutableStateOf(false) }

    // Handle received messages with AI processing
    LaunchedEffect(receivedMessages) {
        receivedMessages.forEach { wireMessage ->
            if (messages.none { it.id == wireMessage.id }) {
                // Process message through AI
                val processed = aiProcessor.processMessage(wireMessage.content)

                // Use translated text if available, otherwise original
                val displayText = processed.translatedText ?: wireMessage.content

                // Add warning if misinformation detected
                val finalText = when (processed.misinformationCheck?.trustLevel) {
                    com.whispergrid.app.ai.TrustLevel.SUSPICIOUS ->
                        "⚠️ [Suspicious] $displayText"
                    com.whispergrid.app.ai.TrustLevel.QUESTIONABLE ->
                        "⚠️ [Verify] $displayText"
                    else -> displayText
                }

                messages = messages + Message(
                    id = wireMessage.id,
                    sender = wireMessage.senderName,
                    content = finalText,
                    timestamp = wireMessage.timestamp,
                    isFromMe = false
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Whisper Grid", fontWeight = FontWeight.Bold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                when {
                                    connectionState is com.whispergrid.app.network.ConnectionState.Connected ->
                                        "Mesh Network • $networkStats nodes"
                                    isDiscovering -> "Discovering • ${peers.size} peers"
                                    else -> "Offline • ${peers.size} nearby"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    connectionState is com.whispergrid.app.network.ConnectionState.Connected ->
                                        MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )

                            if (isOllamaAvailable) {
                                Text(
                                    "• AI",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                actions = {
                    // AI Settings button
                    IconButton(
                        onClick = { showAISettings = !showAISettings }
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = "AI Settings",
                            tint = if (isOllamaAvailable)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = { wifiDirectManager.startDiscovery() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Discover Peers",
                            tint = if (isDiscovering)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = { showPeerList = !showPeerList }) {
                        Badge(
                            containerColor = if (peers.isNotEmpty())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text("${peers.size}")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            MessageComposer(
                text = messageText,
                onTextChange = { messageText = it },
                onSend = {
                    if (messageText.isNotBlank()) {
                        messages = messages + Message(
                            sender = "You",
                            content = messageText,
                            isFromMe = true
                        )

                        connectionManager.sendTextMessage(messageText)

                        messageText = ""
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 8.dp),
                reverseLayout = true
            ) {
                items(messages.reversed()) { message ->
                    MessageBubble(message)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (showPeerList) {
                PeerListSheet(
                    peers = peers,
                    onDismiss = { showPeerList = false },
                    onConnect = { peer ->
                        wifiDirectManager.connect(peer) { success ->
                            if (success) {
                                messages = messages + Message(
                                    sender = "System",
                                    content = "Connecting to ${peer.deviceName}...",
                                    isFromMe = false
                                )
                            }
                        }
                    }
                )
            }

// Add this new section:
            if (showAISettings) {
                AISettingsDialog(
                    isOllamaAvailable = isOllamaAvailable,
                    translationEnabled = translationEnabled,
                    targetLanguage = aiProcessor.targetLanguage.collectAsState().value,
                    onTranslationToggle = { enabled ->
                        aiProcessor.setTranslationEnabled(enabled)
                    },
                    onLanguageChange = { language ->
                        aiProcessor.setTargetLanguage(language)
                    },
                    onDismiss = { showAISettings = false }
                )
            }
        }
    }
}

@Composable
fun PeerListSheet(
    peers: List<Peer>,
    onDismiss: () -> Unit,
    onConnect: (Peer) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Nearby Devices",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (peers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No devices found. Tap refresh to discover peers.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(peers) { peer ->
                        PeerItem(peer = peer, onClick = { onConnect(peer) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PeerItem(
    peer: Peer,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    peer.deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    peer.status.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                "Connect",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isFromMe)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.isFromMe) {
                    Text(
                        message.sender,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MessageComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        shadowElevation = 8.dp,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                maxLines = 3,
                singleLine = false
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend()
                    }
                },
                enabled = text.isNotBlank()
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
@Composable
fun AISettingsDialog(
    isOllamaAvailable: Boolean,
    translationEnabled: Boolean,
    targetLanguage: String,
    onTranslationToggle: (Boolean) -> Unit,
    onLanguageChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (isOllamaAvailable)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                Text("AI Settings")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Ollama status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Gemma AI Status:")
                    Text(
                        if (isOllamaAvailable) "Connected" else "Offline",
                        color = if (isOllamaAvailable)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }

                Divider()

                // Translation toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Auto-translate messages",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Translate incoming messages to your language",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = translationEnabled,
                        onCheckedChange = onTranslationToggle,
                        enabled = isOllamaAvailable
                    )
                }

                // Language selection
                if (translationEnabled) {
                    Column {
                        Text(
                            "Translate to:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val languages = listOf(
                            "English", "Spanish", "French", "German", "Italian",
                            "Portuguese", "Chinese", "Japanese", "Korean", "Arabic",
                            "Hindi", "Russian", "Turkish", "Vietnamese", "Polish"
                        )

                        var expanded by remember { mutableStateOf(false) }

                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(targetLanguage)
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                if (expanded) Icons.Default.Refresh else Icons.Default.Send,
                                contentDescription = null
                            )
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            languages.forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(language) },
                                    onClick = {
                                        onLanguageChange(language)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (!isOllamaAvailable) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Ollama Not Connected",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Make sure Ollama is running with Gemma 2 model.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}