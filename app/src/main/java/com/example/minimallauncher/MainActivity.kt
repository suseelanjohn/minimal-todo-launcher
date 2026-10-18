package com.example.minimallauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Telephony
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

// --- DATA MODELS ---
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String
)

data class EventItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val dateTimeText: String = "Tomorrow  ·  All day"
)

data class AppInfo(
    val label: String,
    val packageName: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force Top Status Bar & Bottom Navigation Bar to 100% Pure Black (#000000)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    LauncherScreen(activity = this)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(activity: Activity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE) }

    // Persistent States (Music Player is OFF by default)
    var todos by remember { mutableStateOf(loadTodos(prefs)) }
    var events by remember { mutableStateOf(loadEvents(prefs)) }
    var customAppPkg by remember { mutableStateOf(prefs.getString("custom_app_pkg", null)) }
    var showMusicPlayer by remember { mutableStateOf(false) }

    // Navigation & Mode States
    var showDrawer by remember { mutableStateOf(false) }
    var isSelectingCustomApp by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    // --- WORKED HOURS TRACKER (METHOD 2: TAP TO START/STOP LIVE TIMER + FOCUS AUTO-ADD) ---
    val todayKey = remember { LocalDate.now().toString() }
    var savedWorkSecondsToday by remember {
        mutableLongStateOf(checkAndLoadTodayWorkSeconds(prefs, todayKey))
    }
    var isWorkTimerRunning by remember {
        mutableStateOf(prefs.getLong("work_timer_start_ms", 0L) > 0L)
    }
    var workTimerStartMs by remember {
        mutableLongStateOf(prefs.getLong("work_timer_start_ms", 0L))
    }
    var displayedWorkSeconds by remember { mutableLongStateOf(savedWorkSecondsToday) }

    // 20-Minute Focus Mode States
    var focusedTask by remember { mutableStateOf<TodoItem?>(null) }
    var focusRemainingSeconds by remember { mutableIntStateOf(20 * 60) }

    // Quick Creation States
    var isAddingEvent by remember { mutableStateOf(false) }
    var newTitleText by remember { mutableStateOf("") }
    var newEventDateText by remember { mutableStateOf("") }

    // Edit Dialog States
    var editingTodo by remember { mutableStateOf<TodoItem?>(null) }
    var editTodoText by remember { mutableStateOf("") }

    var editingEvent by remember { mutableStateOf<EventItem?>(null) }
    var editEventTitle by remember { mutableStateOf("") }
    var editEventDate by remember { mutableStateOf("") }

    // --- TAMIL AUDIO NEWS READER (TTS) STATES ---
    var isReadingNews by remember { mutableStateOf(false) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tamilLocale = Locale("ta", "IN")
                val result = engine?.setLanguage(tamilLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.language = Locale.getDefault()
                }
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "NEWS_END") {
                            isReadingNews = false
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        isReadingNews = false
                    }
                })
            }
        }
        ttsEngine = engine
        onDispose {
            runCatching {
                engine?.stop()
                engine?.shutdown()
            }
        }
    }

    // Live Clock & Background Work Timer Tick
    var currentDateTime by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(isWorkTimerRunning, workTimerStartMs, savedWorkSecondsToday) {
        while (true) {
            currentDateTime = LocalDateTime.now()
            val currentDay = LocalDate.now().toString()
            if (prefs.getString("work_date", "") != currentDay) {
                // Midnight reset
                savedWorkSecondsToday = 0L
                prefs.edit()
                    .putString("work_date", currentDay)
                    .putLong("work_seconds_today", 0L)
                    .apply()
            }
            if (isWorkTimerRunning && workTimerStartMs > 0L) {
                val elapsed = ((System.currentTimeMillis() - workTimerStartMs) / 1000L).coerceAtLeast(0L)
                displayedWorkSeconds = savedWorkSecondsToday + elapsed
            } else {
                displayedWorkSeconds = savedWorkSecondsToday
            }
            delay(1000L)
        }
    }

    // 20-Minute Focus Countdown Timer (Also adds every worked second to Today's Worked Hours)
    LaunchedEffect(focusedTask) {
        val currentFocus = focusedTask
        if (currentFocus != null) {
            focusRemainingSeconds = 20 * 60
            runCatching { activity.startLockTask() }
            while (focusRemainingSeconds > 0 && focusedTask != null) {
                delay(1000L)
                focusRemainingSeconds -= 1
                // Automatically add 1 second to today's worked time
                savedWorkSecondsToday += 1L
                prefs.edit().putLong("work_seconds_today", savedWorkSecondsToday).apply()
            }
            if (focusRemainingSeconds <= 0) {
                runCatching { activity.stopLockTask() }
                focusedTask = null
            }
        }
    }

    // Load Installed Apps in Background
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(intent, 0).mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == context.packageName) null
                else AppInfo(
                    label = resolveInfo.loadLabel(pm).toString(),
                    packageName = pkg
                )
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            installedApps = apps
        }
    }

    BackHandler(enabled = true) {
        if (focusedTask != null) {
            // Locked during 20-min Focus Mode
        } else if (showDrawer) {
            showDrawer = false
            isSelectingCustomApp = false
        }
    }

    val addCurrentItem = {
        if (newTitleText.isNotBlank()) {
            if (isAddingEvent) {
                val dateLabel = if (newEventDateText.isBlank()) "Upcoming  ·  All day" else newEventDateText.trim()
                events = listOf(EventItem(title = newTitleText.trim(), dateTimeText = dateLabel)) + events
                saveEvents(prefs, events)
                newEventDateText = ""
            } else {
                todos = listOf(TodoItem(text = newTitleText.trim())) + todos
                saveTodos(prefs, todos)
            }
            newTitleText = ""
        }
    }

    // --- 1. 20-MINUTE FULL-SCREEN FOCUS MODE (NO MUSIC PLAYER, APPS LOCKED) ---
    if (focusedTask != null) {
        FocusLockScreen(
            task = focusedTask!!,
            remainingSeconds = focusRemainingSeconds,
            onMarkCompleted = {
                val target = focusedTask!!
                // Auto-remove completed task from screen
                todos = todos.filter { it.id != target.id }
                saveTodos(prefs, todos)
                runCatching { activity.stopLockTask() }
                focusedTask = null
            }
        )
        return
    }

    // --- 2. TEXT-ONLY APP DRAWER ---
    if (showDrawer) {
        AppDrawerScreen(
            apps = installedApps,
            isSelectionMode = isSelectingCustomApp,
            onAppClick = { app ->
                if (isSelectingCustomApp) {
                    customAppPkg = app.packageName
                    prefs.edit().putString("custom_app_pkg", app.packageName).apply()
                    isSelectingCustomApp = false
                    showDrawer = false
                } else {
                    launchPackage(context, app.packageName)
                    showDrawer = false
                }
            },
            onClose = {
                showDrawer = false
                isSelectingCustomApp = false
            }
        )
        return
    }

    // --- 3. PURE BLACK HOME SCREEN ---
    val dateFormatted = remember(currentDateTime) {
        val formatter = DateTimeFormatter.ofPattern("dd. MMM.", Locale.ENGLISH)
        "Today | ${currentDateTime.format(formatter)}"
    }
    val hoursRemaining = (24 - currentDateTime.hour).coerceAtLeast(0)
    val dayProgress = (currentDateTime.hour * 60 + currentDateTime.minute) / 1440f

    val workedHours = displayedWorkSeconds / 3600
    val workedMinutes = (displayedWorkSeconds % 3600) / 60
    val workedFormatted = String.format(Locale.ENGLISH, "%dh %02dm", workedHours, workedMinutes)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 64.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Top Header: "Today | 18. Oct."
            Text(
                text = dateFormatted,
                fontSize = 30.sp,
                fontWeight = FontWeight.Thin,
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Slim Progress Bar (White Only)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(dayProgress.coerceIn(0.05f, 1f))
                        .height(1.5.dp)
                        .background(Color.White)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Method 2: Tap "Worked: Xh YYm" to Start / Pause Live Background Work Timer
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isWorkTimerRunning) "▶ Working: $workedFormatted" else "Worked: $workedFormatted",
                    fontSize = 11.5.sp,
                    fontWeight = if (isWorkTimerRunning) FontWeight.Bold else FontWeight.Medium,
                    color = if (isWorkTimerRunning) Color.White else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clickable {
                            if (isWorkTimerRunning) {
                                // Stop timer and save accumulated seconds
                                val elapsed = ((System.currentTimeMillis() - workTimerStartMs) / 1000L).coerceAtLeast(0L)
                                savedWorkSecondsToday += elapsed
                                isWorkTimerRunning = false
                                workTimerStartMs = 0L
                                prefs.edit()
                                    .putLong("work_seconds_today", savedWorkSecondsToday)
                                    .putLong("work_timer_start_ms", 0L)
                                    .apply()
                            } else {
                                // Start live background timer
                                val nowMs = System.currentTimeMillis()
                                workTimerStartMs = nowMs
                                isWorkTimerRunning = true
                                prefs.edit().putLong("work_timer_start_ms", nowMs).apply()
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Text(
                    text = "  ·  ${hoursRemaining}h available",
                    fontSize = 11.5.sp,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Mode Switcher ([ + TASK ] / [ + EVENT ]) + (📻) Tamil News Radio + (♪) Music Toggle + (→) App Drawer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeChip(
                        label = "TASK",
                        selected = !isAddingEvent,
                        onClick = { isAddingEvent = false }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ModeChip(
                        label = "EVENT",
                        selected = isAddingEvent,
                        onClick = { isAddingEvent = true }
                    )
                }

                // Right-Side Circular Action Buttons: (📻) Tamil Radio News + (♪) Music + (→) App Drawer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Circular Radio Button (📻) for Tamil Audio News Reader (No Text Shown)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .border(
                                width = 1.2.dp,
                                color = if (isReadingNews) Color.White else Color.White.copy(alpha = 0.45f),
                                shape = CircleShape
                            )
                            .background(
                                color = if (isReadingNews) Color.White.copy(alpha = 0.22f) else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable {
                                if (isReadingNews) {
                                    isReadingNews = false
                                    ttsEngine?.stop()
                                } else {
                                    isReadingNews = true
                                    scope.launch {
                                        val headlines = fetchTamilNewsHeadlines()
                                        if (isReadingNews && ttsEngine != null) {
                                            val tamilLocale = Locale("ta", "IN")
                                            ttsEngine?.setLanguage(tamilLocale)
                                            ttsEngine?.speak(
                                                "இன்றைய முக்கிய செய்திகள்.",
                                                TextToSpeech.QUEUE_FLUSH,
                                                null,
                                                "NEWS_INTRO"
                                            )
                                            headlines.forEachIndexed { idx, headline ->
                                                val uId = if (idx == headlines.lastIndex) "NEWS_END" else "NEWS_$idx"
                                                ttsEngine?.speak(
                                                    headline,
                                                    TextToSpeech.QUEUE_ADD,
                                                    null,
                                                    uId
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        RadioVectorIcon(
                            tint = if (isReadingNews) Color.White else Color.White.copy(alpha = 0.65f)
                        )
                    }

                    // 2. Circular Music Show/Hide Toggle Button (♪) (Off by default)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .border(
                                width = 1.2.dp,
                                color = if (showMusicPlayer) Color.White else Color.White.copy(alpha = 0.45f),
                                shape = CircleShape
                            )
                            .background(
                                color = if (showMusicPlayer) Color.White.copy(alpha = 0.22f) else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable {
                                showMusicPlayer = !showMusicPlayer
                            }
                    ) {
                        Text(
                            text = "♪",
                            color = if (showMusicPlayer) Color.White else Color.White.copy(alpha = 0.65f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 3. Circular App Drawer Arrow Button (→)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .border(
                                width = 1.2.dp,
                                color = Color.White,
                                shape = CircleShape
                            )
                            .clickable {
                                isSelectingCustomApp = false
                                showDrawer = true
                            }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open App Drawer",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Creation Box
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newTitleText,
                        onValueChange = { newTitleText = it },
                        placeholder = {
                            Text(
                                text = if (isAddingEvent) "Add an upcoming event..." else "Add a new task...",
                                color = Color.White.copy(alpha = 0.45f)
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (isAddingEvent) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { addCurrentItem() }
                        ),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                            cursorColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { addCurrentItem() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Item",
                            tint = Color.White
                        )
                    }
                }

                if (isAddingEvent) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newEventDateText,
                        onValueChange = { newEventDateText = it },
                        placeholder = {
                            Text(
                                text = "Date / Time (e.g., Tomorrow · All day)",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 13.sp
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { addCurrentItem() }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                            cursorColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Feed: Today's Tasks (Tap = Auto-Remove | Hold = 20m Focus) + Upcoming Events
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Text(
                        text = "TODAY'S TASKS",
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                if (todos.isEmpty()) {
                    item {
                        Text(
                            text = "No tasks for today",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }

                items(todos, key = { "todo_${it.id}" }) { item ->
                    AgendaRowItem(
                        title = item.text,
                        subtitleHighlight = "Today",
                        subtitleRest = "  ·  All day",
                        onTapRemove = {
                            // Auto-remove completed task from screen immediately
                            todos = todos.filter { it.id != item.id }
                            saveTodos(prefs, todos)
                        },
                        onLongPress = {
                            // Long press opens 20-minute full-screen Focus Mode
                            focusedTask = item
                        },
                        onEdit = {
                            editingTodo = item
                            editTodoText = item.text
                        },
                        onDelete = {
                            todos = todos.filter { it.id != item.id }
                            saveTodos(prefs, todos)
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "UPCOMING EVENTS",
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                if (events.isEmpty()) {
                    item {
                        Text(
                            text = "No upcoming events",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }

                items(events, key = { "event_${it.id}" }) { event ->
                    val parts = event.dateTimeText.split("·", limit = 2)
                    val highlight = parts.getOrNull(0)?.trim() ?: event.dateTimeText
                    val rest = if (parts.size > 1) "  ·  ${parts[1].trim()}" else ""

                    AgendaRowItem(
                        title = event.title,
                        subtitleHighlight = highlight,
                        subtitleRest = rest,
                        onTapRemove = {
                            // Auto-remove completed event from screen
                            events = events.filter { it.id != event.id }
                            saveEvents(prefs, events)
                        },
                        onLongPress = {},
                        onEdit = {
                            editingEvent = event
                            editEventTitle = event.title
                            editEventDate = event.dateTimeText
                        },
                        onDelete = {
                            events = events.filter { it.id != event.id }
                            saveEvents(prefs, events)
                        }
                    )
                }
            }

            // --- MINIMALIST BOTTOM "MUSICAMP" PLAYER (HIDDEN BY DEFAULT, SHOWN ONLY WHEN ♪ IS PRESSED) ---
            if (showMusicPlayer) {
                Spacer(modifier = Modifier.height(8.dp))
                MusicampBottomWidget(context = context)
            }
        }

        // Bottom 4-Icon Dock
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockIconButton(icon = Icons.Default.Call, description = "Dialer") {
                openDialer(context)
            }

            DockIconButton(icon = Icons.Default.Email, description = "Messages") {
                openMessages(context)
            }

            DockIconButton(icon = Icons.Default.Search, description = "Browser") {
                openBrowser(context)
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .border(
                        width = 1.5.dp,
                        color = Color.White,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .combinedClickable(
                        onClick = {
                            if (customAppPkg != null) {
                                launchPackage(context, customAppPkg!!)
                            } else {
                                isSelectingCustomApp = true
                                showDrawer = true
                            }
                        },
                        onLongClick = {
                            isSelectingCustomApp = true
                            showDrawer = true
                        }
                    )
            ) {
                Icon(
                    imageVector = if (customAppPkg == null) Icons.Default.Add else Icons.Default.Star,
                    contentDescription = "Custom Quick App",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    // Edit Task Dialog
    if (editingTodo != null) {
        AlertDialog(
            onDismissRequest = { editingTodo = null },
            containerColor = Color.Black,
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("Edit Task") },
            text = {
                OutlinedTextField(
                    value = editTodoText,
                    onValueChange = { editTodoText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = editingTodo
                    if (current != null && editTodoText.isNotBlank()) {
                        todos = todos.map {
                            if (it.id == current.id) it.copy(text = editTodoText.trim()) else it
                        }
                        saveTodos(prefs, todos)
                    }
                    editingTodo = null
                }) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTodo = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }

    // Edit Event Dialog
    if (editingEvent != null) {
        AlertDialog(
            onDismissRequest = { editingEvent = null },
            containerColor = Color.Black,
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("Edit Event") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editEventTitle,
                        onValueChange = { editEventTitle = it },
                        label = { Text("Event Title", color = Color.White.copy(alpha = 0.6f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editEventDate,
                        onValueChange = { editEventDate = it },
                        label = { Text("Date / Time", color = Color.White.copy(alpha = 0.6f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = editingEvent
                    if (current != null && editEventTitle.isNotBlank()) {
                        events = events.map {
                            if (it.id == current.id) {
                                it.copy(
                                    title = editEventTitle.trim(),
                                    dateTimeText = editEventDate.trim().ifBlank { "Upcoming · All day" }
                                )
                            } else it
                        }
                        saveEvents(prefs, events)
                    }
                    editingEvent = null
                }) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingEvent = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }
}

// --- CUSTOM MONOCHROME VECTOR RADIO ICON (📻) ---
@Composable
fun RadioVectorIcon(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        // Antenna line
        drawLine(
            color = tint,
            start = Offset(w * 0.25f, h * 0.35f),
            end = Offset(w * 0.78f, h * 0.12f),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )
        // Radio body rectangle
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.1f, h * 0.35f),
            size = Size(w * 0.8f, h * 0.52f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx())
        )
        // Speaker circle inside radio
        drawCircle(
            color = tint,
            radius = w * 0.13f,
            center = Offset(w * 0.33f, h * 0.61f),
            style = Stroke(width = 1.3.dp.toPx())
        )
        // Tuning lines on right side of radio
        drawLine(
            color = tint,
            start = Offset(w * 0.56f, h * 0.53f),
            end = Offset(w * 0.76f, h * 0.53f),
            strokeWidth = 1.3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.56f, h * 0.69f),
            end = Offset(w * 0.76f, h * 0.69f),
            strokeWidth = 1.3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

// --- 20-MINUTE FULL-SCREEN FOCUS LOCK SCREEN ---
@Composable
fun FocusLockScreen(
    task: TodoItem,
    remainingSeconds: Int,
    onMarkCompleted: () -> Unit
) {
    val mins = (remainingSeconds / 60).toString().padStart(2, '0')
    val secs = (remainingSeconds % 60).toString().padStart(2, '0')
    val progress = remainingSeconds / (20f * 60f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "🔒 FOCUS LOCK ACTIVE",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "OTHER APPS & DRAWER BLOCKED FOR 20 MINS",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp,
                letterSpacing = 1.2.sp
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(210.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.15f),
                    style = Stroke(width = 4.dp.toPx())
                )
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$mins:$secs",
                    color = Color.White,
                    fontSize = 46.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "REMAINING",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    letterSpacing = 2.5.sp
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "FOCUSING ON TASK",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.2.dp, Color.White, RoundedCornerShape(4.dp))
                    .padding(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(42.dp)
                        .background(Color.White)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = task.text,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Today · Deep Focus Session (20m)",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .clickable(onClick = onMarkCompleted)
            ) {
                Text(
                    text = "✓ MARK TASK COMPLETED",
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }
        }

        Text(
            text = "DOCK & APP DRAWER LOCKED UNTIL 00:00",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.5.sp,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

// --- MINIMALIST BOTTOM "MUSICAMP" WIDGET ---
@Composable
fun MusicampBottomWidget(context: Context) {
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var isPlaying by remember { mutableStateOf(audioManager.isMusicActive) }
    var trackTitle by remember { mutableStateOf("TAP HERE TO PICK AUDIO OR PLAY") }
    var beatWaveBars by remember { mutableStateOf(List(10) { 3 }) }
    var shuffleOn by remember { mutableStateOf(false) }

    var localPlaylist by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentTrackIndex by remember { mutableIntStateOf(0) }
    val localPlayer = remember { MediaPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                if (localPlayer.isPlaying) localPlayer.stop()
                localPlayer.release()
            }
        }
    }

    fun playLocalUri(uri: Uri) {
        runCatching {
            localPlayer.reset()
            localPlayer.setDataSource(context, uri)
            localPlayer.prepare()
            localPlayer.start()
            isPlaying = true
            trackTitle = getFileNameFromUri(context, uri)
            localPlayer.setOnCompletionListener {
                if (localPlaylist.isNotEmpty()) {
                    currentTrackIndex = if (shuffleOn) {
                        Random.nextInt(localPlaylist.size)
                    } else {
                        (currentTrackIndex + 1) % localPlaylist.size
                    }
                    playLocalUri(localPlaylist[currentTrackIndex])
                } else {
                    isPlaying = false
                }
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            localPlaylist = uris
            currentTrackIndex = 0
            playLocalUri(uris[0])
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val track = intent?.getStringExtra("track")
                val artist = intent?.getStringExtra("artist")
                if (!track.isNullOrBlank()) {
                    trackTitle = if (!artist.isNullOrBlank()) "$track - $artist" else track
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.android.music.metachanged")
            addAction("com.android.music.playstatechanged")
            addAction("com.spotify.music.metadatachanged")
            addAction("com.spotify.music.playbackstatechanged")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val active = audioManager.isMusicActive || localPlayer.isPlaying
            isPlaying = active
            if (active) {
                beatWaveBars = List(10) { Random.nextInt(4, 21) }
                if (trackTitle.startsWith("TAP HERE")) {
                    trackTitle = "1. SYSTEM SOUNDTRACK PLAYING"
                }
            } else {
                beatWaveBars = List(10) { 3 }
            }
            delay(350L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.2.dp, Color.White, RoundedCornerShape(2.dp))
            .background(Color.Black)
            .padding(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .border(0.75.dp, Color.White)
            )
            Text(
                text = " MUSICAMP ",
                color = Color.White,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .border(0.75.dp, Color.White)
            )
        }

        Spacer(modifier = Modifier.height(7.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .width(74.dp)
                    .height(26.dp)
                    .border(0.8.dp, Color.White.copy(alpha = 0.65f))
                    .padding(horizontal = 5.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                beatWaveBars.forEach { h ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(h.dp)
                            .background(Color.White)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .border(0.8.dp, Color.White.copy(alpha = 0.65f))
                    .clickable {
                        audioPicker.launch(arrayOf("audio/*"))
                    }
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = trackTitle.uppercase(Locale.ENGLISH),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(7.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MusicampButton(
                label = "|<",
                modifier = Modifier.weight(1f),
                onClick = {
                    if (localPlaylist.isNotEmpty()) {
                        currentTrackIndex = (currentTrackIndex - 1 + localPlaylist.size) % localPlaylist.size
                        playLocalUri(localPlaylist[currentTrackIndex])
                    } else {
                        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    }
                }
            )

            MusicampButton(
                label = if (isPlaying) "||" else "▶",
                modifier = Modifier.weight(1.25f),
                onClick = {
                    if (localPlaylist.isNotEmpty()) {
                        if (localPlayer.isPlaying) {
                            localPlayer.pause()
                            isPlaying = false
                        } else {
                            localPlayer.start()
                            isPlaying = true
                        }
                    } else {
                        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        isPlaying = !isPlaying
                    }
                }
            )

            MusicampButton(
                label = ">|",
                modifier = Modifier.weight(1f),
                onClick = {
                    if (localPlaylist.isNotEmpty()) {
                        currentTrackIndex = if (shuffleOn) {
                            Random.nextInt(localPlaylist.size)
                        } else {
                            (currentTrackIndex + 1) % localPlaylist.size
                        }
                        playLocalUri(localPlaylist[currentTrackIndex])
                    } else {
                        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                    }
                }
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1.1f)
                    .height(25.dp)
                    .border(1.dp, Color.White)
                    .background(if (shuffleOn) Color.White else Color.Black)
                    .clickable { shuffleOn = !shuffleOn }
            ) {
                Text(
                    text = "SHUF",
                    color = if (shuffleOn) Color.Black else Color.White,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MusicampButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(25.dp)
            .border(1.dp, Color.White)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

// --- MONOCHROME CHIPS & AGENDA ITEMS (AUTO-REMOVES ON TAP) ---
@Composable
fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                shape = RoundedCornerShape(4.dp)
            )
            .background(
                color = if (selected) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "+ $label",
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.5f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgendaRowItem(
    title: String,
    subtitleHighlight: String,
    subtitleRest: String,
    onTapRemove: () -> Unit,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(Color.White)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onTapRemove,
                        onLongClick = onLongPress
                    )
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = subtitleHighlight,
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (subtitleRest.isNotEmpty()) {
                        Text(
                            text = subtitleRest,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(9.dp))
        HorizontalDivider(
            color = Color.White.copy(alpha = 0.15f),
            thickness = 0.5.dp
        )
    }
}

@Composable
fun DockIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
fun AppDrawerScreen(
    apps: List<AppInfo>,
    isSelectionMode: Boolean,
    onAppClick: (AppInfo) -> Unit,
    onClose: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = if (isSelectionMode) "Select quick app..." else "Search apps...",
                        color = Color.White.copy(alpha = 0.45f)
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                    cursorColor = Color.White
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredApps, key = { it.packageName }) { app ->
                Text(
                    text = app.label,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAppClick(app) }
                        .padding(vertical = 12.dp)
                )
            }
        }
    }
}

// --- LIVE TAMIL NEWS RSS FETCHER (GOOGLE NEWS TAMIL) ---
suspend fun fetchTamilNewsHeadlines(): List<String> = withContext(Dispatchers.IO) {
    val headlines = mutableListOf<String>()
    runCatching {
        val url = URL("https://news.google.com/rss?hl=ta&gl=IN&ceid=IN:ta")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "GET"
        }
        conn.inputStream.use { input ->
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(input, "UTF-8")
            var eventType = parser.eventType
            var insideItem = false
            while (eventType != XmlPullParser.END_DOCUMENT && headlines.size < 6) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (parser.name.equals("item", ignoreCase = true)) {
                        insideItem = true
                    } else if (insideItem && parser.name.equals("title", ignoreCase = true)) {
                        val rawTitle = parser.nextText().trim()
                        val cleanTitle = rawTitle.substringBeforeLast(" - ").trim()
                        if (cleanTitle.isNotEmpty()) {
                            headlines.add(cleanTitle)
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG && parser.name.equals("item", ignoreCase = true)) {
                    insideItem = false
                }
                eventType = parser.next()
            }
        }
    }
    if (headlines.isEmpty()) {
        listOf("இணைய இணைப்பு இல்லை. பிறகு முயற்சிக்கவும்.")
    } else {
        headlines
    }
}

// --- WORKED TIME & STORAGE HELPERS ---
fun checkAndLoadTodayWorkSeconds(prefs: SharedPreferences, todayKey: String): Long {
    val savedDate = prefs.getString("work_date", "")
    return if (savedDate != todayKey) {
        prefs.edit()
            .putString("work_date", todayKey)
            .putLong("work_seconds_today", 0L)
            .apply()
        0L
    } else {
        prefs.getLong("work_seconds_today", 0L)
    }
}

fun getFileNameFromUri(context: Context, uri: Uri): String {
    var name = "LOCAL AUDIO TRACK"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index).substringBeforeLast(".")
            }
        }
    }
    return name
}

fun sendMediaKey(context: Context, keyCode: Int) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val eventTime = SystemClock.uptimeMillis()
    val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
    val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
    audioManager.dispatchMediaKeyEvent(downEvent)
    audioManager.dispatchMediaKeyEvent(upEvent)
}

fun openDialer(context: Context) {
    val intent = Intent(Intent.ACTION_DIAL).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
    runCatching { context.startActivity(intent) }
}

fun openMessages(context: Context) {
    val defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(context)
    if (defaultSmsPkg != null) {
        launchPackage(context, defaultSmsPkg)
    } else {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MESSAGING)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
    }
}

fun openBrowser(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
}

fun launchPackage(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    if (intent != null) {
        runCatching { context.startActivity(intent) }
    }
}

fun saveTodos(prefs: SharedPreferences, list: List<TodoItem>) {
    val array = JSONArray()
    list.forEach { item ->
        val obj = JSONObject().apply {
            put("id", item.id)
            put("text", item.text)
        }
        array.put(obj)
    }
    prefs.edit().putString("saved_todos", array.toString()).apply()
}

fun loadTodos(prefs: SharedPreferences): List<TodoItem> {
    val raw = prefs.getString("saved_todos", null) ?: return emptyList()
    val result = mutableListOf<TodoItem>()
    runCatching {
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            // Automatically skip any old completed tasks from previous versions
            if (!obj.optBoolean("isDone", false)) {
                result.add(
                    TodoItem(
                        id = obj.getString("id"),
                        text = obj.getString("text")
                    )
                )
            }
        }
    }
    return result
}

fun saveEvents(prefs: SharedPreferences, list: List<EventItem>) {
    val array = JSONArray()
    list.forEach { item ->
        val obj = JSONObject().apply {
            put("id", item.id)
            put("title", item.title)
            put("dateTimeText", item.dateTimeText)
        }
        array.put(obj)
    }
    prefs.edit().putString("saved_events", array.toString()).apply()
}

fun loadEvents(prefs: SharedPreferences): List<EventItem> {
    val raw = prefs.getString("saved_events", null) ?: return emptyList()
    val result = mutableListOf<EventItem>()
    runCatching {
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (!obj.optBoolean("isDone", false)) {
                result.add(
                    EventItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        dateTimeText = obj.optString("dateTimeText", "Upcoming  ·  All day")
                    )
                )
            }
        }
    }
    return result
}
