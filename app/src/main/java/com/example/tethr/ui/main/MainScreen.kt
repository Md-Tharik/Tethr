package com.example.tethr.ui.main

import android.accessibilityservice.AccessibilityServiceInfo
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.example.tethr.BuildConfig
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.tethr.adb.AdbManager
import com.example.tethr.data.SessionRepository
import com.example.tethr.theme.*
import com.example.tethr.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────────────────────────────
// MainScreen — Tethr Dashboard
// Premium dark UI showing live permission status, session stats, and controls.
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SessionRepository(context) }

    // Live state — refresh every second
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isOverlayEnabled by remember { mutableStateOf(false) }
    var isSecureSettingsGranted by remember { mutableStateOf(false) }
    
    var showPcInstructions by remember { mutableStateOf(false) }
    var showLaptopDialog by remember { mutableStateOf(false) }
    var showInAppSetup by remember { mutableStateOf(false) }

    var isDemoMode by remember {
        mutableStateOf(context.getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
            .getBoolean("DEMO_MODE", false))
    }
    var isQuizzesEnabled by remember {
        mutableStateOf(context.getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
            .getBoolean("ENABLE_QUIZZES", true))
    }
    var sessionHistory by remember { mutableStateOf(emptyList<Long>()) }
    var weekCount by remember { mutableIntStateOf(0) }
    var triggerTime by remember { mutableLongStateOf(300_000L) }

    var pendingConnPort by remember { mutableStateOf<Int?>(null) }
    
    fun spawnNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        // Start discovering ports in the background
        com.example.tethr.adb.AdbDiscoveryManager.startDiscovery(context)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "tethr_setup",
                "Tethr Setup",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val remoteInput = androidx.core.app.RemoteInput.Builder(com.example.tethr.adb.AdbPairingReceiver.REMOTE_INPUT_KEY)
            .setLabel("Enter 6-digit pairing code")
            .build()

        val intent = Intent(context, com.example.tethr.adb.AdbPairingReceiver::class.java).apply {
            action = com.example.tethr.adb.AdbPairingReceiver.ACTION_ADB_PAIR
        }

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context, 
            0, 
            intent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )

        val action = androidx.core.app.NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Enter Pairing Code",
            pendingIntent
        ).addRemoteInput(remoteInput).build()

        val builder = androidx.core.app.NotificationCompat.Builder(context, "tethr_setup")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Tethr Pairing Request")
            .setContentText("Enter the Pairing Port and 6-digit code")
            .addAction(action)
            .setOngoing(true)
            .setAutoCancel(false)

        notificationManager.notify(com.example.tethr.adb.AdbPairingReceiver.NOTIFICATION_ID, builder.build())
        showInAppSetup = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            spawnNotification()
        } else {
            Toast.makeText(context, "Notification permission required to pair!", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = checkAccessibilityEnabled(context)
            isOverlayEnabled = Settings.canDrawOverlays(context)
            isSecureSettingsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            sessionHistory = repo.getSessionHistory()
            weekCount = repo.getSessionCountThisWeek()
            triggerTime = repo.computeTriggerTime()
            delay(1000)
        }
    }

    val avgMs = if (sessionHistory.isEmpty()) 0L else sessionHistory.average().toLong()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // ── Hero Header ───────────────────────────────────────────────
            HeroHeader()

            Spacer(Modifier.height(28.dp))

            // ── Readiness Banner ─────────────────────────────────────────
            ReadinessBanner(
                accessibilityOk = isAccessibilityEnabled,
                overlayOk = isOverlayEnabled,
                secureSettingsOk = isSecureSettingsGranted
            )

            Spacer(Modifier.height(24.dp))

            // ── Stats Row ─────────────────────────────────────────────────
            StatsRow(
                avgSessionMs = avgMs,
                sessionCount = sessionHistory.size,
                weekCount = weekCount,
                triggerTimeMs = triggerTime,
            )

            Spacer(Modifier.height(24.dp))

            // ── Permission Cards ─────────────────────────────────────────
            PermissionCard(
                icon = Icons.Rounded.Accessibility,
                title = "Accessibility Service",
                subtitle = if (isAccessibilityEnabled) "Active — Tethr is monitoring" else "Required to track screen time",
                isGranted = isAccessibilityEnabled,
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            Spacer(Modifier.height(12.dp))

            PermissionCard(
                icon = Icons.Rounded.Layers,
                title = "Display Overlay",
                subtitle = if (isOverlayEnabled) "Granted — overlays enabled" else "Required for escalation overlays",
                isGranted = isOverlayEnabled,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    )
                }
            )

            Spacer(Modifier.height(12.dp))

            PermissionCard(
                icon = Icons.Rounded.Monitor,
                title = "Hardware Grayscale",
                subtitle = if (isSecureSettingsGranted) "Granted — true grayscale enabled" else "Required for hardware-level color filter",
                isGranted = isSecureSettingsGranted,
                onClick = {
                    if (!isSecureSettingsGranted) {
                        showLaptopDialog = true
                    }
                }
            )

            Spacer(Modifier.height(24.dp))

            // ── Escalation Matrix Info Card ───────────────────────────────
            EscalationMatrixCard(triggerTimeMs = triggerTime)

            Spacer(Modifier.height(24.dp))

            if (BuildConfig.DEBUG) {
                // ── Demo Mode ─────────────────────────────────────────────────
                DemoModeCard(
                    isDemoMode = isDemoMode,
                    onToggle = { checked ->
                        isDemoMode = checked
                        context.getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("DEMO_MODE", checked).apply()
                    }
                )

                Spacer(Modifier.height(12.dp))

                // ── Math Quiz Toggle ──────────────────────────────────────────
                QuizModeCard(
                    isQuizzesEnabled = isQuizzesEnabled,
                    onToggle = { checked ->
                        isQuizzesEnabled = checked
                        context.getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("ENABLE_QUIZZES", checked).apply()
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Footer ────────────────────────────────────────────────────
            Text(
                text = "Tethr v1.0 · Edge AI On-Device",
                fontSize = 12.sp,
                color = TethrGray600,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
        }

        if (showLaptopDialog) {
            AlertDialog(
                onDismissRequest = { showLaptopDialog = false },
                title = { Text("Do you have a PC/Laptop?", color = Color.White) },
                text = { Text("It is much easier to set up Grayscale using a computer. Do you have one nearby?", color = TethrGray400) },
                confirmButton = {
                    TextButton(onClick = { 
                        showLaptopDialog = false
                        showPcInstructions = true 
                    }) {
                        Text("Yes", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showLaptopDialog = false
                        showInAppSetup = true 
                    }) {
                        Text("No", color = TethrGray400)
                    }
                },
                containerColor = TethrCardBg,
                textContentColor = TethrGray400,
                titleContentColor = Color.White
            )
        }

        if (showPcInstructions) {
            AlertDialog(
                onDismissRequest = { showPcInstructions = false },
                title = { Text("PC Setup Required", color = Color.White) },
                text = {
                    Column {
                        Text("Because this app uses hardware-level Grayscale, Android requires a one-time secure permission.", color = TethrGray400)
                        Spacer(Modifier.height(8.dp))
                        Text("1. Connect your phone to a PC or laptop via USB.", color = TethrGray400)
                        Spacer(Modifier.height(8.dp))
                        Text("2. Open Google Chrome on your PC and visit:", color = TethrGray400)
                        Spacer(Modifier.height(4.dp))
                        Text("tethrai.in/activator", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("3. Make sure to select '${BuildConfig.APPLICATION_ID}' in the dropdown.", color = TethrAmber, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text("4. Click 'Connect' on the webpage.", color = TethrGray400)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPcInstructions = false }) {
                        Text("Got it", color = Color.White)
                    }
                },
                containerColor = TethrCardBg,
                textContentColor = TethrGray400,
                titleContentColor = Color.White
            )
        }

        if (showInAppSetup) {
            var errorMessage by remember { mutableStateOf<String?>(null) }

            ModalBottomSheet(
                onDismissRequest = { showInAppSetup = false },
                containerColor = TethrCardBg,
                dragHandle = { BottomSheetDefaults.DragHandle(color = TethrGray400) }
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Wireless Debugging Setup", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Text("We will use on-device Wireless Debugging to securely grant the Grayscale permission.", color = TethrGray400, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    
                    Text("1. Open Developer Options on your phone.", color = TethrGray400, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("2. Turn on Wireless Debugging.", color = TethrGray400, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("3. Tap 'Start Pairing Notification' below.", color = TethrGray400, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("4. Switch back to Developer Options, tap 'Pair device with pairing code'.", color = TethrGray400, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("5. Type the 6-Digit Code directly into the notification!", color = TethrGray400, textAlign = TextAlign.Center)

                    if (errorMessage != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(errorMessage!!, color = TethrRed, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }

                    Spacer(Modifier.height(24.dp))
                    
                    Button(
                        onClick = { 
                            errorMessage = null
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                    spawnNotification()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                spawnNotification()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Start Pairing Notification", fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { 
                        showInAppSetup = false
                        showPcInstructions = true 
                    }) {
                        Text("Too hard? Use the PC WebUSB method instead", color = TethrGray400, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hero Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeroHeader() {
    // Pulsing glow animation around the logo orb
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Logo orb with subtle pulse
        Box(contentAlignment = Alignment.Center) {
            // Outer ring
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(pulseScale)
                    .background(Color.Transparent, shape = CircleShape)
                    .border(1.dp, Color(0xFF333333), CircleShape)
            )
            // Inner orb
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFF111111), shape = CircleShape)
                    .border(1.dp, Color(0xFF444444), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = "Tethr logo",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Tethr",
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            letterSpacing = 2.sp,
        )
        Text(
            text = "Anti-Doomscroll · Edge AI",
            fontSize = 14.sp,
            color = TethrGray400,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Readiness Banner
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReadinessBanner(accessibilityOk: Boolean, overlayOk: Boolean, secureSettingsOk: Boolean) {
    val allOk = accessibilityOk && overlayOk && secureSettingsOk
    val borderColor = if (allOk) TethrGreen else Color(0xFF555555)
    val iconColor   = if (allOk) TethrGreen else Color(0xFF888888)
    val bannerText  = if (allOk) "Shield Active — Tethr is protecting you" else "Action Required — Grant permissions below"
    val bannerIcon  = if (allOk) Icons.Rounded.CheckCircle else Icons.Rounded.Warning

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF111111))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = bannerIcon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
        Text(bannerText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stats Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatsRow(
    avgSessionMs: Long,
    sessionCount: Int,
    weekCount: Int,
    triggerTimeMs: Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatChip(
            modifier = Modifier.weight(1f),
            label = "Avg Session",
            value = formatDuration(avgSessionMs),
            color = Color.White,
        )
        StatChip(
            modifier = Modifier.weight(1f),
            label = "Sessions",
            value = "$sessionCount recorded",
            color = Color.White,
        )
        StatChip(
            modifier = Modifier.weight(1f),
            label = "AI Trigger",
            value = formatDuration(triggerTimeMs),
            color = Color.White,
        )
    }
}

@Composable
private fun StatChip(modifier: Modifier, label: String, value: String, color: Color) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(TethrCardBg)
            .border(1.dp, TethrCardBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = label,
            color = TethrGray400,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Permission Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isGranted) TethrGreen.copy(alpha = 0.5f) else TethrCardBorder
    val statusColor = if (isGranted) TethrGreen else TethrAmber
    val statusText = if (isGranted) "Granted" else "Tap to Grant"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TethrCardBg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Icon badge
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF222222), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TethrGray400, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }

        // Status badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(statusColor.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Escalation Matrix Info Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EscalationMatrixCard(triggerTimeMs: Long) {
    val tier2Start = formatDuration(triggerTimeMs)
    val tier3Start = formatDuration(triggerTimeMs + 5 * 60_000L)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(TethrCardBg)
            .border(1.dp, TethrCardBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Timeline, contentDescription = null, tint = TethrGray400, modifier = Modifier.size(20.dp))
            Text("Escalation Matrix", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(14.dp))

        TierRow(tier = "Tier 1", time = "0:00 → $tier2Start", label = "Timer Pill", color = Color.White)
        Spacer(Modifier.height(10.dp))
        TierRow(tier = "Tier 2", time = "$tier2Start → $tier3Start", label = "Grayscale Screen", color = TethrGray400)
        Spacer(Modifier.height(10.dp))
        TierRow(tier = "Tier 3", time = "$tier3Start+", label = "Cognitive Loop", color = TethrRed)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = TethrCardBorder)
        Spacer(Modifier.height(10.dp))

        Text(
            "AI Trigger at $tier2Start — personalized from your usage history",
            color = TethrGray400,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TierRow(tier: String, time: String, label: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(tier, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
        Text(time, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(label, color = TethrGray400, fontSize = 12.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Demo Mode Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DemoModeCard(isDemoMode: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TethrCardBg)
            .border(1.dp, if (isDemoMode) Color(0xFF555555) else TethrCardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF222222), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text("Demo Mode", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (isDemoMode) "Active — timers run 60× faster" else "Accelerates the escalation matrix for testing",
                color = TethrGray400,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Switch(
            checked = isDemoMode,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = TethrGray400,
                uncheckedTrackColor = TethrCardBorder,
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Quiz Mode Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun QuizModeCard(isQuizzesEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TethrCardBg)
            .border(1.dp, TethrCardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF222222), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Calculate, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text("Math Quizzes", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (isQuizzesEnabled) "Active — requires cognitive task" else "Disabled — locks at Grayscale tier",
                color = TethrGray400,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Switch(
            checked = isQuizzesEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = TethrGray400,
                uncheckedTrackColor = TethrCardBorder,
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val mins = ms / 60_000
    val secs = (ms % 60_000) / 1000
    return "%d:%02d".format(mins, secs)
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}
