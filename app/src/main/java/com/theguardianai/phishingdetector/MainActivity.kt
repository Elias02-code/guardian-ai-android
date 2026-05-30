package com.theguardianai.phishingdetector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.theguardianai.phishingdetector.ui.theme.TheGuardianAITheme
import com.theguardianai.phishingdetector.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ─── Constants ──────────────────────────────────────────────────────────────────
const val REPORT_EMAIL = "ejehehitosin07@gmail.com"

// ─── Data Models ────────────────────────────────────────────────────────────────
data class ScanResult(
    val prediction: String,
    val confidence: Double,
    val phishingProbability: Double,
    val note: String
)

data class ScanHistoryItem(
    val url: String,
    val prediction: String,
    val confidence: Double,
    val timestamp: Long = System.currentTimeMillis()
)

// ─── MainActivity ────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var themePreference by remember { mutableStateOf(getThemePreference(context)) }
            val isDarkTheme = when (themePreference) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            TheGuardianAITheme(darkTheme = isDarkTheme) {
                GuardianApp(
                    themePreference = themePreference,
                    onThemeChange = { newTheme ->
                        saveThemePreference(context, newTheme)
                        themePreference = newTheme
                    }
                )
            }
        }
    }
}

// ─── SharedPreferences Helpers ───────────────────────────────────────────────────
fun getThemePreference(context: Context): String =
    context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        .getString("theme", "system") ?: "system"

fun saveThemePreference(context: Context, theme: String) =
    context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        .edit().putString("theme", theme).apply()

fun getUserName(context: Context): String? =
    context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        .getString("user_name", null)

fun saveUserName(context: Context, name: String) =
    context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        .edit().putString("user_name", name).apply()

fun getScanHistory(context: Context): List<ScanHistoryItem> {
    val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
    val json = prefs.getString("scan_history", "[]") ?: "[]"
    val array = JSONArray(json)
    val items = mutableListOf<ScanHistoryItem>()
    val threeWeeksAgo = System.currentTimeMillis() - (21L * 24 * 60 * 60 * 1000)
    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        val timestamp = obj.getLong("timestamp")
        if (timestamp > threeWeeksAgo) {
            items.add(
                ScanHistoryItem(
                    url = obj.getString("url"),
                    prediction = obj.getString("prediction"),
                    confidence = obj.getDouble("confidence"),
                    timestamp = timestamp
                )
            )
        }
    }
    return items
}

fun saveScanHistory(context: Context, items: List<ScanHistoryItem>) {
    val array = JSONArray()
    items.forEach { item ->
        val obj = JSONObject()
        obj.put("url", item.url)
        obj.put("prediction", item.prediction)
        obj.put("confidence", item.confidence)
        obj.put("timestamp", item.timestamp)
        array.put(obj)
    }
    context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        .edit().putString("scan_history", array.toString()).apply()
}

fun addToHistory(context: Context, item: ScanHistoryItem) {
    val current = getScanHistory(context).toMutableList()
    current.removeAll { it.url == item.url }
    current.add(0, item)
    saveScanHistory(context, current.take(50))
}

// ─── Greeting ────────────────────────────────────────────────────────────────────
fun getGreeting(name: String): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val greetings = when {
        hour < 12 -> listOf(
            "Good morning, $name ☀️",
            "Rise and guard, $name 🛡️",
            "Morning, $name! Stay safe out there."
        )
        hour < 17 -> listOf(
            "Welcome back, $name 👋",
            "How's the guard, $name? 🛡️",
            "Good afternoon, $name! Keep scanning."
        )
        else -> listOf(
            "Evening, $name 🌙",
            "Guarding the night, $name 🛡️",
            "Good evening, $name! Stay vigilant."
        )
    }
    return greetings[dayOfYear % greetings.size]
}

// ─── Guardian App ────────────────────────────────────────────────────────────────
@Composable
fun GuardianApp(themePreference: String, onThemeChange: (String) -> Unit) {
    val context = LocalContext.current
    var userName by remember { mutableStateOf(getUserName(context)) }

    if (userName == null) {
        NameSetupScreen { name ->
            saveUserName(context, name)
            userName = name
        }
    } else {
        MainScreen(
            userName = userName!!,
            context = context,
            themePreference = themePreference,
            onThemeChange = onThemeChange,
            onNameChange = { newName ->
                saveUserName(context, newName)
                userName = newName
            }
        )
    }
}

