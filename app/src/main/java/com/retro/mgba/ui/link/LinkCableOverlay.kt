package com.retro.mgba.ui.link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro.mgba.R
import com.retro.mgba.SettingsActivity
import com.retro.mgba.core.NativeCore
import com.retro.mgba.ui.theme.*
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

private const val KEY_LAST_LINK_HOST = "link_last_host"
private const val KEY_LAST_LINK_PORT = "link_last_port"

/**
 * Resolves local non-loopback IPv4 addresses (Wi-Fi, Hotspot, VPN/Tailscale).
 */
fun getLocalIpv4Addresses(): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    result.add(nif.name to ip)
                }
            }
        }
    } catch (_: Exception) {
    }
    return result.sortedBy { (iface, _) ->
        when {
            iface.startsWith("wlan") || iface.startsWith("ap") -> 0
            iface.startsWith("tun") || iface.startsWith("tailscale") || iface.startsWith("wg") -> 1
            else -> 2
        }
    }
}

/**
 * Parses either "192.168.1.5" + port or "192.168.1.5:55777" into (host, port).
 */
fun parseHostAndPort(rawHost: String, rawPort: String): Pair<String, Int>? {
    val trimmed = rawHost.trim()
    if (trimmed.isEmpty()) return null

    var host = trimmed
    var portStr = rawPort.trim()
    val colonIdx = trimmed.lastIndexOf(':')
    if (colonIdx > 0 && trimmed.indexOf(':') == colonIdx) {
        host = trimmed.substring(0, colonIdx).trim()
        val embeddedPort = trimmed.substring(colonIdx + 1).trim()
        if (embeddedPort.isNotEmpty()) {
            portStr = embeddedPort
        }
    }
    val port = portStr.toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1024..65535) return null
    return host to port
}

/**
 * Root Compose layer hosted inside EmulationActivity:
 * - Renders a compact translucent live status pill during active gameplay when linked.
 * - Renders the full Link Cable modal panel when opened from Pause Menu or HUD pill.
 */
@Composable
fun LinkCableRootOverlay(
    core: NativeCore,
    isGbaPlatform: Boolean,
    dialogVisible: Boolean,
    onOpenDialog: () -> Unit,
    onCloseDialog: () -> Unit
) {
    var status by remember { mutableStateOf(NativeCore.LinkStatus()) }

    // Poll live native link status cleanly (300ms when dialog open, 1000ms when hidden)
    LaunchedEffect(dialogVisible, core) {
        while (true) {
            if (core.handle != 0L) {
                status = core.getLinkStatus()
            }
            delay(if (dialogVisible) 280L else 900L)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Compact HUD pill visible during active gameplay while connected/hosting
        if (!dialogVisible && status.isActive) {
            LinkHudPill(
                status = status,
                onClick = onOpenDialog,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
            )
        }

        // Modal Link Cable configuration & live status sheet
        if (dialogVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB8000000))
                    .clickable(onClick = onCloseDialog),
                contentAlignment = Alignment.Center
            ) {
                LinkCablePanel(
                    core = core,
                    isGbaPlatform = isGbaPlatform,
                    status = status,
                    onStatusRefresh = {
                        if (core.handle != 0L) status = core.getLinkStatus()
                    },
                    onClose = onCloseDialog,
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 20.dp)
                        .clickable(enabled = false) {}
                )
            }
        }
    }
}

