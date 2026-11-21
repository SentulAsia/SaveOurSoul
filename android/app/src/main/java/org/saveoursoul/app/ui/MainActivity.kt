package org.saveoursoul.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.saveoursoul.app.BackendClient
import org.saveoursoul.app.BuildConfig
import org.saveoursoul.app.SosApp
import org.saveoursoul.app.SosEngine
import org.saveoursoul.app.SosService
import org.saveoursoul.core.SosKind

private val Red = Color(0xFFD32F2F)
private const val HOLD_TO_TRIGGER_MS = 1_500L
private const val COUNTDOWN_SEC = 5

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = SosApp.engine(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Red)) {
                Surface(Modifier.fillMaxSize()) { App(engine) }
            }
        }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.SEND_SMS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

@Composable
private fun App(engine: SosEngine) {
    val state by engine.state.collectAsStateWithLifecycle()
    var enrolled by remember { mutableStateOf(engine.credentials.isEnrolled) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        engine.updateMeshRunning()
    }
    LaunchedEffect(Unit) { permissions.launch(requiredPermissions()) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (BuildConfig.APP_ENV != "prod") {
            Text("${BuildConfig.APP_ENV.uppercase()} BUILD · not for real emergencies", color = Color(0xFFFFB300), fontSize = 12.sp)
        }
        state.error?.let { ErrorBanner(it) { engine.dismissError() } }
        if (state.isActive) ActiveSos(state) else IdleSos(state)
        ChannelPanel(state)
        if (!enrolled) EnrollCard(engine) { enrolled = true }
        SettingsCard(engine)
    }
}

@Composable
private fun IdleSos(state: SosEngine.State) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // "Lost" first: being separated from the group is the most common emergency at Hajj.
    var kind by remember { mutableStateOf(SosKind.LOST) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        if (c == 0) {
            countdown = null
            SosService.trigger(context, kind)
        } else {
            delay(1_000)
            countdown = c - 1
        }
    }

    Text("Hold the button to call for help", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SosKind.entries.forEach { k ->
            FilterChip(selected = k == kind, onClick = { kind = k }, label = { Text(k.name.lowercase().replaceFirstChar(Char::uppercase), fontSize = 11.sp) })
        }
    }

    Box(
        Modifier.size(240.dp).clip(CircleShape).background(Red).pointerInput(Unit) {
            detectTapGestures(onPress = {
                if (countdown != null) return@detectTapGestures
                val job = scope.launch {
                    val start = System.currentTimeMillis()
                    while (isActive) {
                        holdProgress = ((System.currentTimeMillis() - start).toFloat() / HOLD_TO_TRIGGER_MS).coerceAtMost(1f)
                        if (holdProgress >= 1f) {
                            countdown = COUNTDOWN_SEC
                            break
                        }
                        delay(16)
                    }
                }
                tryAwaitRelease()
                job.cancel()
                holdProgress = 0f
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        if (holdProgress > 0f) CircularProgressIndicator(progress = { holdProgress }, modifier = Modifier.size(220.dp), color = Color.White, strokeWidth = 8.dp)
        Text(countdown?.toString() ?: "SOS", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
    }

    if (countdown != null) {
        Text("Sending SOS in $countdown s", color = Red, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { countdown = null }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Cancel — I'm OK") }
    }
    if (state.responderStatus == "resolved") Text("Your last alert was resolved by a responder.")
}

@Composable
private fun ActiveSos(state: SosEngine.State) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmCancel by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(Red).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOS ACTIVE", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Text(state.kind.name.lowercase() + " emergency", color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                when (state.responderStatus) {
                    "acknowledged" -> "A responder has acknowledged your SOS. Help is coming."
                    "open" -> "Your SOS reached the emergency server. Waiting for a responder…"
                    else -> "Sending over internet, SMS and nearby phones…"
                },
                color = Color.White, textAlign = TextAlign.Center,
            )
            state.lastFix?.let { Text("Location ±${it.accuracy.toInt()} m shared", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp) }
        }
    }
    if (!confirmCancel) {
        OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("I'm safe — cancel SOS") }
    } else {
        Button(
            onClick = { SosService.cancel(context); confirmCancel = false },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Confirm: I'm safe") }
    }
}

