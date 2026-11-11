package com.example.freevpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

data class VpnServer(
    val id: String,
    val country: String,
    val city: String,
    val flagEmoji: String,
    val endpointIp: String,
    val port: Int,
    val dnsServer: String,
    val pingMs: Int,
    val wireGuardPublicKey: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981),
                    secondary = Color(0xFF3B82F6),
                    background = Color(0xFF0B0F19),
                    surface = Color(0xFF151C2C),
                    onBackground = Color(0xFFF9FAFB),
                    onSurface = Color(0xFFF3F4F6)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FreeVpnScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeVpnScreen() {
    val context = LocalContext.current

    val defaultServers = remember {
        mutableStateListOf(
            VpnServer("nl-free-1", "Netherlands", "Amsterdam", "🇳🇱", "185.107.56.212", 51820, "1.1.1.1", 28),
            VpnServer("us-free-1", "United States", "New York", "🇺🇸", "198.54.132.88", 51820, "1.1.1.1", 64),
            VpnServer("jp-free-1", "Japan", "Tokyo", "🇯🇵", "138.199.21.45", 51820, "9.9.9.9", 89),
            VpnServer("de-free-1", "Germany", "Frankfurt", "🇩🇪", "89.58.12.104", 51820, "1.1.1.1", 32),
            VpnServer("sg-free-1", "Singapore", "Singapore", "🇸🇬", "103.253.41.18", 51820, "9.9.9.9", 41)
        )
    }

    var selectedServer by remember { mutableStateOf(defaultServers.first()) }
    var vpnState by remember { mutableStateOf(VpnState.DISCONNECTED) }
    var connectedSeconds by remember { mutableLongStateOf(0L) }
    var rxBytes by remember { mutableLongStateOf(0L) }
    var txBytes by remember { mutableLongStateOf(0L) }

    // Settings state
    var customDnsEnabled by remember { mutableStateOf(true) }
    var blockIpv6Leak by remember { mutableStateOf(true) }
    var splitTunnelingBrowserOnly by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showImportConfigDialog by remember { mutableStateOf(false) }

    // Listen to status updates broadcast from FreeVpnService
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    FreeVpnService.ACTION_STATUS_BROADCAST -> {
                        val stateStr = intent.getStringExtra(FreeVpnService.EXTRA_STATE)
                        vpnState = when (stateStr) {
                            "CONNECTED" -> VpnState.CONNECTED
                            "CONNECTING" -> VpnState.CONNECTING
                            else -> VpnState.DISCONNECTED
                        }
                        rxBytes = intent.getLongExtra(FreeVpnService.EXTRA_RX_BYTES, 0L)
                        txBytes = intent.getLongExtra(FreeVpnService.EXTRA_TX_BYTES, 0L)
                    }
                }
            }
        }
        val filter = IntentFilter(FreeVpnService.ACTION_STATUS_BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Session timer while connected
    LaunchedEffect(vpnState) {
        if (vpnState == VpnState.CONNECTED) {
            while (vpnState == VpnState.CONNECTED) {
                delay(1000L)
                connectedSeconds += 1L
            }
        } else if (vpnState == VpnState.DISCONNECTED) {
            connectedSeconds = 0L
            rxBytes = 0L
            txBytes = 0L
        }
    }

    // Helper to start the foreground VpnService
    fun startVpnService(server: VpnServer) {
        vpnState = VpnState.CONNECTING
        val serviceIntent = Intent(context, FreeVpnService::class.java).apply {
            action = FreeVpnService.ACTION_CONNECT
            putExtra(FreeVpnService.EXTRA_SERVER_NAME, "${server.country} (${server.city})")
            putExtra(FreeVpnService.EXTRA_SERVER_ENDPOINT, server.endpointIp)
            putExtra(FreeVpnService.EXTRA_SERVER_PORT, server.port)
            putExtra(
                FreeVpnService.EXTRA_DNS_SERVER,
                if (customDnsEnabled) server.dnsServer else "8.8.8.8"
            )
            putExtra(FreeVpnService.EXTRA_BLOCK_IPV6, blockIpv6Leak)
            putExtra(FreeVpnService.EXTRA_SPLIT_TUNNEL, splitTunnelingBrowserOnly)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun stopVpnService() {
        val serviceIntent = Intent(context, FreeVpnService::class.java).apply {
            action = FreeVpnService.ACTION_DISCONNECT
        }
        context.startService(serviceIntent)
        vpnState = VpnState.DISCONNECTED
    }

    // Android VpnService.prepare() permission dialog launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(selectedServer)
        } else {
            vpnState = VpnState.DISCONNECTED
        }
    }

    fun onToggleVpn() {
        if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING) {
            stopVpnService()
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                startVpnService(selectedServer)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Shield",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "OpenShield VPN",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showImportConfigDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Import Custom Server"
                        )
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "VPN Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0B0F19),
                    titleContentColor = Color.White,
                    actionIconContentColor = Color(0xFF9CA3AF)
                )
            )
        },
        containerColor = Color(0xFF0B0F19)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Connection Status Pill
            StatusBadge(vpnState = vpnState)

            Spacer(modifier = Modifier.height(28.dp))

            // Animated Central Connect Button
            ConnectButton(
                vpnState = vpnState,
                onClick = { onToggleVpn() }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Session Timer & Assigned Tunnel IP
            Text(
                text = formatDuration(connectedSeconds),
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (vpnState == VpnState.CONNECTED) Color.White else Color(0xFF6B7280)
            )
            Text(
                text = if (vpnState == VpnState.CONNECTED) {
                    "Tunnel IP: 10.8.0.2 • Endpoint: ${selectedServer.endpointIp}"
                } else {
                    "Tap the shield to establish an encrypted TUN tunnel"
                },
                fontSize = 13.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Live Upload / Download Telemetry Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryCard(
                    label = "DOWNLOAD",
                    value = formatBytes(rxBytes),
                    accentColor = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
                TelemetryCard(
                    label = "UPLOAD",
                    value = formatBytes(txBytes),
                    accentColor = Color(0xFF3B82F6),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Server List Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FREE TUNNEL ENDPOINTS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9CA3AF),
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${defaultServers.size} available",
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Server List
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(defaultServers, key = { it.id }) { server ->
                    ServerRowItem(
                        server = server,
                        isSelected = server.id == selectedServer.id,
                        onSelect = {
                            val wasConnected = vpnState == VpnState.CONNECTED
                            selectedServer = server
                            if (wasConnected) {
                                stopVpnService()
                                startVpnService(server)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSettingsSheet) {
        AlertDialog(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = Color(0xFF151C2C),
            title = {
                Text("Tunnel & Privacy Settings", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingToggleRow(
                        title = "Encrypted DNS (1.1.1.1 / 9.9.9.9)",
                        subtitle = "Route DNS queries inside the VPN TUN interface",
                        checked = customDnsEnabled,
                        onCheckedChange = { customDnsEnabled = it }
                    )
                    SettingToggleRow(
                        title = "IPv6 Leak Protection",
                        subtitle = "Block non-tunneled IPv6 traffic across interfaces",
                        checked = blockIpv6Leak,
                        onCheckedChange = { blockIpv6Leak = it }
                    )
                    SettingToggleRow(
                        title = "Split Tunneling (Browsers Only)",
                        subtitle = "Exclude local system apps from the VPN route",
                        checked = splitTunnelingBrowserOnly,
                        onCheckedChange = { splitTunnelingBrowserOnly = it }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsSheet = false }) {
                    Text("Done", color = Color(0xFF10B981))
                }
            }
        )
    }

    if (showImportConfigDialog) {
        var countryInput by remember { mutableStateOf("") }
        var endpointInput by remember { mutableStateOf("") }
        var portInput by remember { mutableStateOf("51820") }

        AlertDialog(
            onDismissRequest = { showImportConfigDialog = false },
            containerColor = Color(0xFF151C2C),
            title = {
                Text("Add Custom WireGuard / TUN Endpoint", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = countryInput,
                        onValueChange = { countryInput = it },
                        label = { Text("Server Label (e.g. Switzerland)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endpointInput,
                        onValueChange = { endpointInput = it },
                        label = { Text("Endpoint IP / Hostname") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text("UDP Port (default 51820)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (countryInput.isNotBlank() && endpointInput.isNotBlank()) {
                            val newServer = VpnServer(
                                id = "custom-${System.currentTimeMillis()}",
                                country = countryInput.trim(),
                                city = "Custom Endpoint",
                                flagEmoji = "🌐",
                                endpointIp = endpointInput.trim(),
                                port = portInput.toIntOrNull() ?: 51820,
                                dnsServer = "1.1.1.1",
                                pingMs = 45
                            )
                            defaultServers.add(0, newServer)
                            selectedServer = newServer
                            showImportConfigDialog = false
                        }
                    }
                ) {
                    Text("Save Endpoint", color = Color(0xFF10B981))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfigDialog = false }) {
                    Text("Cancel", color = Color(0xFF9CA3AF))
                }
            }
        )
    }
}

@Composable
fun StatusBadge(vpnState: VpnState) {
    val bgColor = when (vpnState) {
        VpnState.CONNECTED -> Color(0xFF064E3B)
        VpnState.CONNECTING -> Color(0xFF78350F)
        VpnState.DISCONNECTED -> Color(0xFF1F2937)
    }
    val dotColor = when (vpnState) {
        VpnState.CONNECTED -> Color(0xFF10B981)
        VpnState.CONNECTING -> Color(0xFFF59E0B)
        VpnState.DISCONNECTED -> Color(0xFFEF4444)
    }
    val text = when (vpnState) {
        VpnState.CONNECTED -> "PROTECTED • TUN0 ACTIVE"
        VpnState.CONNECTING -> "NEGOTIATING HANDSHAKE..."
        VpnState.DISCONNECTED -> "UNPROTECTED CONNECTION"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
fun ConnectButton(vpnState: VpnState, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (vpnState == VpnState.CONNECTING) 1.12f else 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val ringColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> Color(0xFF10B981)
            VpnState.CONNECTING -> Color(0xFFF59E0B)
            VpnState.DISCONNECTED -> Color(0xFF374151)
        },
        label = "ringColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(190.dp)
    ) {
        // Outer pulsing halo
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(if (vpnState != VpnState.DISCONNECTED) pulseScale else 1f)
                .clip(CircleShape)
                .background(ringColor.copy(alpha = 0.16f))
        )

        // Main circular button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(148.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                    )
                )
                .border(3.dp, ringColor, CircleShape)
                .clickable { onClick() }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (vpnState == VpnState.CONNECTING) Icons.Default.Refresh else Icons.Default.Lock,
                    contentDescription = "Toggle VPN",
                    tint = ringColor,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (vpnState) {
                        VpnState.CONNECTED -> "DISCONNECT"
                        VpnState.CONNECTING -> "CANCEL"
                        VpnState.DISCONNECTED -> "CONNECT"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun TelemetryCard(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF151C2C))
            .padding(16.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF9CA3AF),
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = accentColor
        )
    }
}

@Composable
fun ServerRowItem(
    server: VpnServer,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFF10B981) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF151C2C))
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = server.flagEmoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = server.country,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "${server.city} • DNS ${server.dnsServer}",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${server.pingMs} ms",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (server.pingMs < 50) Color(0xFF10B981) else Color(0xFFF59E0B)
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = Color(0xFF9CA3AF), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format("%02d:%02d:%02d", hrs, mins, secs)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MB", mb)
}
