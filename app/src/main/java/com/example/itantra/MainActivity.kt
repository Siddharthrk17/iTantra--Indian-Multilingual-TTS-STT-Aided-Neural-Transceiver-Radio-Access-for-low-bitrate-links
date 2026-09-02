package com.example.itantra

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.itantra.ai.stt.SttEngine
import com.example.itantra.ai.translate.TranslationEngine
import com.example.itantra.ai.tts.TtsEngine
import com.example.itantra.deviceprofile.DeviceProfiler
import com.example.itantra.protocol.ITantraEnvelope
import com.example.itantra.protocol.ITantraMessage
import com.example.itantra.protocol.envelope.EnvelopeFactory
import com.example.itantra.storage.AppDatabase
import com.example.itantra.storage.MessageEntity
import com.example.itantra.storage.MessageRepository
import com.example.itantra.transport.nearby.NearbyTransport
import com.example.itantra.ui.theme.ITantraTheme
import com.example.itantra.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AppState {
    FIRST_RUN,
    DASHBOARD,
    CREATE_ROOM,
    INVITING_PEOPLE,
    INCOMING_INVITE,
    CONNECTING,
    CHAT_ROOM,
    SETTINGS,
    HISTORY,
    RECENT_CONTACTS
}

data class UserSettings(
    val myLanguage: String = "en",
    val ttsEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val autoDiscovery: Boolean = true
)

data class DiscoveredDevice(
    val name: String,
    val wifiDirectMac: String
)

class MainActivity : ComponentActivity() {

    private lateinit var messageRepository: MessageRepository
    private lateinit var sttEngine: SttEngine
    private lateinit var translationEngine: TranslationEngine
    private lateinit var ttsEngine: TtsEngine
    private lateinit var nearbyTransport: NearbyTransport
    
    private val prefs by lazy { getSharedPreferences("itantra_prefs", MODE_PRIVATE) }
    private var userName by mutableStateOf("")
    private var settings by mutableStateOf(UserSettings())

    // UI States
    private var appState by mutableStateOf(AppState.DASHBOARD)
    private var discoveredDevices = mutableStateOf<List<DiscoveredDevice>>(emptyList())
    private var connectedPeerName by mutableStateOf<String?>(null)
    private var currentRoomName by mutableStateOf("")
    private var inviterName by mutableStateOf("")
    
    private var isSttReady by mutableStateOf(false)
    private var isListening by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startDiscoveryFlow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        loadPrefs()
        
        val deviceProfiler = DeviceProfiler(this)
        val deviceInfo = deviceProfiler.getDeviceInfo()
        
        nearbyTransport = NearbyTransport(
            context = this,
            onDeviceFound = { name, id ->
                lifecycleScope.launch {
                    val newList = discoveredDevices.value.toMutableList()
                    if (newList.none { it.wifiDirectMac == id }) {
                        newList.add(DiscoveredDevice(name, id))
                        discoveredDevices.value = newList
                    }
                }
            },
            onMessageReceived = { bytes ->
                try {
                    val message = ITantraMessage.parseFrom(bytes)
                    handleIncomingMessage(message)
                } catch (e: Exception) {
                    Logger.e("Failed to parse Nearby message", e)
                }
            },
            onConnected = { id ->
                appState = AppState.CHAT_ROOM
                val invitePayload = "INVITE:$userName:$currentRoomName"
                val inviteMsg = EnvelopeFactory.createSystemMessage(userName, invitePayload)
                nearbyTransport.sendMessage(inviteMsg.toByteArray())
            },
            onDisconnected = {
                appState = AppState.DASHBOARD
            }
        )
        
        sttEngine = SttEngine(this)
        sttEngine.initModel("model-en-us.zip") { success ->
            isSttReady = success
        }
        translationEngine = TranslationEngine(this)
        ttsEngine = TtsEngine(this)

        val database = AppDatabase.getDatabase(this)
        messageRepository = MessageRepository(database.messageDao())
        
        if (userName.isBlank()) {
            appState = AppState.FIRST_RUN
        }
        
