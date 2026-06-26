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
                var openAiKey by remember { mutableStateOf(prefs.getString(FloatingButtonService.KEY_OPENAI_API_KEY, "") ?: "") }
                var anthropicKey by remember { mutableStateOf(prefs.getString(FloatingButtonService.KEY_ANTHROPIC_API_KEY, "") ?: "") }

                val spent = remember(refresh) { CostTracker.getSpent(this) }
                val budget = remember(refresh) { CostTracker.getBudget(this) }

                MainScreen(
                    overlayGranted = overlayGranted,
                    micGranted = micGranted,
                    serviceRunning = serviceRunning,
                    accessibilityEnabled = accessibilityEnabled,
                    openAiKey = openAiKey,
                    anthropicKey = anthropicKey,
                    spent = spent,
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
                    onOpenAiKeyChange = { key ->
                        openAiKey = key.trim()
                        prefs.edit().putString(FloatingButtonService.KEY_OPENAI_API_KEY, key.trim()).apply()
                    },
                    onAnthropicKeyChange = { key ->
                        anthropicKey = key.trim()
                        prefs.edit().putString(FloatingButtonService.KEY_ANTHROPIC_API_KEY, key.trim()).apply()
                    },
                    onResetBudget = {
                        CostTracker.reset(this)
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
    spent: Double,
    budget: Double,
    onRequestOverlay: () -> Unit,
    onRequestMic: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onOpenAiKeyChange: (String) -> Unit,
    onAnthropicKeyChange: (String) -> Unit,
    onResetBudget: () -> Unit
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
            description = "Tippen = Start/Stopp  ·  Gedrückt halten = Walkie-Talkie",
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
            val openAiValid = openAiKey.startsWith("sk-") && openAiKey.length > 10

            Spacer(Modifier.height(24.dp))
            ApiKeyCard(
                label = "OpenAI API-Key",
                hint = "platform.openai.com → API keys",
                placeholder = "sk-...",
                apiKey = openAiKey,
                isValid = openAiValid,
                onApiKeyChange = onOpenAiKeyChange
            )
            Spacer(Modifier.height(12.dp))
            ApiKeyCard(
                label = "Anthropic API-Key (optional)",
                hint = "Für Stil-Korrektur · console.anthropic.com → API keys",
                placeholder = "sk-ant-...",
                apiKey = anthropicKey,
                isValid = anthropicKey.startsWith("sk-ant-") && anthropicKey.length > 10,
                onApiKeyChange = onAnthropicKeyChange
            )
            if (openAiValid) {
                Spacer(Modifier.height(12.dp))
                BudgetCard(
                    spent = spent, budget = budget, remaining = remaining,
                    exceeded = budgetExceeded, onReset = onResetBudget
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Milestone 4+5 von 8  ·  Claude Korrektur + Texteingabe",
            fontSize = 12.sp,
            color = Color(0xFF3A3A3C),
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

@Composable
fun BudgetCard(
    spent: Double,
    budget: Double,
    remaining: Double,
    exceeded: Boolean,
    onReset: () -> Unit
) {
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
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%.4f € von %.2f € genutzt".format(spent, budget),
                    color = Color(0xFF8E8E93),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                if (exceeded) {
                    TextButton(
                        onClick = onReset,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Reset", color = Color(0xFF0A84FF), fontSize = 12.sp)
                    }
                }
            }
            if (exceeded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Guthaben aufgebraucht. Konto auf platform.openai.com aufladen, dann Reset tippen.",
                    color = Color(0xFFFF453A),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun ApiKeyCard(
    label: String,
    hint: String,
    placeholder: String,
    apiKey: String,
    isValid: Boolean,
    onApiKeyChange: (String) -> Unit
) {
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                hint,
                color = Color(0xFF8E8E93),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(placeholder, color = Color(0xFF48484A)) },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(
                            if (showKey) "Verbergen" else "Anzeigen",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF0A84FF),
                    unfocusedBorderColor = Color(0xFF3A3A3C),
                    cursorColor = Color(0xFF0A84FF)
                ),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
            if (isValid) {
                Spacer(Modifier.height(6.dp))
                Text("Key erkannt — aktiv", color = Color(0xFF30D158), fontSize = 12.sp)
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