@Composable
private fun ChannelPanel(s: SosEngine.State) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Channels", fontWeight = FontWeight.Bold)
            ChannelRow("Internet (Wi-Fi / cellular)", s.internet)
            ChannelRow("SMS", s.sms)
            ChannelRow("Bluetooth relay (works in crowds, no internet needed)", s.ble)
            ChannelRow("Nearby phones (Bluetooth / Wi-Fi Direct)", s.mesh)
            if (s.pendingFrames > 0) Text("${s.pendingFrames} signal(s) queued for delivery", fontSize = 12.sp)
            if (s.relayedForOthers > 0) Text("Relayed ${s.relayedForOthers} SOS signal(s) for people nearby", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChannelRow(name: String, c: SosEngine.ChannelState) {
    val dot = when (c.health) {
        SosEngine.Health.OK -> Color(0xFF43A047)
        SosEngine.Health.WORKING -> Color(0xFFFFB300)
        SosEngine.Health.FAILED -> Red
        SosEngine.Health.IDLE -> Color.Gray
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.size(8.dp))
        Column {
            Text(name, fontSize = 14.sp)
            if (c.detail.isNotEmpty()) Text(c.detail, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun ErrorBanner(msg: String, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(msg, Modifier.weight(1f), color = Red)
            OutlinedButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun EnrollCard(engine: SosEngine, onDone: () -> Unit) {
    val settings = engine.settings
    var url by remember { mutableStateOf(settings.backendUrl) }
    var name by remember { mutableStateOf(settings.userName) }
    var phone by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Register this phone", fontWeight = FontWeight.Bold)
            Text("Needs internet once. Afterwards SOS works over SMS and nearby phones too.", fontSize = 12.sp)
            OutlinedTextField(url, { url = it }, label = { Text("SOS server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Your phone number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Enrollment code (if your organisation gave you one)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = Red, fontSize = 12.sp) }
            Button(enabled = !busy && url.startsWith("http"), onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        settings.backendUrl = url
                        settings.userName = name
                        val e = BackendClient { settings.backendUrl }.enroll(name.ifBlank { Build.MODEL }, phone, token.ifBlank { null })
                        engine.credentials.store(e.deviceId, e.secret)
                        onDone()
                    } catch (e: Exception) {
                        error = "Registration failed: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Registering…" else "Register") }
        }
    }
}

@Composable
private fun SettingsCard(engine: SosEngine) {
    val s = engine.settings
    var gateway by remember { mutableStateOf(s.smsGatewayNumber) }
    var contacts by remember { mutableStateOf(s.emergencyContacts.joinToString(", ")) }
    var delaySec by remember { mutableStateOf(s.smsFallbackDelaySec.toString()) }
    var relay by remember { mutableStateOf(s.relayEnabled) }
    var relaySms by remember { mutableStateOf(s.relayViaSms) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", fontWeight = FontWeight.Bold)
            OutlinedTextField(gateway, { gateway = it; s.smsGatewayNumber = it }, label = { Text("SMS gateway number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(contacts, { contacts = it; s.emergencyContacts = it.split(',') }, label = { Text("Emergency contacts (comma separated)") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(delaySec, { v -> delaySec = v.filter(Char::isDigit); delaySec.toIntOrNull()?.let { s.smsFallbackDelaySec = it } },
                label = { Text("Use SMS gateway if no internet confirmation after (s)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            SwitchRow("Relay SOS for people nearby (Bluetooth / Wi-Fi)", relay) {
                relay = it
                s.relayEnabled = it
                if (it) SosService.start(context)
                engine.updateMeshRunning()
            }
            SwitchRow("Allow relaying strangers' SOS by SMS when offline (uses your SMS)", relaySms) { relaySms = it; s.relayViaSms = it }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
