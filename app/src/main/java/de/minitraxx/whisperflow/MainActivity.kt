package de.minitraxx.whisperflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.minitraxx.whisperflow.service.FloatingButtonService
import de.minitraxx.whisperflow.service.WhisperAccessibilityService
import de.minitraxx.whisperflow.ui.theme.WhisperFlowTheme
import de.minitraxx.whisperflow.util.CostTracker

class MainActivity : ComponentActivity() {

    private var refreshTrigger by mutableStateOf(0)
    private var userManuallyStopped = false

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTrigger++ }

    override fun onResume() {
        super.onResume()
        refreshTrigger++
        tryAutoStart()
    }

    private fun tryAutoStart() {
        val canStart = Settings.canDrawOverlays(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (canStart && !FloatingButtonService.isRunning && !userManuallyStopped) {
            FloatingButtonService.start(this)
            refreshTrigger++
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val name = "${packageName}/${WhisperAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val refresh = refreshTrigger
            WhisperFlowTheme {
                val overlayGranted = remember(refresh) { Settings.canDrawOverlays(this) }
                val micGranted = remember(refresh) {
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                }
                val serviceRunning = remember(refresh) { FloatingButtonService.isRunning }
                val accessibilityEnabled = remember(refresh) { isAccessibilityServiceEnabled() }

                val prefs = remember { getSharedPreferences(FloatingButtonService.PREFS_NAME, Context.MODE_PRIVATE) }
                var styleProfile by remember { mutableStateOf(prefs.getString(FloatingButtonService.KEY_STYLE_PROFILE, FloatingButtonService.PROFILE_WHATSAPP) ?: FloatingButtonService.PROFILE_WHATSAPP) }
                var openAiKey by remember { mutableStateOf((prefs.getString(FloatingButtonService.KEY_OPENAI_API_KEY, "") ?: "").trim()) }
                var anthropicKey by remember { mutableStateOf((prefs.getString(FloatingButtonService.KEY_ANTHROPIC_API_KEY, "") ?: "").trim()) }
                var language by remember { mutableStateOf((prefs.getString(FloatingButtonService.KEY_LANGUAGE, "") ?: "")) }
                var previewEnabled by remember { mutableStateOf(prefs.getBoolean(FloatingButtonService.KEY_PREVIEW_ENABLED, false)) }

                val spent = remember(refresh) { CostTracker.getSpent(this) }
                val todaySpent = remember(refresh) { CostTracker.getTodaySpent(this) }
                val budget = remember(refresh) { CostTracker.getBudget(this) }

                MainScreen(
                    overlayGranted = overlayGranted,
                    micGranted = micGranted,
                    serviceRunning = serviceRunning,
                    accessibilityEnabled = accessibilityEnabled,
                    openAiKey = openAiKey,
                    anthropicKey = anthropicKey,
                    language = language,
                    previewEnabled = previewEnabled,
                    spent = spent,
                    todaySpent = todaySpent,
                    budget = budget,
                    onRequestOverlay = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    },
                    onRequestMic = { requestMicPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onStartService = {
                        userManuallyStopped = false
                        FloatingButtonService.start(this)
                        refreshTrigger++
                    },
                    onStopService = {
                        userManuallyStopped = true
                        FloatingButtonService.stop(this)
                        refreshTrigger++
                    },
                    onRequestAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    styleProfile = styleProfile,
                    onStyleProfileChange = { profile ->
                        styleProfile = profile
                        prefs.edit().putString(FloatingButtonService.KEY_STYLE_PROFILE, profile).apply()
                    },
                    onOpenAiKeyChange = { key ->
                        openAiKey = key.trim()
                        prefs.edit().putString(FloatingButtonService.KEY_OPENAI_API_KEY, key.trim()).apply()
                    },
                    onAnthropicKeyChange = { key ->
                        anthropicKey = key.trim()
                        prefs.edit().putString(FloatingButtonService.KEY_ANTHROPIC_API_KEY, key.trim()).apply()
                    },
                    onLanguageChange = { lang ->
                        language = lang
                        prefs.edit().putString(FloatingButtonService.KEY_LANGUAGE, lang).apply()
                    },
                    onPreviewEnabledChange = { enabled ->
                        previewEnabled = enabled
                        prefs.edit().putBoolean(FloatingButtonService.KEY_PREVIEW_ENABLED, enabled).apply()
                    },
                    onResetBudget = {
                        CostTracker.reset(this)
                        refreshTrigger++
                    },
                    onBudgetLimitChange = { eur ->
                        CostTracker.setBudget(eur, this)
                        refreshTrigger++
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    overlayGranted: Boolean,
    micGranted: Boolean,
    serviceRunning: Boolean,
    accessibilityEnabled: Boolean,
    openAiKey: String,
    anthropicKey: String,
    language: String,
    previewEnabled: Boolean,
    spent: Double,
    todaySpent: Double,
    budget: Double,
    styleProfile: String,
    onRequestOverlay: () -> Unit,
    onRequestMic: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onStyleProfileChange: (String) -> Unit,
    onOpenAiKeyChange: (String) -> Unit,
    onAnthropicKeyChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onPreviewEnabledChange: (Boolean) -> Unit,
    onResetBudget: () -> Unit,
    onBudgetLimitChange: (Double) -> Unit
) {
    val allSetUp = overlayGranted && micGranted
    val budgetExceeded = spent >= budget
    val remaining = (budget - spent).coerceAtLeast(0.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        Text("WhisperFlow", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Diktieren. Korrigieren. Senden.",
            fontSize = 15.sp,
            color = Color(0xFF8E8E93),
            modifier = Modifier.padding(top = 6.dp, bottom = 40.dp)
        )

        SetupStep(
            number = "1", title = "Overlay-Berechtigung",
            description = "Erlaubnis, den Button über anderen Apps anzuzeigen.",
            done = overlayGranted, actionLabel = "Berechtigung erteilen",
            showAction = !overlayGranted, onAction = onRequestOverlay
        )
        Spacer(Modifier.height(12.dp))
        SetupStep(
            number = "2", title = "Mikrofon-Berechtigung",
            description = "Benötigt für die Sprachaufnahme.",
            done = micGranted, actionLabel = "Erlauben",
            showAction = overlayGranted && !micGranted, onAction = onRequestMic
        )
        Spacer(Modifier.height(12.dp))
        SetupStep(
            number = "3", title = "Floating Button",
            description = "Tippen = Start/Stopp  ·  Gedrückt halten = Walkie-Talkie  ·  Nach oben wischen = Profil wechseln",
            done = serviceRunning,
            actionLabel = if (serviceRunning) "Button stoppen" else "Button starten",
            showAction = allSetUp,
            actionDestructive = serviceRunning,
            onAction = if (serviceRunning) onStopService else onStartService
        )
        Spacer(Modifier.height(12.dp))
        SetupStep(
            number = "4", title = "Texteingabe aktivieren",
            description = "WhisperFlow darf direkt in andere Apps schreiben. Einmalig aktivieren — dann läuft es für immer.",
            done = accessibilityEnabled,
            actionLabel = "Aktivieren",
            showAction = allSetUp && !accessibilityEnabled,
            onAction = onRequestAccessibility
        )

        if (allSetUp) {
            Spacer(Modifier.height(24.dp))
            ApiKeyCard(
                openAiKey = openAiKey,
                anthropicKey = anthropicKey,
                onOpenAiKeyChange = onOpenAiKeyChange,
                onAnthropicKeyChange = onAnthropicKeyChange
            )
            Spacer(Modifier.height(12.dp))
            ProfileCard(currentProfile = styleProfile, onProfileChange = onStyleProfileChange)
            Spacer(Modifier.height(12.dp))
            SettingsCard(
                language = language,
                previewEnabled = previewEnabled,
                onLanguageChange = onLanguageChange,
                onPreviewEnabledChange = onPreviewEnabledChange
            )
            Spacer(Modifier.height(12.dp))
            BudgetCard(
                spent = spent, todaySpent = todaySpent, budget = budget, remaining = remaining,
                exceeded = budgetExceeded, onReset = onResetBudget,
                onBudgetLimitChange = onBudgetLimitChange
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun ApiKeyCard(
    openAiKey: String,
    anthropicKey: String,
    onOpenAiKeyChange: (String) -> Unit,
    onAnthropicKeyChange: (String) -> Unit
) {
    var showOpenAi by remember { mutableStateOf(false) }
    var showAnthropic by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("API-Keys", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "Einmalig eingeben — bleibt gespeichert",
                        color = Color(0xFF8E8E93), fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (openAiKey.isNotBlank()) Color(0xFF30D158) else Color(0xFFFF453A),
                            RoundedCornerShape(50)
                        )
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = openAiKey,
                onValueChange = onOpenAiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenAI Key (sk-...)", color = Color(0xFF8E8E93), fontSize = 12.sp) },
                visualTransformation = if (showOpenAi) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showOpenAi = !showOpenAi }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (showOpenAi) "Verbergen" else "Zeigen", color = Color(0xFF0A84FF), fontSize = 12.sp)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF0A84FF), unfocusedBorderColor = Color(0xFF3A3A3C),
                    cursorColor = Color(0xFF0A84FF)
                ),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = anthropicKey,
                onValueChange = onAnthropicKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Anthropic Key (optional, für Stilkorrektur)", color = Color(0xFF8E8E93), fontSize = 12.sp) },
                visualTransformation = if (showAnthropic) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showAnthropic = !showAnthropic }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (showAnthropic) "Verbergen" else "Zeigen", color = Color(0xFF0A84FF), fontSize = 12.sp)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF0A84FF), unfocusedBorderColor = Color(0xFF3A3A3C),
                    cursorColor = Color(0xFF0A84FF)
                ),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
        }
    }
}