// ─── Name Setup Screen ───────────────────────────────────────────────────────────
@Composable
fun NameSetupScreen(onNameSaved: (String) -> Unit) {
    var nameInput by remember { mutableStateOf("") }
    var showNameDialog by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F23)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .scale(scale)
                    .size(160.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(Color(0xFF1A1E3A)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.guardian_icon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(140.dp)
                        .padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("The Guardian AI", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("AI-Powered Phishing Detection", fontSize = 13.sp, color = Color(0xFF94A3B8))
            Spacer(modifier = Modifier.height(40.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F2E))
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Before we Guard out,", fontSize = 14.sp, color = Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("What should we call you?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = { Text("Enter name or nickname", color = Color(0xFF2A3441)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF2A3441),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF3B82F6)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.Person, null, tint = Color(0xFF94A3B8))
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
    onClick = {
        if (nameInput.isBlank()) {
            showNameDialog = true
        } else {
            onNameSaved(nameInput.trim())
        }
    },
    modifier = Modifier
        .fillMaxWidth()
        .height(52.dp),
    colors = ButtonDefaults.buttonColors(
        containerColor = Color.Transparent
    ),
    shape = RoundedCornerShape(12.dp),
    contentPadding = PaddingValues(0.dp)
) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Get Started", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
if (showNameDialog) {

    AlertDialog(
        onDismissRequest = {
            showNameDialog = false
        },

        title = {
            Text(
                text = "Continue without a name?",
                color = Color.White
            )
        },

        text = {
            Text(
                text = "Please enter a name or nickname, this helps us enhance your Guardian experience.",
                color = Color(0xFF94A3B8)
            )
        },

        confirmButton = {
            TextButton(
                onClick = {
                    showNameDialog = false
                    onNameSaved("Guardian")
                }
            ) {
                Text("Continue Anyway")
            }
        },

        dismissButton = {
            TextButton(
                onClick = {
                    showNameDialog = false
                }
            ) {
                Text("Go Back")
            }
        },

        containerColor = Color(0xFF1A1F2E)
    )
}
}