        setContent {
            ITantraTheme(darkTheme = true) {
                val dbMessages by messageRepository.allMessages.collectAsState(initial = emptyList())
                
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize().background(Color(0xFF121212))) {
                        when (appState) {
                            AppState.FIRST_RUN -> NameEntryScreen(onComplete = { name ->
                                saveUserName(name)
                                appState = AppState.DASHBOARD
                                startDiscoveryFlow()
                            })
                            AppState.DASHBOARD -> DashboardScreen(
                                onCreateRoom = { appState = AppState.CREATE_ROOM },
                                onSettings = { appState = AppState.SETTINGS },
                                onHistory = { appState = AppState.HISTORY },
                                onRecentContacts = { appState = AppState.RECENT_CONTACTS }
                            )
                            AppState.SETTINGS -> SettingsScreen(
                                settings = settings,
                                currentName = userName,
                                onUpdate = { updated ->
                                    settings = updated
                                    saveSettings(updated)
                                },
                                onNameChange = { newName ->
                                    saveUserName(newName)
                                    nearbyTransport.stopAll()
                                    startDiscoveryFlow()
                                },
                                onDeleteHistory = {
                                    lifecycleScope.launch {
                                        messageRepository.deleteAllMessages()
                                        Toast.makeText(this@MainActivity, "History Cleared", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onTestVoice = {
                                    ttsEngine.speak("This is a test of the iTantra voice output system. If you can hear this, your speaker is working.", settings.myLanguage)
                                },
                                onBack = { appState = AppState.DASHBOARD }
                            )
                            AppState.HISTORY -> HistoryScreen(
                                messages = dbMessages,
                                onBack = { appState = AppState.DASHBOARD }
                            )
                            AppState.RECENT_CONTACTS -> RecentPeersScreen(
                                messages = dbMessages,
                                onBack = { appState = AppState.DASHBOARD }
                            )
                            AppState.CREATE_ROOM -> CreateRoomScreen(
                                onRoomNameSet = { name ->
                                    currentRoomName = name
                                    appState = AppState.INVITING_PEOPLE
                                },
                                onBack = { appState = AppState.DASHBOARD }
                            )
                            AppState.INVITING_PEOPLE -> DiscoveryScreen(
                                devices = discoveredDevices.value,
                                roomName = currentRoomName,
                                localMac = "READY",
                                onConnect = { device ->
                                    connectedPeerName = device.name
                                    appState = AppState.CONNECTING
                                    nearbyTransport.connect(userName, device.wifiDirectMac)
                                },
                                onBack = { appState = AppState.CREATE_ROOM }
                            )
                            AppState.INCOMING_INVITE -> IncomingInviteScreen(
                                inviterName = inviterName,
                                roomName = currentRoomName,
                                onAccept = {
                                    appState = AppState.CHAT_ROOM
                                    val acceptMsg = EnvelopeFactory.createSystemMessage(userName, "INVITE_ACCEPTED")
                                    nearbyTransport.sendMessage(acceptMsg.toByteArray())
                                },
                                onReject = { 
                                    nearbyTransport.stopAll()
                                    appState = AppState.DASHBOARD 
                                }
                            )
                            AppState.CONNECTING -> ConnectingScreen(
                                peerName = connectedPeerName ?: "Device"
                            )
                            AppState.CHAT_ROOM -> ZelloChatScreen(
                                roomName = currentRoomName,
                                peerName = connectedPeerName ?: "Device",
                                messages = dbMessages.filter { it.senderId == connectedPeerName || it.isOutgoing },
                                isListening = isListening,
                                onStartListen = { startStt() },
                                onStopListen = { stopStt() },
                                onSendMessage = { text -> sendTextMessage(text) },
                                onExit = { appState = AppState.DASHBOARD }
                            )
                        }
                    }
                }
            }
        }

        if (userName.isNotBlank()) {
            checkAndRequestPermissions()
        }
    }

    private fun loadPrefs() {
        userName = prefs.getString("user_name", "") ?: ""
        settings = UserSettings(
            myLanguage = prefs.getString("my_language", "en") ?: "en",
            ttsEnabled = prefs.getBoolean("tts_enabled", true),
            hapticEnabled = prefs.getBoolean("haptic_enabled", true),
            autoDiscovery = prefs.getBoolean("auto_discovery", true)
        )
    }

    private fun saveUserName(name: String) {
        userName = name
        prefs.edit().putString("user_name", name).apply()
    }

    private fun saveSettings(updated: UserSettings) {
        prefs.edit().apply {
            putString("my_language", updated.myLanguage)
            putBoolean("tts_enabled", updated.ttsEnabled)
            putBoolean("haptic_enabled", updated.hapticEnabled)
            putBoolean("auto_discovery", updated.autoDiscovery)
        }.apply()
    }

    private fun startDiscoveryFlow() {
        nearbyTransport.startAdvertising(userName)
        nearbyTransport.startDiscovery()
    }