@Composable
private fun LinkHudPill(
    status: NativeCore.LinkStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dotColor = when (status.state) {
        NativeCore.LINK_STATE_CONNECTED -> ActiveIndicatorGreen
        NativeCore.LINK_STATE_HOSTING, NativeCore.LINK_STATE_CONNECTING -> FavoriteGold
        else -> DestructiveRed
    }

    val label = when (status.state) {
        NativeCore.LINK_STATE_CONNECTED -> {
            if (status.pingMs >= 0) {
                "LINK ${status.playerCount}P · ${status.pingMs} ms"
            } else {
                "LINK ${status.playerCount}P"
            }
        }
        NativeCore.LINK_STATE_HOSTING -> stringResource(R.string.link_hud_hosting)
        NativeCore.LINK_STATE_CONNECTING -> stringResource(R.string.link_hud_connecting)
        else -> "LINK"
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Color(0xCC12121A),
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LinkCablePanel(
    core: NativeCore,
    isGbaPlatform: Boolean,
    status: NativeCore.LinkStatus,
    onStatusRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableIntStateOf(if (status.isActive && !status.isHost) 1 else 0) }
    var hostPortText by remember {
        mutableStateOf(prefs.getInt(KEY_LAST_LINK_PORT, NativeCore.DEFAULT_LINK_PORT).toString())
    }
    var joinHostText by remember {
        mutableStateOf(prefs.getString(KEY_LAST_LINK_HOST, "") ?: "")
    }
    var joinPortText by remember {
        mutableStateOf(prefs.getInt(KEY_LAST_LINK_PORT, NativeCore.DEFAULT_LINK_PORT).toString())
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    val localIps = remember { getLocalIpv4Addresses() }
    val primaryIp = localIps.firstOrNull()?.second ?: "127.0.0.1"

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = SurfaceCard,
        border = BorderStroke(1.dp, SurfaceBorderHighlight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(AccentPrimary.copy(alpha = 0.16f))
                            .border(1.dp, AccentPrimary.copy(alpha = 0.4f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cable,
                            contentDescription = null,
                            tint = AccentGlow,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.link_title),
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.link_subtitle),
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = TextSecondary
                    )
                }
            }

            // Platform guard (GB/GBC)
            if (!isGbaPlatform) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(FavoriteGold.copy(alpha = 0.12f))
                        .border(1.dp, FavoriteGold.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = FavoriteGold)
                    Text(
                        text = stringResource(R.string.link_gba_only),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                return@Column
            }

            // Live Link Telemetry + 4-Player Port Rack
            LinkStatusHeroCard(status = status)

            // Active session controls OR Host/Join setup
            if (status.isActive) {
                if (status.isHost) {
                    val activePort = hostPortText.toIntOrNull() ?: NativeCore.DEFAULT_LINK_PORT
                    AddressCopyBox(
                        ip = primaryIp,
                        port = activePort,
                        extraIps = localIps.drop(1)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            core.disconnectLink()
                            onStatusRefresh()
                        },
                        border = BorderStroke(1.dp, DestructiveRed.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DestructiveRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.link_disconnect), fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = TextOnAccent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(stringResource(R.string.link_back_to_game), fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                // Mode switcher (Host / Join)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceLowest)
                        .border(1.dp, SurfaceBorderSubtle, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ModeSegmentButton(
                        label = stringResource(R.string.link_tab_host),
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            validationError = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ModeSegmentButton(
                        label = stringResource(R.string.link_tab_join),
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            validationError = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (selectedTab == 0) {
                    // HOST TAB
                    val previewPort = hostPortText.toIntOrNull()?.takeIf { it in 1024..65535 }
                        ?: NativeCore.DEFAULT_LINK_PORT
                    AddressCopyBox(
                        ip = primaryIp,
                        port = previewPort,
                        extraIps = localIps.drop(1)
                    )

                    OutlinedTextField(
                        value = hostPortText,
                        onValueChange = {
                            hostPortText = it.filter { ch -> ch.isDigit() }.take(5)
                            validationError = null
                        },
                        label = { Text(stringResource(R.string.link_port_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = TextPrimary, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = linkTextFieldColors()
                    )

                    Button(
                        onClick = {
                            val port = hostPortText.toIntOrNull()
                            if (port == null || port !in 1024..65535) {
                                validationError = context.getString(R.string.link_error_invalid_port)
                                return@Button
                            }
                            prefs.edit().putInt(KEY_LAST_LINK_PORT, port).apply()
                            validationError = null
                            val ok = core.startLinkHost(port)
                            onStatusRefresh()
                            if (!ok) {
                                validationError = core.getLinkStatus().lastError
                                    ?: context.getString(R.string.link_error_host_failed)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = TextOnAccent
                        ),
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.link_start_host), fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    // JOIN TAB
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = joinHostText,
                            onValueChange = {
                                joinHostText = it
                                validationError = null
                            },
                            label = { Text(stringResource(R.string.link_host_ip_label)) },
                            placeholder = {
                                Text(
                                    text = "192.168.1.42",
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = GbcCyan, fontSize = 14.sp),
                            modifier = Modifier.weight(1.8f),
                            shape = RoundedCornerShape(12.dp),
                            colors = linkTextFieldColors()
                        )
                        OutlinedTextField(
                            value = joinPortText,
                            onValueChange = {
                                joinPortText = it.filter { ch -> ch.isDigit() }.take(5)
                                validationError = null
                            },
                            label = { Text(stringResource(R.string.link_port_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = TextPrimary, fontSize = 14.sp),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = linkTextFieldColors()
                        )
                    }

                    Button(
                        onClick = {
                            val parsed = parseHostAndPort(joinHostText, joinPortText)
                            if (parsed == null) {
                                validationError = context.getString(R.string.link_error_invalid_address)
                                return@Button
                            }
                            val (host, port) = parsed
                            joinHostText = host
                            joinPortText = port.toString()
                            prefs.edit()
                                .putString(KEY_LAST_LINK_HOST, host)
                                .putInt(KEY_LAST_LINK_PORT, port)
                                .apply()
                            validationError = null
                            val ok = core.connectLinkClient(host, port)
                            onStatusRefresh()
                            if (!ok) {
                                validationError = core.getLinkStatus().lastError
                                    ?: context.getString(R.string.link_error_connect_failed)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = TextOnAccent
                        ),
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.link_connect), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Error banner (validation or native socket error)
            val displayedError = validationError ?: status.lastError?.takeIf { status.state == NativeCore.LINK_STATE_ERROR }
            displayedError?.let { err ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DestructiveRed.copy(alpha = 0.14f))
                        .border(1.dp, DestructiveRed.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = DestructiveRed, modifier = Modifier.size(18.dp))
                    Text(text = err, color = Color(0xFFFCA5A5), fontSize = 12.sp, lineHeight = 16.sp)
                }
            }

            // Concise transport hint (Wi-Fi / Hotspot / VPN / Internet)
            Text(
                text = stringResource(R.string.link_transport_hint),
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun LinkStatusHeroCard(status: NativeCore.LinkStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    val ledColor = when (status.state) {
        NativeCore.LINK_STATE_CONNECTED -> ActiveIndicatorGreen
        NativeCore.LINK_STATE_HOSTING, NativeCore.LINK_STATE_CONNECTING -> FavoriteGold
        NativeCore.LINK_STATE_ERROR -> DestructiveRed
        else -> TextMuted
    }

    val stateLabel = when (status.state) {
        NativeCore.LINK_STATE_CONNECTED -> stringResource(R.string.link_state_connected)
        NativeCore.LINK_STATE_HOSTING -> stringResource(R.string.link_state_hosting)
        NativeCore.LINK_STATE_CONNECTING -> stringResource(R.string.link_state_connecting)
        NativeCore.LINK_STATE_ERROR -> stringResource(R.string.link_state_error)
        else -> stringResource(R.string.link_state_idle)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceLowest)
            .border(1.dp, if (status.state == NativeCore.LINK_STATE_CONNECTED) ActiveIndicatorGreen.copy(alpha = 0.4f) else SurfaceBorderSubtle, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(if (status.isActive) pulseAlpha else 1f)
                        .clip(CircleShape)
                        .background(ledColor)
                )
                Text(
                    text = stateLabel,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            if (status.pingMs >= 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ActiveIndicatorGreen.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${status.pingMs} ms",
                        color = ActiveIndicatorGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 4-Player GBA Link Port Visualizer (P1..P4)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (slot in 0 until 4) {
                val occupied = status.isActive && slot < status.playerCount
                val isSelf = status.isActive && slot == status.playerId
                val slotBg by animateColorAsState(
                    targetValue = when {
                        isSelf -> AccentPrimary.copy(alpha = 0.24f)
                        occupied -> ActiveIndicatorGreen.copy(alpha = 0.16f)
                        else -> SurfaceCard
                    },
                    label = "slotBg"
                )
                val slotBorder = when {
                    isSelf -> AccentPrimary
                    occupied -> ActiveIndicatorGreen.copy(alpha = 0.55f)
                    else -> SurfaceBorderSubtle
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(slotBg)
                        .border(1.dp, slotBorder, RoundedCornerShape(10.dp))
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "P${slot + 1}",
                        color = if (occupied) TextPrimary else TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = when {
                            isSelf -> stringResource(R.string.link_slot_you)
                            slot == 0 && occupied -> stringResource(R.string.link_slot_host)
                            occupied -> stringResource(R.string.link_slot_peer)
                            else -> "—"
                        },
                        color = when {
                            isSelf -> AccentGlow
                            occupied -> ActiveIndicatorGreen
                            else -> TextMuted
                        },
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressCopyBox(
    ip: String,
    port: Int,
    extraIps: List<Pair<String, String>>
) {
    val context = LocalContext.current
    val fullAddress = "$ip:$port"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceLowest)
            .border(1.dp, SurfaceBorderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.link_your_address),
            color = TextSecondary,
            fontSize = 11.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = fullAddress,
                color = GbcCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Surface(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("mGBA Link Address", fullAddress))
                    Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(8.dp),
                color = SurfaceElevated,
                border = BorderStroke(1.dp, SurfaceBorderHighlight)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AccentGlow, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.link_copy), color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (extraIps.isNotEmpty()) {
            val alt = extraIps.take(2).joinToString("  •  ") { "${it.first}: ${it.second}" }
            Text(
                text = alt,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ModeSegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) AccentPrimary else Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (selected) TextOnAccent else TextSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun linkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentPrimary,
    unfocusedBorderColor = SurfaceBorderHighlight,
    focusedLabelColor = AccentGlow,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentPrimary,
    focusedContainerColor = SurfaceLowest,
    unfocusedContainerColor = SurfaceLowest
)
