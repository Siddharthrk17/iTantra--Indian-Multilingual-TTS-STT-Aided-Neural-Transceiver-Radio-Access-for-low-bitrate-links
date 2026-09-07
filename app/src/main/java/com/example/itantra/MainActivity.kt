package com.example.itantra

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
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

val DeepBlack = Color(0xFF080808)
val Charcoal = Color(0xFF121212)
val ISROGold = Color(0xFFFFD700)
val RocketOrange = Color(0xFFFF4500)
val GlassWhite = Color.White.copy(alpha = 0.05f)
val GlassBorder = Color.White.copy(alpha = 0.1f)

@Composable
fun MovingGradientBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "gradient")
    val xOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2000f,
        animationSpec = infiniteRepeatable(
            animation = tween(30000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "xOffset"
    )
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(DeepBlack, Charcoal, Color(0xFF000510)),
                start = Offset(xOffset - 1000f, 0f),
                end = Offset(xOffset, size.height)
            )
        )
    }
}

enum class AppState {
    SPLASH,
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
    private var appState by mutableStateOf(AppState.SPLASH)
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
                // Ensure model is preloaded on connection
                lifecycleScope.launch {
                    translationEngine.preloadLanguageModel(settings.myLanguage)
                }
                val invitePayload = "INVITE:$userName:$currentRoomName"
                val inviteMsg = EnvelopeFactory.createSystemMessage(userName, invitePayload)
                nearbyTransport.sendMessage(inviteMsg.toByteArray())
            },
            onDisconnected = {
                appState = AppState.DASHBOARD
                Toast.makeText(this@MainActivity, "Link Disconnected. Re-scanning...", Toast.LENGTH_SHORT).show()
                startDiscoveryFlow()
            }
        )
        
        sttEngine = SttEngine(this)
        sttEngine.initModel("model-en-us.zip") { success ->
            isSttReady = success
        }
        translationEngine = TranslationEngine(this)
        ttsEngine = TtsEngine(this)

        // Preload ML Kit models for user's selected language
        lifecycleScope.launch {
            translationEngine.preloadLanguageModel(settings.myLanguage)
        }

        val database = AppDatabase.getDatabase(this)
        messageRepository = MessageRepository(database.messageDao())
        
        setContent {
            ITantraTheme(darkTheme = true) {
                val dbMessages by messageRepository.allMessages.collectAsState(initial = emptyList())
                
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize().background(DeepBlack)) {
                        when (appState) {
                            AppState.SPLASH -> SplashScreen(onFinished = {
                                appState = if (userName.isBlank()) AppState.FIRST_RUN else AppState.DASHBOARD
                                if (userName.isNotBlank()) checkAndRequestPermissions()
                            })
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
                                onDownloadModel = {
                                    lifecycleScope.launch {
                                        Toast.makeText(this@MainActivity, "Downloading offline model for ${settings.myLanguage.uppercase()}...", Toast.LENGTH_SHORT).show()
                                        val success = translationEngine.preloadLanguageModel(settings.myLanguage)
                                        if (success) {
                                            Toast.makeText(this@MainActivity, "Model Downloaded & Ready for Offline Use!", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "Download failed. Check internet connection.", Toast.LENGTH_LONG).show()
                                        }
                                    }
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
                            AppState.CONNECTING -> ConnectingScreen(
                                peerName = connectedPeerName ?: "Device"
                            )
                            AppState.INCOMING_INVITE -> {}
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
        
        // Background preloading for chosen language
        lifecycleScope.launch {
            translationEngine.preloadLanguageModel(updated.myLanguage)
            Toast.makeText(this@MainActivity, "Language model ready for ${updated.myLanguage.uppercase()}", Toast.LENGTH_SHORT).show()
        }
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
            val senderLang = settings.myLanguage
            
            // Translate Sender's language -> English (Universal Pivot required for ML Kit 2-way support)
            val englishPivotText = translationEngine.translate(text, senderLang, "en")
            
            val message = EnvelopeFactory.createTextMessage(
                senderId = userName,
                payload = englishPivotText,
                originalLang = senderLang,
                targetLang = "en"
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
                        val receiverLang = settings.myLanguage
                        
                        // Translate from English Pivot -> Receiver's preferred language (ML Kit standard)
                        val finalPayload = translationEngine.translate(
                            text = env.payload,
                            sourceLang = "en",
                            targetLang = receiverLang
                        )
                        
                        // Check if message contains danger/emergency keywords
                        val isDanger = isEmergencyMessage(finalPayload) || isEmergencyMessage(env.payload)
                        
                        // Save translated envelope so DB, Chat UI, and History display translated text in receiver's language!
                        val translatedEnv = env.toBuilder()
                            .setPayload(if (isDanger) "⚠️ EMERGENCY: $finalPayload" else finalPayload)
                            .setTargetLanguage(receiverLang)
                            .build()
                        
                        // SPEAK INSTANTLY (0ms delay) before doing database writes
                        if (settings.ttsEnabled) {
                            ttsEngine.speak(finalPayload, receiverLang, isEmergency = isDanger)
                        }
                        
                        messageRepository.saveIncomingEnvelope(translatedEnv)
                        
                        if (isDanger) {
                            Logger.e("EMERGENCY BROADCAST RECEIVED: $finalPayload")
                            Toast.makeText(this@MainActivity, "⚠️ EMERGENCY ALERT: $finalPayload", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, finalPayload, Toast.LENGTH_SHORT).show()
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
fun SplashScreen(onFinished: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "breath")
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val breathAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    LaunchedEffect(Unit) {
        delay(4000)
        onFinished()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MovingGradientBackground()
        
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Breathing glow
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(breathScale * 1.2f)
                        .background(ISROGold.copy(alpha = 0.1f * breathAlpha), CircleShape)
                )
                Image(
                    painter = painterResource(id = R.drawable.isro_logo),
                    contentDescription = "ISRO Logo",
                    modifier = Modifier
                        .size(160.dp)
                        .scale(breathScale)
                        .alpha(breathAlpha)
                )
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                text = "iTantra",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.ExtraLight,
                    letterSpacing = 16.sp,
                    color = ISROGold
                )
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "COMM-LINK INTERFACE",
                style = MaterialTheme.typography.labelLarge.copy(
                    letterSpacing = 6.sp,
                    color = RocketOrange.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
fun NameEntryScreen(onComplete: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize().background(DeepBlack)) {
        MovingGradientBackground()
        Column(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                "Welcome to the\nFuture of Communication",
                style = MaterialTheme.typography.displayMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 48.sp
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Initialize your identity to secure the link.",
                style = MaterialTheme.typography.bodyLarge.copy(color = Color.Gray)
            )
            Spacer(modifier = Modifier.height(64.dp))
            
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("COMMANDER NAME", color = ISROGold.copy(alpha = 0.5f), letterSpacing = 2.sp) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.titleLarge.copy(color = Color.White, letterSpacing = 2.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ISROGold,
                    unfocusedBorderColor = Color.DarkGray,
                    cursorColor = ISROGold,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { if (name.isNotBlank()) onComplete(name) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ISROGold),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("ESTABLISH CONNECTION", color = DeepBlack, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun DashboardScreen(onCreateRoom: () -> Unit, onSettings: () -> Unit, onHistory: () -> Unit, onRecentContacts: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(DeepBlack)) {
        MovingGradientBackground()
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp)
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                "COMMAND CENTER",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = RocketOrange,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(Color.Green, CircleShape))
                Text(
                    " SYSTEM OPERATIONAL",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall.copy(color = Color.Green.copy(alpha = 0.7f), letterSpacing = 1.sp)
                )
            }
            
            Spacer(modifier = Modifier.height(64.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    FeatureCard(
                        title = "NEW LINK",
                        desc = "Create mission room",
                        icon = Icons.Default.Add,
                        modifier = Modifier.weight(1f),
                        color = ISROGold,
                        onClick = onCreateRoom
                    )
                    FeatureCard(
                        title = "PEERS",
                        desc = "Recent contacts",
                        icon = Icons.Default.People,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        onClick = onRecentContacts
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    FeatureCard(
                        title = "ARCHIVES",
                        desc = "Mission history",
                        icon = Icons.Default.History,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        onClick = onHistory
                    )
                    FeatureCard(
                        title = "SETTINGS",
                        desc = "System config",
                        icon = Icons.Default.Settings,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        onClick = onSettings
                    )
                }
            }
        }
    }
}

@Composable
fun FeatureCard(title: String, desc: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(180.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GlassWhite)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(24.dp)
    ) {
        Column {
            Icon(icon, null, tint = color, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.titleMedium.copy(color = color, fontWeight = FontWeight.Black, letterSpacing = 2.sp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.labelSmall.copy(color = Color.Gray, letterSpacing = 1.sp))
        }
    }
}

@Composable
fun HistoryScreen(messages: List<MessageEntity>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(DeepBlack).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.background(GlassWhite, CircleShape)) { 
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) 
            }
            Text("MISSION ARCHIVES", modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.headlineSmall.copy(color = ISROGold, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
        }
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 24.dp)) {
            items(messages.reversed()) { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassWhite)
                        .border(0.5.dp, GlassBorder, RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(text = (if (msg.isOutgoing) "COMMANDER" else msg.senderId).uppercase(), color = RocketOrange, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = msg.payload, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun RecentPeersScreen(messages: List<MessageEntity>, onBack: () -> Unit) {
    val peers = messages.filter { !it.isOutgoing }.map { it.senderId }.distinct()
    Column(modifier = Modifier.fillMaxSize().background(DeepBlack).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.background(GlassWhite, CircleShape)) { 
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) 
            }
            Text("IDENTIFIED PEERS", modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.headlineSmall.copy(color = ISROGold, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
        }
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 24.dp)) {
            items(peers) { peer ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(GlassWhite)
                        .border(0.5.dp, GlassBorder, RoundedCornerShape(16.dp))
                        .clickable { /* Could initiate connection */ }
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(48.dp).background(ISROGold, CircleShape), contentAlignment = Alignment.Center) {
                            Text(peer.take(1).uppercase(), color = DeepBlack, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        }
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(peer.uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                            Text("Last contacted recently", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
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
    onDownloadModel: () -> Unit,
    onBack: () -> Unit
) {
    var editName by remember { mutableStateOf(currentName) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(DeepBlack).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.background(GlassWhite, CircleShape)) { 
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) 
            }
            Text("SYSTEM CONFIG", modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.headlineSmall.copy(color = ISROGold, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text("COMMANDER IDENTITY", color = RocketOrange, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = editName,
                onValueChange = { editName = it },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Charcoal,
                    unfocusedContainerColor = Charcoal,
                    focusedBorderColor = ISROGold,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(onClick = { onNameChange(editName) }, modifier = Modifier.background(ISROGold, RoundedCornerShape(8.dp))) {
                Icon(Icons.Default.Save, "Save", tint = DeepBlack)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("LINGUISTIC INTERFACE (MY LANGUAGE)", color = RocketOrange, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
        Spacer(modifier = Modifier.height(8.dp))
        val langs = listOf(
            "en" to "English",
            "ta" to "Tamil (தமிழ்)",
            "hi" to "Hindi (हिंदी)",
            "te" to "Telugu (తెలుగు)",
            "mr" to "Marathi (मराठी)",
            "bn" to "Bengali (বাংলা)",
            "gu" to "Gujarati (ગુજરાતી)",
            "kn" to "Kannada (கன்னட/കന്നഡ)",
            "ml" to "Malayalam (മലയാളം)",
            "pa" to "Punjabi (ਪੰਜਾਬੀ)",
            "ur" to "Urdu (اردو)",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German"
        )
        LazyColumn(modifier = Modifier.height(160.dp)) {
            items(langs) { (code, name) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (settings.myLanguage == code) GlassWhite else Color.Transparent)
                        .clickable { onUpdate(settings.copy(myLanguage = code)) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.myLanguage == code,
                        onClick = { onUpdate(settings.copy(myLanguage = code)) },
                        colors = RadioButtonDefaults.colors(selectedColor = ISROGold, unselectedColor = Color.Gray)
                    )
                    Text(name, color = Color.White, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onDownloadModel,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ISROGold.copy(alpha = 0.2f)),
            border = BorderStroke(1.dp, ISROGold)
        ) {
            Icon(Icons.Default.Download, null, tint = ISROGold)
            Spacer(modifier = Modifier.width(8.dp))
            Text("DOWNLOAD OFFLINE TRANSLATION MODEL", color = ISROGold, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlassWhite)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Voice Output (TTS)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Synthesize incoming messages", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
            Switch(
                checked = settings.ttsEnabled,
                onCheckedChange = { onUpdate(settings.copy(ttsEnabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = ISROGold, checkedTrackColor = ISROGold.copy(alpha = 0.5f))
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onTestVoice,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Charcoal),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color.DarkGray)
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = ISROGold)
            Spacer(modifier = Modifier.width(12.dp))
            Text("TEST AUDIO SYSTEM", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }

        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("PURGE ARCHIVES", color = Color.Red.copy(alpha = 0.8f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Charcoal,
            titleContentColor = ISROGold,
            textContentColor = Color.White,
            title = { Text("CONFIRM PURGE") },
            text = { Text("This will permanently remove all mission data from local storage.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteHistory()
                    showDeleteDialog = false
                }) { Text("PURGE", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
fun CreateRoomScreen(onRoomNameSet: (String) -> Unit, onBack: () -> Unit) {
    var roomName by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize().background(DeepBlack)) {
        MovingGradientBackground()
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.background(GlassWhite, CircleShape)) { 
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) 
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text("INITIALIZE MISSION", style = MaterialTheme.typography.displaySmall.copy(color = ISROGold, fontWeight = FontWeight.Bold))
            Text("Define the room designation to begin discovery.", color = Color.Gray)
            
            Spacer(modifier = Modifier.height(48.dp))
            OutlinedTextField(
                value = roomName,
                onValueChange = { roomName = it },
                label = { Text("ROOM DESIGNATION", color = ISROGold.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.titleLarge.copy(color = Color.White, letterSpacing = 2.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ISROGold,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(40.dp))
            Button(
                onClick = { if (roomName.isNotBlank()) onRoomNameSet(roomName) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ISROGold),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("SCAN FOR PEERS", color = DeepBlack, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun DiscoveryScreen(devices: List<DiscoveredDevice>, roomName: String, localMac: String?, onConnect: (DiscoveredDevice) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(DeepBlack).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.background(GlassWhite, CircleShape)) { 
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) 
            }
            Text("SCANNING FOR $roomName", modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleLarge.copy(color = ISROGold, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
        }
        Spacer(modifier = Modifier.height(40.dp))
        RadarAnimation()
        Spacer(modifier = Modifier.height(40.dp))
        Text("AVAILABLE TRANSPONDERS", style = MaterialTheme.typography.labelSmall.copy(color = Color.Gray, letterSpacing = 2.sp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp)) {
            items(devices) { device ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(GlassWhite)
                        .border(1.dp, ISROGold.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .clickable { onConnect(device) }
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SettingsInputAntenna, null, tint = ISROGold)
                        Text(device.name.uppercase(), modifier = Modifier.padding(start = 16.dp), color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                        Spacer(modifier = Modifier.weight(1f))
                        Text("CONNECT", color = RocketOrange, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

@Composable
fun IncomingInviteScreen(inviterName: String, roomName: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(DeepBlack)) {
        MovingGradientBackground()
        Column(modifier = Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(RocketOrange.copy(alpha = 0.1f), CircleShape)
                    .border(2.dp, RocketOrange, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Security, null, tint = RocketOrange, modifier = Modifier.size(48.dp))
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                "INCOMING CONNECTION",
                style = MaterialTheme.typography.headlineSmall.copy(color = RocketOrange, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "$inviterName is requesting a secure link for $roomName",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(64.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Green.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(12.dp)
            ) { Text("ESTABLISH LINK", color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 2.sp) }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                border = BorderStroke(2.dp, Color.Red),
                shape = RoundedCornerShape(12.dp)
            ) { Text("TERMINATE", fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
        }
    }
}

@Composable
fun ZelloChatScreen(roomName: String, peerName: String, messages: List<MessageEntity>, isListening: Boolean, onStartListen: () -> Unit, onStopListen: () -> Unit, onSendMessage: (String) -> Unit, onExit: () -> Unit) {
    var textInput by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize().background(DeepBlack)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Charcoal.copy(alpha = 0.5f))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onExit, modifier = Modifier.background(GlassWhite, CircleShape)) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(roomName.uppercase(), style = MaterialTheme.typography.titleLarge.copy(color = ISROGold, fontWeight = FontWeight.Black, letterSpacing = 2.sp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(Color.Green, CircleShape))
                        Text(" PEER: ${peerName.uppercase()}", style = MaterialTheme.typography.labelSmall.copy(color = Color.Green.copy(alpha = 0.7f), letterSpacing = 1.sp))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Default.Lock, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
            
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
                contentPadding = PaddingValues(vertical = 24.dp)
            ) {
                items(messages) { msg ->
                    ChatBubble(msg)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            
            // Interaction Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(Charcoal)
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isListening) {
                        WaveformAnimation()
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PTTButton(isListening = isListening, onStart = onStartListen, onStop = onStopListen)
                        Spacer(modifier = Modifier.width(20.dp))
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Secure transmission...", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DeepBlack,
                                unfocusedContainerColor = DeepBlack,
                                focusedBorderColor = RocketOrange,
                                unfocusedBorderColor = Color.DarkGray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp),
                            trailingIcon = {
                                IconButton(onClick = { if (textInput.isNotBlank()) { onSendMessage(textInput); textInput = "" } }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = ISROGold)
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("HOLD BUTTON TO TRANSMIT VOICE", style = MaterialTheme.typography.labelSmall.copy(color = Color.Gray, letterSpacing = 1.sp))
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: MessageEntity) {
    val isMe = msg.isOutgoing
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                        bottomStart = if (isMe) 24.dp else 4.dp,
                        bottomEnd = if (isMe) 4.dp else 24.dp
                    )
                )
                .background(
                    if (isMe) Brush.linearGradient(listOf(RocketOrange, Color(0xFFCC3300)))
                    else Brush.linearGradient(listOf(Color(0xFF2C2C2C), Charcoal))
                )
                .border(
                    width = 0.5.dp,
                    color = if (isMe) ISROGold.copy(alpha = 0.3f) else GlassBorder,
                    shape = RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                        bottomStart = if (isMe) 24.dp else 4.dp,
                        bottomEnd = if (isMe) 4.dp else 24.dp
                    )
                )
                .padding(16.dp)
        ) {
            Text(msg.payload, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = (if (isMe) "COMMANDER" else msg.senderId).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.Gray,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp)
        )
    }
}

@Composable
fun PTTButton(isListening: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    val active = isListening || isPressed
    val scale by animateFloatAsState(if (active) 1.25f else 1f, animationSpec = spring(stiffness = Spring.StiffnessLow))
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (active) RocketOrange else Charcoal)
            .border(2.dp, if (active) ISROGold else Color.DarkGray, CircleShape)
            .pointerInput(Unit) {
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
            }
    ) {
        Icon(
            if (active) Icons.Default.GraphicEq else Icons.Default.Mic,
            null,
            tint = if (active) Color.White else ISROGold,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
fun WaveformAnimation() {
    val infiniteTransition = rememberInfiniteTransition()
    Row(
        modifier = Modifier.height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(20) { index ->
            val heightScale by infiniteTransition.animateFloat(
                initialValue = 0.1f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(300 + (index * 40), easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(heightScale)
                    .background(
                        brush = Brush.verticalGradient(listOf(ISROGold, RocketOrange)),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

@Composable
fun RadarAnimation() {
    val transition = rememberInfiniteTransition()
    val scale1 by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)))
    val alpha1 by transition.animateFloat(1f, 0f, infiniteRepeatable(tween(3000, easing = LinearEasing)))
    
    val scale2 by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, delayMillis = 1500, easing = LinearEasing)))
    val alpha2 by transition.animateFloat(1f, 0f, infiniteRepeatable(tween(3000, delayMillis = 1500, easing = LinearEasing)))
    
    Box(modifier = Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(260.dp).scale(scale1).border(2.dp, ISROGold.copy(alpha = alpha1), CircleShape))
        Box(modifier = Modifier.size(260.dp).scale(scale2).border(1.dp, RocketOrange.copy(alpha = alpha2), CircleShape))
        
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(GlassWhite, CircleShape)
                .border(1.dp, GlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Radio, null, tint = ISROGold, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
fun ConnectingScreen(peerName: String) {
    Box(modifier = Modifier.fillMaxSize().background(DeepBlack)) {
        MovingGradientBackground()
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = ISROGold, strokeWidth = 4.dp, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                "SYNCHRONIZING LINK",
                style = MaterialTheme.typography.titleLarge.copy(color = ISROGold, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            )
            Text(
                "ESTABLISHING SECURE CONNECTION TO ${peerName.uppercase()}",
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

fun isEmergencyMessage(text: String): Boolean {
    val lower = text.lowercase()
    val keywords = listOf(
        // English
        "danger", "emergency", "help", "sos", "alert", "fire", "attack", "threat", 
        "warning", "critical", "caution", "hazard", "red alert", "evacuate", "breach", 
        "failure", "crash", "mayday", "bomb", "leak", "poison", "sos", "injured",
        
        // Malayalam
        "അപകടം", "അടിയന്തര", "സഹായം", "തീ", "മുന്നറിയിപ്പ്", "രക്ഷിക്കൂ", 
        "ഒഴിഞ്ഞുപോകുക", "പരാജയം", "ചോർച്ച", "പരിക്ക്", "ആപത്ത്",
        
        // Hindi
        "खतरा", "आपातकाल", "मदद", "बचाओ", "चेतावनी", "आग", "सावधान", 
        "खाली करो", "विफलता", "लीक", "घायल", "आपात",
        
        // Tamil
        "ஆபத்து", "அவசரம்", "உதவி", "தீ", "எச்சரிக்கை", "காப்பாற்று", 
        "வெளியேறு", "தோல்வி", "கசிவு", "காயம்",
        
        // Telugu
        "ప్రమాదం", "అత్యవసర", "సహాయం", "అగ్ని", "హెచ్చరిక", "కాపాడండి",
        
        // Marathi
        "धोका", "आणीबाणी", "मदत", "आग", "चेतावणी", "वाचवा",
        
        // Bengali
        "বিপদ", "জরুরি", "সাহায্য", "আগুন", "সতর্কতা", "বাঁচাও",
        
        // Kannada
        "ಅಪಾಯ", "ತುರ್ತು", "ಸಹಾಯ", "ಬೆಂಕಿ", "ಎಚ್ಚರಿಕೆ", "ಕಾಪಾಡಿ",
        
        // Gujarati
        "ખતરો", "કટોકટી", "મદદ", "આગ", "ચેતવણી", "બચાવો",
        
        // Punjabi
        "ਖ਼ਤਰਾ", "ਐਮਰਜੈਂਸੀ", "ਮਦद", "ਅੱਗ", "ਚੇਤਾਵਨੀ", "ਬਚਾਓ",
        
        // Urdu
        "خطرہ", "ہنگامی صورتحال", "مدد", "آگ", "انتباہ", "بچاؤ"
    )
    return keywords.any { lower.contains(it) }
}