    private fun startStt() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isSttReady) {
            Toast.makeText(this, "Voice model loading...", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isListening) {
            isListening = true
            sttEngine.startListening { result ->
                if (result.isNotBlank()) sendTextMessage(result)
            }
        }
    }

    private fun stopStt() {
        if (isListening) {
            sttEngine.stopListening()
            isListening = false
        }
    }

    private fun sendTextMessage(text: String) {
        lifecycleScope.launch {
            val detectedLang = translationEngine.identifyLanguage(text)
            val message = EnvelopeFactory.createTextMessage(
                senderId = userName,
                payload = text,
                originalLang = detectedLang,
                targetLang = "any"
            )
            messageRepository.saveOutgoingEnvelope(message.envelope)
            nearbyTransport.sendMessage(message.toByteArray())
        }
    }

    private fun handleIncomingMessage(message: ITantraMessage) {
        when (message.contentCase) {
            ITantraMessage.ContentCase.ENVELOPE -> {
                val env = message.envelope
                if (env.type == ITantraEnvelope.MessageType.SYSTEM) {
                    val payload = env.payload
                    if (payload.startsWith("INVITE:")) {
                        val parts = payload.split(":")
                        if (parts.size >= 3) {
                            inviterName = parts[1]
                            currentRoomName = parts[2]
                            connectedPeerName = inviterName
                            if (appState != AppState.CHAT_ROOM) appState = AppState.INCOMING_INVITE
                        }
                    } else if (payload == "INVITE_ACCEPTED") {
                        appState = AppState.CHAT_ROOM
                    }
                } else if (env.type == ITantraEnvelope.MessageType.TEXT) {
                    lifecycleScope.launch {
                        messageRepository.saveIncomingEnvelope(env)
                        val translated = translationEngine.translate(env.payload, env.originalLanguage, settings.myLanguage)
                        if (settings.ttsEnabled) {
                            ttsEngine.speak(translated, settings.myLanguage)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nearbyTransport.stopAll()
        translationEngine.close()
        ttsEngine.close()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissions.add(Manifest.permission.RECORD_AUDIO)

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (toRequest.isNotEmpty()) requestPermissionLauncher.launch(toRequest)
        else startDiscoveryFlow()
    }
}

@Composable
fun NameEntryScreen(onComplete: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Welcome to iTantra", style = MaterialTheme.typography.displaySmall, color = Color.Yellow, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(32.dp))
        Text("Please enter your name", color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF2C2C2C), unfocusedContainerColor = Color(0xFF2C2C2C), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { if (name.isNotBlank()) onComplete(name) }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow)) {
            Text("Get Started", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DashboardScreen(onCreateRoom: () -> Unit, onSettings: () -> Unit, onHistory: () -> Unit, onRecentContacts: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("iTantra", style = MaterialTheme.typography.displayMedium, color = Color.Yellow, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(48.dp))
        DashboardCard(title = "Create a Room", icon = Icons.Default.Add, onClick = onCreateRoom)
        Spacer(modifier = Modifier.height(16.dp))
        DashboardCard(title = "Recent Contacts", icon = Icons.Default.People, onClick = onRecentContacts)
        Spacer(modifier = Modifier.height(16.dp))
        DashboardCard(title = "Chat History", icon = Icons.Default.History, onClick = onHistory)
        Spacer(modifier = Modifier.height(16.dp))
        DashboardCard(title = "Settings", icon = Icons.Default.Settings, onClick = onSettings)
    }
}

@Composable
fun HistoryScreen(messages: List<MessageEntity>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Chat History", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp)) {
            items(messages.reversed()) { msg ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C))) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = if (msg.isOutgoing) "You" else msg.senderId, color = Color.Yellow, style = MaterialTheme.typography.labelSmall)
                        Text(text = msg.payload, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun RecentPeersScreen(messages: List<MessageEntity>, onBack: () -> Unit) {
    val peers = messages.filter { !it.isOutgoing }.map { it.senderId }.distinct()
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Recent Contacts", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp)) {
            items(peers) { peer ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C))) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(40.dp).background(Color.Yellow, CircleShape), contentAlignment = Alignment.Center) {
                            Text(peer.take(1), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        Text(peer, modifier = Modifier.padding(start = 16.dp), color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: UserSettings,
    currentName: String,
    onUpdate: (UserSettings) -> Unit,
    onNameChange: (String) -> Unit,
    onDeleteHistory: () -> Unit,
    onTestVoice: () -> Unit,
    onBack: () -> Unit
) {
    var editName by remember { mutableStateOf(currentName) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).background(Color(0xFF121212))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Your Name", color = Color.Yellow, style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = editName,
                onValueChange = { editName = it },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF2C2C2C), unfocusedContainerColor = Color(0xFF2C2C2C), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
            )
            IconButton(onClick = { onNameChange(editName) }) {
                Icon(Icons.Default.Save, "Save Name", tint = Color.Yellow)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Preferred Language", color = Color.Yellow)
        val langs = listOf("en" to "English", "hi" to "Hindi", "es" to "Spanish", "fr" to "French")
        langs.forEach { (code, name) ->
            Row(modifier = Modifier.fillMaxWidth().clickable { onUpdate(settings.copy(myLanguage = code)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.myLanguage == code, onClick = { onUpdate(settings.copy(myLanguage = code)) })
                Text(name, color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color.DarkGray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Voice Output (TTS)", color = Color.White)
            Switch(checked = settings.ttsEnabled, onCheckedChange = { onUpdate(settings.copy(ttsEnabled = it)) })
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onTestVoice,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.Yellow)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Test Voice Output", color = Color.White)
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f))
        ) {
            Text("Clear All Chat History", color = Color.White)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete History?") },
            text = { Text("This will permanently remove all your messages.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteHistory()
                    showDeleteDialog = false
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DashboardCard(title: String, icon: ImageVector, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().height(80.dp).clickable { onClick() }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C))) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.Yellow, modifier = Modifier.size(28.dp))
            Text(text = title, modifier = Modifier.padding(start = 24.dp), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}

@Composable
fun CreateRoomScreen(onRoomNameSet: (String) -> Unit, onBack: () -> Unit) {
    var roomName by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Text("Create Room", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        TextField(value = roomName, onValueChange = { roomName = it }, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF2C2C2C), unfocusedContainerColor = Color(0xFF2C2C2C), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { if (roomName.isNotBlank()) onRoomNameSet(roomName) }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow)) {
            Text("Find Peers", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DiscoveryScreen(devices: List<DiscoveredDevice>, roomName: String, localMac: String?, onConnect: (DiscoveredDevice) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Invite to $roomName", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Spacer(modifier = Modifier.height(16.dp))
        RadarAnimation()
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp)) {
            items(devices) { device ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onConnect(device) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C))) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun IncomingInviteScreen(inviterName: String, roomName: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$inviterName invites you to $roomName", color = Color.White, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(48.dp))
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Green)) { Text("Join", color = Color.Black) }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onReject, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)) { Text("Decline") }
    }
}