// ─── Main Screen ─────────────────────────────────────────────────────────────────
@Composable
fun MainScreen(
    userName: String,
    context: Context,
    themePreference: String,
    onThemeChange: (String) -> Unit,
    onNameChange: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadingStep by remember { mutableStateOf(0) }
    var scanResult by remember { mutableStateOf<ScanResult?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var scanHistory by remember { mutableStateOf(getScanHistory(context)) }
    var scannedUrl by remember { mutableStateOf("") }
    var showDrawer by remember { mutableStateOf(false) }
    var currentUserName by remember { mutableStateOf(getUserName(context) ?: userName) }

    val isLight = themePreference == "light" ||
            (themePreference == "system" && !androidx.compose.foundation.isSystemInDarkTheme())
    val darkBackground = if (isLight) Color(0xFFF1F5F9) else Color(0xFF0F0F23)
    val cardColor = if (isLight) Color(0xFFFFFFFF) else Color(0xFF1A1F2E)
    val textPrimary = if (isLight) Color(0xFF0F172A) else Color.White
    val textSecondary = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8)
    val borderColor = if (isLight) Color(0xFFCBD5E1) else Color(0xFF2A3441)
    val accentBlue = Color(0xFF3B82F6)
    val accentPurple = Color(0xFF8B5CF6)
    val safeGreen = Color(0xFF10B981)
    val dangerRed = Color(0xFFEF4444)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val shieldScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "shieldScale"
    )

    // Warm-up ping
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://phishing-api-j7fs.onrender.com/")
                    .get().build()
                client.newCall(request).execute()
            }
        } catch (e: Exception) { }
    }

    Box(modifier = Modifier.fillMaxSize().background(darkBackground)) {

        // ── Main Content ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            // Hamburger menu icon
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { showDrawer = true },
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Icon(Icons.Filled.Menu, "Menu", tint = textSecondary)
                }
            }

            // Shield header
            Box(
    modifier = Modifier
        .size(52.dp)
        .clip(RoundedCornerShape(14.dp)),
    contentAlignment = Alignment.Center
) {
    androidx.compose.foundation.Image(
    painter = androidx.compose.ui.res.painterResource(id = R.drawable.guardian_icon),
    contentDescription = "Guardian",
    modifier = Modifier.size(90.dp)
)
}

            Spacer(modifier = Modifier.height(14.dp))
            Text("Guardian", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(getGreeting(currentUserName), fontSize = 14.sp, color = textSecondary, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(12.dp))

            // Status pills
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveStatusPill()
                StatusPill("🔒 Secure", textSecondary, cardColor)
                StatusPill("⚡ Real-time", textSecondary, cardColor)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Input Card ───────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("TARGET URL", fontSize = 11.sp, color = textSecondary,
                        fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    val previouslySeen = scanHistory.any {
                        it.url == urlInput.trim() || it.url == "https://${urlInput.trim()}"
                    }

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it; scanResult = null; errorMessage = "" },
                        placeholder = { Text("Paste a URL to scan...", color = Color(0xFF4B5563)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (previouslySeen) accentPurple else accentBlue,
                            unfocusedBorderColor = if (previouslySeen) accentPurple.copy(alpha = 0.5f) else borderColor,
                            focusedTextColor = if (previouslySeen) accentPurple else textPrimary,
                            unfocusedTextColor = if (previouslySeen) accentPurple else textPrimary,
                            cursorColor = accentBlue
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.Search, null,
                                tint = if (previouslySeen) accentPurple else textSecondary)
                        },
                        trailingIcon = {
                            if (previouslySeen) {
                                Icon(Icons.Filled.History, null, tint = accentPurple,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    )

                    if (previouslySeen) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("⟳ Previously scanned", fontSize = 11.sp, color = accentPurple)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Gradient scan button
                    Button(
                        onClick = {
                            if (urlInput.isNotEmpty()) {
                                scope.launch {
                                    isLoading = true
                                    loadingStep = 0
                                    scanResult = null
                                    errorMessage = ""
                                    scannedUrl = urlInput.trim()
                                    try {
                                        // Animate loading steps
                                        delay(600); loadingStep = 1
                                        delay(600); loadingStep = 2
                                        delay(600); loadingStep = 3
                                        val result = scanUrl(urlInput.trim())
                                        delay(400); loadingStep = 4
                                        delay(300)
                                        scanResult = result
                                        val historyItem = ScanHistoryItem(
                                            url = if (urlInput.startsWith("http"))
                                                urlInput.trim() else "https://${urlInput.trim()}",
                                            prediction = result.prediction,
                                            confidence = result.confidence
                                        )
                                        addToHistory(context, historyItem)
                                        scanHistory = getScanHistory(context)
                                    } catch (e: java.net.UnknownHostException) {
                                        errorMessage = "No internet connection. Please check your network."
                                    } catch (e: java.net.SocketTimeoutException) {
                                        errorMessage = "Request timed out. The server may be waking up — try again in 30 seconds."
                                    } catch (e: Exception) {
                                        errorMessage = "Something went wrong. Please try again."
                                    }
                                    isLoading = false
                                    loadingStep = 0
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading && urlInput.isNotEmpty(),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (!isLoading && urlInput.isNotEmpty())
                                        Brush.linearGradient(listOf(accentBlue, accentPurple))
                                    else Brush.linearGradient(listOf(borderColor, borderColor)),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Filled.Search, null, tint = Color.White,
                                    modifier = Modifier.size(18.dp))
                                Text("Scan URL", fontSize = 15.sp, color = Color.White,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Loading Steps ────────────────────────────────────
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut()
            ) {
                LoadingStepsCard(
                    step = loadingStep,
                    url = scannedUrl,
                    cardColor = cardColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )
            }

            // ── Error ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = errorMessage.isNotEmpty(),
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A1A))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = dangerRed, modifier = Modifier.size(20.dp))
                        Text(errorMessage, color = dangerRed, fontSize = 14.sp)
                    }
                }
            }

            // ── Result Card ──────────────────────────────────────
            AnimatedVisibility(
                visible = scanResult != null && !isLoading,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut()
            ) {
                scanResult?.let { result ->
                    val isPhishing = result.prediction == "phishing"
                    val resultColor = if (isPhishing) dangerRed else safeGreen
                    val resultBg = if (isPhishing) Color(0xFF2A1A1A) else Color(0xFF0F2A1A)

                    Column {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = resultBg)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(resultColor.copy(alpha = 0.15f))
                                        .border(2.dp, resultColor, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isPhishing) Icons.Filled.Warning
                                        else Icons.Filled.CheckCircle,
                                        contentDescription = null, tint = resultColor,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    if (isPhishing) "Phishing Detected!" else "URL is Safe",
                                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = resultColor
                                )
                                Text(
                                    if (isPhishing) "High-risk URL — do not visit"
                                    else "No significant threats detected",
                                    fontSize = 13.sp, color = textSecondary, textAlign = TextAlign.Center
                                )

                                // Scanned URL chip
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF0A0E1A)) {
                                    Text(
                                        scannedUrl, fontSize = 11.sp, color = textSecondary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = borderColor)
                                Spacer(modifier = Modifier.height(16.dp))

                                // Metrics
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    MetricItem("${result.phishingProbability}%", "Threat Score", resultColor)
                                    MetricItem(if (isPhishing) "HIGH" else "LOW", "Risk Level", resultColor)
                                    MetricItem("${result.confidence}%", "Confidence", accentBlue)
                                }

                                // Threat level bar
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Threat level", fontSize = 11.sp, color = textSecondary)
                                    Text(
                                        "${result.phishingProbability}% — ${
                                            when {
                                                result.phishingProbability >= 75 -> "Critical"
                                                result.phishingProbability >= 50 -> "High"
                                                result.phishingProbability >= 25 -> "Medium"
                                                else -> "Low"
                                            }
                                        }",
                                        fontSize = 11.sp, color = resultColor
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)).background(borderColor)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((result.phishingProbability / 100).toFloat().coerceIn(0f, 1f))
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(resultColor)
                                    )
                                }

                                // Why flagged box
                                if (isPhishing) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = dangerRed.copy(alpha = 0.1f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("Why flagged:", fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold, color = dangerRed)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                if (result.note.isNotEmpty()) result.note
                                                else "Suspicious URL patterns detected by AI analysis.",
                                                fontSize = 12.sp, color = textSecondary
                                            )
                                        }
                                    }
                                }

                                // Whitelist note
                                if (result.note.isNotEmpty() && !isPhishing) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("ℹ️ ${result.note}", fontSize = 11.sp,
                                        color = textSecondary, textAlign = TextAlign.Center)
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Action buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Scan Another URL
                                    OutlinedButton(
                                        onClick = {
                                            urlInput = ""
                                            scanResult = null
                                            errorMessage = ""
                                        },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, accentBlue),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentBlue)
                                    ) {
                                        Text("Scan Another", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }

                                    // Report button (phishing only)
                                    if (isPhishing) {
                                        val ctx = LocalContext.current
                                        Button(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                                    data = Uri.parse("mailto:")
                                                    putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
                                                    putExtra(Intent.EXTRA_SUBJECT, "[Guardian] Phishing Report: $scannedUrl")
                                                    putExtra(Intent.EXTRA_TEXT,
                                                        "Hi Guardian Team,\n\nI'd like to report the following URL as phishing:\n\n$scannedUrl\n\nConfidence: ${result.confidence}%\nThreat Score: ${result.phishingProbability}%\n\nAdditional notes:\n[Add your observations here]\n\nReported via Guardian AI App.")
                                                }
                                                ctx.startActivity(intent)
                                            },
                                            modifier = Modifier.weight(1f).height(44.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = dangerRed),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Filled.Flag, null,
                                                modifier = Modifier.size(14.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Report", fontSize = 13.sp, color = Color.White,
                                                fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // ── Recent Scans / Empty State ───────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("RECENT SCANS", fontSize = 11.sp, color = textSecondary,
                        fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    if (scanHistory.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Filled.History, null, tint = borderColor,
                                modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("No scans yet", fontSize = 15.sp,
                                fontWeight = FontWeight.Medium, color = textSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Paste any link above to check\nif it's safe or malicious.",
                                fontSize = 12.sp, color = borderColor, textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        scanHistory.take(4).forEachIndexed { index, item ->
                            HistoryItem(item, safeGreen, dangerRed, textSecondary, borderColor)
                            if (index < minOf(3, scanHistory.size - 1)) {
                                HorizontalDivider(color = borderColor,
                                    modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }

        // ── Drawer Overlay ───────────────────────────────────────
        if (showDrawer) {
            DrawerMenu(
                userName = currentUserName,
                themePreference = themePreference,
                scanHistory = scanHistory,
                onThemeChange = onThemeChange,
                onNameChange = { newName ->
                    onNameChange(newName)
                    currentUserName = newName
                },
                onDismiss = { showDrawer = false },
                context = context,
                textSecondary = Color(0xFF94A3B8),
                borderColor = Color(0xFF2A3441)
            )
        }
    }
}

// ─── Loading Steps Card ──────────────────────────────────────────────────────────
@Composable
fun LoadingStepsCard(
    step: Int,
    url: String,
    cardColor: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    val accentBlue = Color(0xFF3B82F6)
    val accentPurple = Color(0xFF8B5CF6)

    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "loadingScale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .scale(scale)
                    .size(160.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(Color(0xFF1A1E3A)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.guardian_icon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(140.dp)
                        .padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Analysing URL...", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF0A0E1A)) {
                Text(url, fontSize = 11.sp, color = textSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.height(20.dp))

            val steps = listOf("Domain lookup", "SSL certificate check", "AI threat analysis", "Generating report")
            steps.forEachIndexed { index, stepName ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    index < step -> Color(0xFF10B981)
                                    index == step -> accentBlue
                                    else -> Color(0xFF2A3441)
                                }
                            )
                    )
                    Text(
                        stepName,
                        fontSize = 13.sp,
                        fontWeight = if (index == step) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            index < step -> Color(0xFF10B981)
                            index == step -> textPrimary
                            else -> Color(0xFF4B5563)
                        }
                    )
                }
            }
        }
    }
}