@Composable
fun ProfileCard(currentProfile: String, onProfileChange: (String) -> Unit) {
    val profiles = listOf("whatsapp" to "WhatsApp", "professional" to "Professionell", "formal" to "Formal")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Stil-Profil", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "Wie soll Claude den Text formulieren?  ·  Button nach oben wischen = schnell wechseln",
                color = Color(0xFF8E8E93), fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { (key, label) ->
                    val selected = currentProfile == key
                    Button(
                        onClick = { onProfileChange(key) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF0A84FF) else Color(0xFF2C2C2E)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(label, fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCard(
    language: String,
    previewEnabled: Boolean,
    onLanguageChange: (String) -> Unit,
    onPreviewEnabledChange: (Boolean) -> Unit
) {
    val languages = listOf("" to "Auto", "de" to "DE", "en" to "EN", "fr" to "FR", "es" to "ES")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Einstellungen", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))

            Text("Sprache", color = Color(0xFF8E8E93), fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.forEach { (code, label) ->
                    val selected = language == code
                    Button(
                        onClick = { onLanguageChange(code) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF0A84FF) else Color(0xFF2C2C2E)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                    ) {
                        Text(label, fontSize = 12.sp, color = Color.White)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Vorschau vor Einfügen", color = Color.White, fontSize = 14.sp)
                    Text(
                        "Text prüfen bevor er eingefügt wird",
                        color = Color(0xFF8E8E93), fontSize = 12.sp
                    )
                }
                Switch(
                    checked = previewEnabled,
                    onCheckedChange = onPreviewEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF0A84FF),
                        uncheckedThumbColor = Color(0xFF8E8E93),
                        uncheckedTrackColor = Color(0xFF2C2C2E)
                    )
                )
            }
        }
    }
}

@Composable
fun BudgetCard(
    spent: Double,
    todaySpent: Double,
    budget: Double,
    remaining: Double,
    exceeded: Boolean,
    onReset: () -> Unit,
    onBudgetLimitChange: (Double) -> Unit
) {
    var budgetInput by remember(budget) { mutableStateOf("%.0f".format(budget)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Guthaben",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (exceeded) "Aufgebraucht" else "%.3f € übrig".format(remaining),
                    color = if (exceeded) Color(0xFFFF453A) else Color(0xFF30D158),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (spent / budget).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (exceeded) Color(0xFFFF453A) else Color(0xFF0A84FF),
                trackColor = Color(0xFF2C2C2E)
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Heute", color = Color(0xFF8E8E93), fontSize = 11.sp)
                    Text("%.4f €".format(todaySpent), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Gesamt", color = Color(0xFF8E8E93), fontSize = 11.sp)
                    Text("%.4f €".format(spent), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Limit", color = Color(0xFF8E8E93), fontSize = 11.sp)
                    OutlinedTextField(
                        value = budgetInput,
                        onValueChange = { v ->
                            budgetInput = v.filter { it.isDigit() || it == '.' }
                            v.toDoubleOrNull()?.let { if (it > 0) onBudgetLimitChange(it) }
                        },
                        modifier = Modifier.width(72.dp),
                        suffix = { Text("€", color = Color(0xFF8E8E93), fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0A84FF), unfocusedBorderColor = Color(0xFF3A3A3C),
                            cursorColor = Color(0xFF0A84FF)
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                }
            }
            if (exceeded) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Guthaben aufgebraucht — platform.openai.com aufladen, dann Reset.",
                        color = Color(0xFFFF453A), fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Reset", color = Color(0xFF0A84FF), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SetupStep(
    number: String,
    title: String,
    description: String,
    done: Boolean,
    actionLabel: String,
    showAction: Boolean,
    actionDestructive: Boolean = false,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (done) Color(0xFF30D158) else Color(0xFF2C2C2E),
                        RoundedCornerShape(50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (done) "✓" else number,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    description,
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (showAction) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (actionDestructive) Color(0xFFFF453A) else Color(0xFF0A84FF)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text(actionLabel, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