@Composable
fun ZelloChatScreen(roomName: String, peerName: String, messages: List<MessageEntity>, isListening: Boolean, onStartListen: () -> Unit, onStopListen: () -> Unit, onSendMessage: (String) -> Unit, onExit: () -> Unit) {
    var textInput by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Text(roomName, modifier = Modifier.padding(start = 8.dp), color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(messages) { msg ->
                val isMe = msg.isOutgoing
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart) {
                    Card(colors = CardDefaults.cardColors(containerColor = if (isMe) Color.Yellow else Color(0xFF2C2C2C))) {
                        Text(msg.payload, modifier = Modifier.padding(8.dp), color = if (isMe) Color.Black else Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            PTTButton(isListening = isListening, onStart = onStartListen, onStop = onStopListen)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(value = textInput, onValueChange = { textInput = it }, modifier = Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF1A1A1A), unfocusedContainerColor = Color(0xFF1A1A1A), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            IconButton(onClick = { if (textInput.isNotBlank()) { onSendMessage(textInput); textInput = "" } }) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.Yellow) }
        }
    }
}

@Composable
fun PTTButton(isListening: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    val active = isListening || isPressed
    val transition = updateTransition(targetState = active, label = "PTT")
    val buttonColor by transition.animateColor(label = "Color") { if (it) Color.Red else Color(0xFF333333) }
    val buttonScale by animateFloatAsState(if (active) 0.8f else 1f, label = "Scale")
    
    Box(contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(150.dp).scale(buttonScale).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStart()
                    try { awaitRelease() } finally {
                        isPressed = false
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onStop()
                    }
                })
            },
            shape = CircleShape, color = buttonColor, border = BorderStroke(4.dp, Color.DarkGray)
        ) {
            Icon(Icons.Default.Mic, null, modifier = Modifier.padding(40.dp), tint = if (active) Color.White else Color.Yellow)
        }
    }
}

@Composable
fun RadarAnimation() {
    val transition = rememberInfiniteTransition()
    val scale by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2000)))
    val alpha by transition.animateFloat(1f, 0f, infiniteRepeatable(tween(2000)))
    Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(200.dp).scale(scale).background(Color.Yellow.copy(alpha), CircleShape))
        Box(modifier = Modifier.size(40.dp).background(Color.Yellow, CircleShape))
    }
}

@Composable
fun ConnectingScreen(peerName: String) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color.Yellow)
        Text("Connecting to $peerName", color = Color.White, modifier = Modifier.padding(top = 16.dp))
    }
}
