package de.minitraxx.whisperflow

import android.Manifest
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.minitraxx.whisperflow.service.FloatingButtonService
import de.minitraxx.whisperflow.ui.theme.WhisperFlowTheme

class MainActivity : ComponentActivity() {

    private var refreshTrigger by mutableStateOf(0)

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTrigger++ }

    override fun onResume() {
        super.onResume()
        refreshTrigger++
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
                MainScreen(
                    overlayGranted = overlayGranted,
                    micGranted = micGranted,
                    serviceRunning = serviceRunning,
                    onRequestOverlay = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    },
                    onRequestMic = {
                        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStartService = { FloatingButtonService.start(this) },
                    onStopService = { FloatingButtonService.stop(this) }
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
    onRequestOverlay: () -> Unit,
    onRequestMic: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
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
            number = "1",
            title = "Overlay-Berechtigung",
            description = "WhisperFlow braucht die Erlaubnis, einen Button über anderen Apps anzuzeigen.",
            done = overlayGranted,
            actionLabel = "Berechtigung erteilen",
            showAction = !overlayGranted,
            onAction = onRequestOverlay
        )

        Spacer(Modifier.height(12.dp))

        SetupStep(
            number = "2",
            title = "Mikrofon-Berechtigung",
            description = "Benötigt damit du später diktieren kannst.",
            done = micGranted,
            actionLabel = "Erlauben",
            showAction = overlayGranted && !micGranted,
            onAction = onRequestMic
        )

        Spacer(Modifier.height(12.dp))

        SetupStep(
            number = "3",
            title = "Floating Button",
            description = "Der schwebende Mikrofon-Button erscheint über allen Apps — verschiebbar, immer griffbereit.",
            done = serviceRunning,
            actionLabel = if (serviceRunning) "Button stoppen" else "Button starten",
            showAction = overlayGranted && micGranted,
            actionDestructive = serviceRunning,
            onAction = if (serviceRunning) onStopService else onStartService
        )

        Spacer(Modifier.weight(1f))

        Text(
            "Milestone 1 von 8  ·  Floating Button",
            fontSize = 12.sp,
            color = Color(0xFF3A3A3C),
            modifier = Modifier.padding(bottom = 24.dp)
        )
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
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
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