// ─── Drawer Menu ─────────────────────────────────────────────────────────────────
@Composable
fun DrawerMenu(
    userName: String,
    themePreference: String,
    scanHistory: List<ScanHistoryItem>,
    onThemeChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    context: Context,
    textSecondary: Color,
    borderColor: Color
) {
    var currentSection by remember { mutableStateOf("main") }
    var nameInput by remember { mutableStateOf(userName) }
    var nameSaved by remember { mutableStateOf(false) }

    val accentBlue = Color(0xFF3B82F6)
    val accentPurple = Color(0xFF8B5CF6)
    val safeGreen = Color(0xFF10B981)
    val dangerRed = Color(0xFFEF4444)
    val drawerBg = Color(0xFF0F0F23)
    val cardColor = Color(0xFF1A1F2E)
    val textPrimary = Color.White

    Box(modifier = Modifier.fillMaxSize()) {
        // Dim background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { onDismiss() }
        )

        // Drawer panel
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.85f)
                .align(Alignment.CenterStart)
                .background(drawerBg)
                .clickable { }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                // Drawer header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(listOf(accentBlue.copy(alpha = 0.3f), accentPurple.copy(alpha = 0.3f)))
                        )
                        .padding(24.dp)
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(32.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.guardian_icon),
                                    contentDescription = "Guardian",
                                    modifier = Modifier.size(52.dp)
                                )
                            }
                            Column {
                                Text("The Guardian AI", fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold, color = textPrimary)
                                Text("AI-Powered Phishing Detection", fontSize = 12.sp, color = textSecondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                when (currentSection) {
                    "main" -> {
                        DrawerItem(Icons.Filled.History, "Scan History") { currentSection = "history" }
                        DrawerItem(Icons.Filled.Settings, "Settings") { currentSection = "settings" }
                        DrawerItem(Icons.Filled.Info, "About") { currentSection = "about" }
                        DrawerItem(Icons.Filled.BugReport, "Report an Issue") { currentSection = "report" }
                        DrawerItem(Icons.Filled.Gavel, "Disclaimer") { currentSection = "disclaimer" }
                    }

                    "history" -> {
                        DrawerSectionHeader("Scan History") { currentSection = "main" }
                        if (scanHistory.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.History, null, tint = borderColor,
                                        modifier = Modifier.size(40.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No scans yet", color = textSecondary, fontSize = 14.sp)
                                }
                            }
                        } else {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                scanHistory.forEach { item ->
                                    HistoryItem(item, safeGreen, dangerRed, textSecondary, borderColor)
                                    HorizontalDivider(color = borderColor,
                                        modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }
                    }

                    "settings" -> {
                        DrawerSectionHeader("Settings") { currentSection = "main" }
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Name change
                            Text("PROFILE", fontSize = 11.sp, color = textSecondary,
                                letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = cardColor)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Display Name", fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium, color = textPrimary)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = nameInput,
                                        onValueChange = { nameInput = it; nameSaved = false },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = accentBlue,
                                            unfocusedBorderColor = borderColor,
                                            focusedTextColor = textPrimary,
                                            unfocusedTextColor = textPrimary,
                                            cursorColor = accentBlue
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        singleLine = true,
                                        leadingIcon = {
                                            Icon(Icons.Filled.Person, null, tint = textSecondary)
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            if (nameInput.isNotBlank()) {
                                                onNameChange(nameInput.trim())
                                                nameSaved = true
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize()
                                                .background(
                                                    Brush.linearGradient(listOf(accentBlue, accentPurple)),
                                                    shape = RoundedCornerShape(10.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                if (nameSaved) "✓ Saved!" else "Save Name",
                                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                                color = if (nameSaved) safeGreen else Color.White
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // Theme
                            Text("APPEARANCE", fontSize = 11.sp, color = textSecondary,
                                letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = cardColor)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    listOf(
                                        Pair("system", "System Default"),
                                        Pair("dark", "Dark"),
                                        Pair("light", "Light")
                                    ).forEach { (value, label) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(
                                                    width = if (themePreference == value) 1.5.dp else 1.dp,
                                                    color = if (themePreference == value) accentBlue else borderColor,
                                                    shape = RoundedCornerShape(10.dp)
                                                )
                                                .clickable { onThemeChange(value) }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(label, fontSize = 14.sp,
                                                color = if (themePreference == value) accentBlue else textPrimary,
                                                fontWeight = if (themePreference == value) FontWeight.SemiBold else FontWeight.Normal)
                                            if (themePreference == value) {
                                                Icon(Icons.Filled.CheckCircle, null,
                                                    tint = accentBlue, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        if (value != "light") Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }

                    "about" -> {
                        DrawerSectionHeader("About") { currentSection = "main" }
                        Column(modifier = Modifier.padding(16.dp)) {
                            Card(modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = cardColor)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    AboutRow("App Name", "The Guardian AI", textPrimary, textSecondary, borderColor)
                                    AboutRow("Version", "1.0.0", textPrimary, textSecondary, borderColor)
                                    AboutRow("Model Accuracy", "99%", textPrimary, safeGreen, borderColor)
                                    AboutRow("Training Data", "450,000+ URLs", textPrimary, textSecondary, borderColor)
                                    AboutRow("Detection Layers", "3 (Whitelist + Brand + AI)", textPrimary, textSecondary, borderColor)
                                    HorizontalDivider(color = borderColor, modifier = Modifier.padding(vertical = 8.dp))
                                    Text(
                                        "Guardian AI uses a machine learning model trained on over 450,000 URLs combined with domain whitelisting and brand impersonation detection to protect you from phishing attacks in real time.",
                                        fontSize = 12.sp, color = textSecondary, lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }

                    "report" -> {
                        DrawerSectionHeader("Report an Issue") { currentSection = "main" }
                        Column(modifier = Modifier.padding(16.dp)) {
                            Card(modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = cardColor)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Found a bug or have feedback?", fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold, color = textPrimary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "We'd love to hear from you. Tap below to send us an email with your issue or suggestion. Your feedback helps us improve Guardian for everyone.",
                                        fontSize = 13.sp, color = textSecondary, lineHeight = 19.sp
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    val ctx = LocalContext.current
                                    Button(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                                data = Uri.parse("mailto:")
                                                putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
                                                putExtra(Intent.EXTRA_SUBJECT, "[Guardian AI] Issue Report")
                                                putExtra(Intent.EXTRA_TEXT,
                                                    "Hi Guardian Team,\n\nI'd like to report the following issue:\n\n[Describe your issue here]\n\nApp Version: 1.0.0\nDevice: [Your device]\n\nThank you.")
                                            }
                                            ctx.startActivity(intent)
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize()
                                                .background(
                                                    Brush.linearGradient(listOf(accentBlue, accentPurple)),
                                                    shape = RoundedCornerShape(10.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Filled.Email, null, tint = Color.White,
                                                    modifier = Modifier.size(16.dp))
                                                Text("Send Email", fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "disclaimer" -> {
                        DrawerSectionHeader("Disclaimer") { currentSection = "main" }
                        Column(modifier = Modifier.padding(16.dp)) {
                            Card(modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = cardColor)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Important Notice", fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold, color = textPrimary)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        "Guardian AI is designed to assist users in identifying potentially malicious URLs. While our model achieves 99% accuracy on our test dataset, no system is perfect.\n\n" +
                                        "Guardian AI does not guarantee that all phishing URLs will be detected, nor that all flagged URLs are malicious. Users should exercise their own judgment before visiting any URL.\n\n" +
                                        "Guardian AI does not collect, store, or share any personal data. All scan history is stored locally on your device and is never transmitted to our servers.\n\n" +
                                        "This application is provided as-is for educational and protective purposes. The developers are not liable for any damages arising from the use or misuse of this application.",
                                        fontSize = 13.sp, color = textSecondary, lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Drawer Components ───────────────────────────────────────────────────────────
@Composable
fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(22.dp))
        Text(label, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = Color(0xFF4B5563), modifier = Modifier.size(18.dp))
    }
}

@Composable
fun DrawerSectionHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
    HorizontalDivider(color = Color(0xFF2A3441), modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
fun AboutRow(label: String, value: String, textPrimary: Color, valueColor: Color, borderColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFF94A3B8))
        Text(value, fontSize = 13.sp, color = valueColor, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = borderColor)
}

// ─── Reusable Components ─────────────────────────────────────────────────────────
@Composable
fun LiveStatusPill() {
    val infiniteTransition = rememberInfiniteTransition(label = "live")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800), repeatMode = RepeatMode.Reverse
        ), label = "dotAlpha"
    )
    Surface(shape = RoundedCornerShape(100.dp), color = Color(0xFF1A1F2E)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape)
                .background(Color(0xFF10B981).copy(alpha = alpha)))
            Text("API Live", fontSize = 11.sp, color = Color(0xFF10B981))
        }
    }
}

@Composable
fun StatusPill(text: String, textColor: Color, bgColor: Color) {
    Surface(shape = RoundedCornerShape(100.dp), color = bgColor) {
        Text(text, fontSize = 11.sp, color = textColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
fun MetricItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = Color(0xFF94A3B8))
    }
}

@Composable
fun HistoryItem(
    item: ScanHistoryItem,
    safeGreen: Color,
    dangerRed: Color,
    textSecondary: Color,
    borderColor: Color
) {
    val isPhishing = item.prediction == "phishing"
    val dotColor = if (isPhishing) dangerRed else safeGreen
    val badgeColor = if (isPhishing) dangerRed else safeGreen
    val badgeBg = if (isPhishing) dangerRed.copy(alpha = 0.1f) else safeGreen.copy(alpha = 0.1f)
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val timeStr = timeFormat.format(Date(item.timestamp))

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(item.url, fontSize = 12.sp, color = textSecondary,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(shape = RoundedCornerShape(100.dp), color = badgeBg) {
            Text(
                if (isPhishing) "Phishing" else "Safe",
                fontSize = 10.sp, color = badgeColor, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Text(timeStr, fontSize = 10.sp, color = Color(0xFF4B5563))
    }
}

// ─── API Call ─────────────────────────────────────────────────────────────────────
suspend fun scanUrl(url: String): ScanResult {
    return withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val apiKey = BuildConfig.API_KEY
        val apiUrl = "https://phishing-api-j7fs.onrender.com/predict"

        var processedUrl = url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            processedUrl = "https://$url"
        }

        val json = JSONObject()
        json.put("url", processedUrl)

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .addHeader("x-api-key", apiKey)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val jsonResponse = JSONObject(responseBody)

        ScanResult(
            prediction = jsonResponse.getString("prediction"),
            confidence = jsonResponse.getDouble("confidence"),
            phishingProbability = jsonResponse.getDouble("phishing_probability"),
            note = if (jsonResponse.has("note")) jsonResponse.getString("note") else ""
        )
    }
}