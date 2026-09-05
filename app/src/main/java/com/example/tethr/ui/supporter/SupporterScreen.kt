package com.example.tethr.ui.supporter

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.tethr.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tethr.MainActivity
import com.example.tethr.billing.BillingManager
import com.example.tethr.billing.SupporterStore
import com.example.tethr.theme.*
import kotlinx.coroutines.launch

@Composable
fun SupporterScreen(
    onBack: () -> Unit,
    billingManager: BillingManager,
    supporterStore: SupporterStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSupporter by supporterStore.isSupporter.collectAsState(initial = false)
    val currentLogo by supporterStore.appLogo.collectAsState(initial = "default")
    val currentTimerBg by supporterStore.timerPillBg.collectAsState(initial = "default")
    val currentTimerImageUri by supporterStore.timerPillImageUri.collectAsState(initial = null)

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val file = java.io.File(context.filesDir, "custom_timer_bg.jpg")
                    val outputStream = java.io.FileOutputStream(file)
                    inputStream?.copyTo(outputStream)
                    inputStream?.close()
                    outputStream.close()
                    val savedUri = android.net.Uri.fromFile(file).toString()
                    supporterStore.setTimerPillImageUri(savedUri)
                    supporterStore.setTimerPillBg("custom")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Supporter Pack", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }

        Spacer(Modifier.height(24.dp))

        // Hero Graphic
        Icon(
            imageVector = Icons.Rounded.Favorite,
            contentDescription = null,
            tint = TethrGreen,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Support the Development", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Tethr is completely free to use. By becoming a supporter, you help keep the project alive and unlock exclusive cosmetic perks!",
            color = TethrGray400,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        if (!isSupporter) {
            Button(
                onClick = { 
                    val activity = context as? android.app.Activity
                    if (activity != null) {
                        billingManager.launchBillingFlow(activity)
                    } else {
                        android.widget.Toast.makeText(context, "Error: Could not launch billing", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TethrGreen, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Become a Supporter - ₹199", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        } else {
            // Supporter Badge!
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(TethrGreen.copy(alpha = 0.2f))
                    .border(1.dp, TethrGreen, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = TethrGreen, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("You are a Supporter! Thank you.", color = TethrGreen, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        }

        // Perks Section
        Text("Exclusive Perks", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(16.dp))

        // App Logo Picker
        PerkCard("App Logo", isSupporter) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LogoOption("Default", "default", currentLogo, isSupporter, R.drawable.logo) {
                    scope.launch { supporterStore.setAppLogo("default") }
                    changeAppIcon(context, "com.example.tethr.MainActivity")
                }
                LogoOption("Diamond", "logo_2", currentLogo, isSupporter, R.drawable.logo_2) {
                    scope.launch { supporterStore.setAppLogo("logo_2") }
                    changeAppIcon(context, "com.example.tethr.MainActivityAlias2")
                }
                LogoOption("Gold", "logo_3", currentLogo, isSupporter, R.drawable.logo_3) {
                    scope.launch { supporterStore.setAppLogo("logo_3") }
                    changeAppIcon(context, "com.example.tethr.MainActivityAlias3")
                }
                LogoOption("Ruby", "logo_4", currentLogo, isSupporter, R.drawable.logo_4) {
                    scope.launch { supporterStore.setAppLogo("logo_4") }
                    changeAppIcon(context, "com.example.tethr.MainActivityAlias4")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Timer Pill Background Picker
        PerkCard("Timer Pill Background", isSupporter) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BgOption("Default", "default", currentTimerBg, isSupporter) {
                    scope.launch { supporterStore.setTimerPillImageUri(null); supporterStore.setTimerPillBg("default") }
                }
                BgOption("Green", "green", currentTimerBg, isSupporter) {
                    scope.launch { supporterStore.setTimerPillImageUri(null); supporterStore.setTimerPillBg("green") }
                }
                BgOption("Red", "red", currentTimerBg, isSupporter) {
                    scope.launch { supporterStore.setTimerPillImageUri(null); supporterStore.setTimerPillBg("red") }
                }
                BgOption("Gold", "gold", currentTimerBg, isSupporter) {
                    scope.launch { supporterStore.setTimerPillImageUri(null); supporterStore.setTimerPillBg("gold") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BgOption("Blue", "blue", currentTimerBg, isSupporter) {
                    scope.launch { supporterStore.setTimerPillImageUri(null); supporterStore.setTimerPillBg("blue") }
                }
                BgOption("Purple", "purple", currentTimerBg, isSupporter) {
                    scope.launch { supporterStore.setTimerPillImageUri(null); supporterStore.setTimerPillBg("purple") }
                }
                BgOption("Sunset", "sunset", currentTimerBg, isSupporter) {
                    scope.launch { supporterStore.setTimerPillImageUri(null); supporterStore.setTimerPillBg("sunset") }
                }
                
                val isCustom = currentTimerBg == "custom"
                val customBgColor = if (isCustom) TethrGreen.copy(alpha = 0.2f) else Color(0xFF222222)
                val customBorderColor = if (isCustom) TethrGreen else Color.Transparent

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(customBgColor)
                        .border(2.dp, customBorderColor, RoundedCornerShape(12.dp))
                        .clickable(enabled = isSupporter, onClick = { imagePicker.launch("image/*") })
                        .padding(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).background(if(isSupporter) Color.DarkGray else Color.Gray, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Custom", color = if(isSupporter) Color.White else TethrGray400, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun PerkCard(title: String, isSupporter: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TethrCardBg)
            .border(1.dp, TethrCardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!isSupporter) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = TethrGray400, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
fun LogoOption(label: String, value: String, currentValue: String, isSupporter: Boolean, iconRes: Int, onSelect: () -> Unit) {
    val isSelected = value == currentValue
    val bgColor = if (isSelected) TethrGreen.copy(alpha = 0.2f) else Color(0xFF222222)
    val borderColor = if (isSelected) TethrGreen else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = isSupporter, onClick = onSelect)
            .padding(12.dp)
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.Gray)
        )
        Spacer(Modifier.height(8.dp))
        Text(label, color = if(isSupporter) Color.White else TethrGray400, fontSize = 12.sp)
    }
}

@Composable
fun BgOption(label: String, value: String, currentValue: String, isSupporter: Boolean, onSelect: () -> Unit) {
    val isSelected = value == currentValue
    val bgColor = if (isSelected) TethrGreen.copy(alpha = 0.2f) else Color(0xFF222222)
    val borderColor = if (isSelected) TethrGreen else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = isSupporter, onClick = onSelect)
            .padding(12.dp)
    ) {
        val circleColor = when(value) {
            "green" -> Color(0xFF1B5E20)
            "red" -> Color(0xFFB71C1C)
            "gold" -> Color(0xFFF57F17)
            "blue" -> Color(0xFF0D47A1)
            "purple" -> Color(0xFF4A148C)
            "sunset" -> Color(0xFFBF360C)
            else -> Color.DarkGray
        }
        Box(
            modifier = Modifier.size(40.dp).background(if(isSupporter) circleColor else Color.Gray, CircleShape)
        )
        Spacer(Modifier.height(8.dp))
        Text(label, color = if(isSupporter) Color.White else TethrGray400, fontSize = 12.sp)
    }
}

private fun changeAppIcon(context: Context, componentToEnable: String) {
    val pm = context.packageManager
    val allComponents = listOf(
        "com.example.tethr.MainActivity",
        "com.example.tethr.MainActivityAlias2",
        "com.example.tethr.MainActivityAlias3",
        "com.example.tethr.MainActivityAlias4"
    )

    try {
        for (component in allComponents) {
            val componentName = android.content.ComponentName(context, component)
            if (component == componentToEnable) {
                pm.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            } else {
                pm.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }
        }
        Toast.makeText(context, "Icon changed. The launcher will refresh shortly.", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
