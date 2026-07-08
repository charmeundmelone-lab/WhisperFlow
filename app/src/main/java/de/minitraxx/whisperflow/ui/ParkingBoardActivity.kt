package de.minitraxx.whisperflow.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.minitraxx.whisperflow.ui.theme.WhisperFlowTheme
import de.minitraxx.whisperflow.util.ParkItem
import de.minitraxx.whisperflow.util.ParkStatus
import de.minitraxx.whisperflow.util.ParkingBoardStore
import de.minitraxx.whisperflow.util.PomodoroManager
import kotlinx.coroutines.delay

private val COLOR_BACKLOG = Color(0xFFD9695A)
private val COLOR_PROGRESS = Color(0xFFFFD60A)
private val COLOR_DONE = Color(0xFF6FAE7C)
private val COLOR_MUTED = Color(0xFF8E8E93)
private val COLOR_CARD = Color(0xFF1C1C1E)

class ParkingBoardActivity : ComponentActivity() {
    // Wird bei onResume erhöht → beim Zurückkehren aus der App (z.B. nachdem eine
    // Pomodoro-Benachrichtigung bei ausgeschaltetem Bildschirm den Status geändert
    // hat) wird die Liste frisch von der Platte geladen. Gleiches Muster wie MainActivity.
    private var refreshTrigger by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        refreshTrigger++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhisperFlowTheme {
                val ctx = this
                var items by remember(refreshTrigger) { mutableStateOf(ParkingBoardStore.getAll(ctx)) }

                fun warnWipIfNeeded(id: Long) {
                    val already = items.count { it.status == ParkStatus.IN_PROGRESS && it.id != id }
                    if (already >= ParkingBoardStore.WIP_LIMIT) {
                        Toast.makeText(
                            ctx,
                            "Schon ${ParkingBoardStore.WIP_LIMIT} Dinge in Arbeit — bewusst mehr?",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                ParkingBoardScreen(
                    items = items,
                    onMove = { id, status ->
                        if (status == ParkStatus.IN_PROGRESS) warnWipIfNeeded(id)
                        ParkingBoardStore.updateStatus(ctx, id, status)
                        items = ParkingBoardStore.getAll(ctx)
                    },
                    onDelete = { id ->
                        if (PomodoroManager.activeTaskId(ctx) == id) PomodoroManager.cancel(ctx)
                        ParkingBoardStore.delete(ctx, id)
                        items = ParkingBoardStore.getAll(ctx)
                    },
                    onEdit = { id, text ->
                        ParkingBoardStore.updateText(ctx, id, text)
                        items = ParkingBoardStore.getAll(ctx)
                    },
                    onStartPomodoro = { id, text, dur ->
                        warnWipIfNeeded(id)
                        ParkingBoardStore.updateStatus(ctx, id, ParkStatus.IN_PROGRESS)
                        PomodoroManager.start(ctx, id, text, dur)
                        items = ParkingBoardStore.getAll(ctx)
                    },
                    onStopPomodoro = {
                        PomodoroManager.cancel(ctx)
                        items = ParkingBoardStore.getAll(ctx)
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
fun ParkingBoardScreen(
    items: List<ParkItem>,
    onMove: (Long, ParkStatus) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Long, String) -> Unit,
    onStartPomodoro: (Long, String, Int) -> Unit,
    onStopPomodoro: () -> Unit,
    onBack: () -> Unit
) {
    val statusOrder = mapOf(ParkStatus.IN_PROGRESS to 0, ParkStatus.BACKLOG to 1, ParkStatus.DONE to 2)
    val sorted = remember(items) {
        items.sortedWith(compareBy({ statusOrder[it.status] }, { it.createdAt }))
    }
    val wip = items.count { it.status == ParkStatus.IN_PROGRESS }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var pickerItem by remember { mutableStateOf<ParkItem?>(null) }

    // Sekündlicher Tick nur fürs Live-Countdown des laufenden Pomodoro.
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val pomoActive = PomodoroManager.isActive(context)
    val pomoTaskId = if (pomoActive) PomodoroManager.activeTaskId(context) else -1L
    val pomoRemainingMs = if (pomoActive) (PomodoroManager.endTime(context) - now).coerceAtLeast(0) else 0L

    pickerItem?.let { item ->
        PomodoroPickerDialog(
            taskText = item.text,
            onPick = { dur -> onStartPomodoro(item.id, item.text, dur); pickerItem = null },
            onNoTimer = { onMove(item.id, ParkStatus.IN_PROGRESS); pickerItem = null },
            onDismiss = { pickerItem = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "←",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 14.dp, top = 2.dp, bottom = 2.dp)
            )
            Column {
                Text("🅿️ Parkplatz", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "$wip/${ParkingBoardStore.WIP_LIMIT} in Arbeit",
                    fontSize = 12.sp,
                    color = COLOR_MUTED,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Noch nichts geparkt.\nTipp das 🅿️-Badge am Button, um einen Gedanken abzulegen.",
                    color = COLOR_MUTED,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sorted, key = { it.id }) { item ->
                    if (editingId == item.id) {
                        ParkItemEditRow(
                            initial = item.text,
                            onSave = { newText -> onEdit(item.id, newText); editingId = null },
                            onCancel = { editingId = null }
                        )
                    } else {
                        var menuOpen by remember { mutableStateOf(false) }
                        val isRunningPomo = pomoActive && item.id == pomoTaskId
                        val remainingLabel = if (isRunningPomo) formatRemaining(pomoRemainingMs) else null
                        Box {
                            ParkItemRow(item = item, remainingLabel = remainingLabel, onTap = { menuOpen = true })
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Bearbeiten") },
                                    onClick = { editingId = item.id; menuOpen = false }
                                )
                                if (isRunningPomo) {
                                    DropdownMenuItem(
                                        text = { Text("Pomodoro stoppen") },
                                        onClick = { onStopPomodoro(); menuOpen = false }
                                    )
                                }
                                if (item.status != ParkStatus.BACKLOG) {
                                    DropdownMenuItem(
                                        text = { Text("Zurück in Backlog") },
                                        onClick = { onMove(item.id, ParkStatus.BACKLOG); menuOpen = false }
                                    )
                                }
                                if (item.status != ParkStatus.IN_PROGRESS) {
                                    DropdownMenuItem(
                                        text = { Text("In Arbeit") },
                                        onClick = { pickerItem = item; menuOpen = false }
                                    )
                                }
                                if (item.status != ParkStatus.DONE) {
                                    DropdownMenuItem(
                                        text = { Text("Fertig") },
                                        onClick = { onMove(item.id, ParkStatus.DONE); menuOpen = false }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Löschen") },
                                    onClick = { onDelete(item.id); menuOpen = false }
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ParkItemRow(item: ParkItem, remainingLabel: String? = null, onTap: () -> Unit) {
    val statusColor = when (item.status) {
        ParkStatus.BACKLOG -> COLOR_BACKLOG
        ParkStatus.IN_PROGRESS -> COLOR_PROGRESS
        ParkStatus.DONE -> COLOR_DONE
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(COLOR_CARD)
            .clickable { onTap() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
            if (item.status == ParkStatus.IN_PROGRESS) {
                val infinite = rememberInfiniteTransition(label = "pulse")
                val haloAlpha by infinite.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.75f,
                    animationSpec = infiniteRepeatable(
                        tween(1200, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse
                    ),
                    label = "haloAlpha"
                )
                val haloScale by infinite.animateFloat(
                    initialValue = 0.9f,
                    targetValue = 1.7f,
                    animationSpec = infiniteRepeatable(
                        tween(1200, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse
                    ),
                    label = "haloScale"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(haloScale)
                        .alpha(haloAlpha)
                        .background(statusColor, CircleShape)
                )
            }
            Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
        }
        Text(
            item.text,
            color = if (item.status == ParkStatus.DONE) COLOR_MUTED else Color.White,
            fontSize = 14.sp,
            textDecoration = if (item.status == ParkStatus.DONE) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f)
        )
        if (remainingLabel != null) {
            Text(
                "🍅 $remainingLabel",
                color = COLOR_PROGRESS,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun PomodoroPickerDialog(
    taskText: String,
    onPick: (Int) -> Unit,
    onNoTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🍅 Wie lange dranbleiben?", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                taskText,
                color = COLOR_MUTED,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PomodoroManager.DURATIONS_MIN.forEach { minutes ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0x66FFD60A), RoundedCornerShape(14.dp))
                            .background(Color(0x14FFD60A))
                            .clickable { onPick(minutes) }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("$minutes", color = COLOR_PROGRESS, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Min", color = COLOR_MUTED, fontSize = 11.sp)
                    }
                }
            }
            Text(
                "Ohne Timer starten",
                color = COLOR_MUTED,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onNoTimer() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ParkItemEditRow(
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    // Beim Öffnen direkt fokussieren, damit die Tastatur ohne Extra-Tap kommt.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(COLOR_CARD)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = COLOR_PROGRESS,
                unfocusedBorderColor = Color(0xFF3A3A3C),
                cursorColor = COLOR_PROGRESS
            ),
            textStyle = TextStyle(fontSize = 14.sp),
            shape = RoundedCornerShape(8.dp)
        )
        Text(
            "✓",
            color = COLOR_DONE,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable { if (text.isNotBlank()) onSave(text) }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
        Text(
            "✕",
            color = COLOR_MUTED,
            fontSize = 18.sp,
            modifier = Modifier
                .clickable { onCancel() }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}
