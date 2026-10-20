package com.example.minimallauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Telephony
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.input.pointer.pointerInput
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

// --- DATA MODELS WITH ANALYTICS METADATA ---
val TASK_CATEGORIES = listOf("Core/Client", "Admin", "Research", "Growth/Meetings")
val PLANNED_MINUTES_OPTIONS = listOf(20, 30, 60, 120)
val BLOCKER_OPTIONS = listOf("None", "Waiting Approval", "Unclear Next Step", "Deprioritized")

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val category: String = "Core/Client",
    val plannedMinutes: Int = 30,
    val blocker: String = "None",
    val createdAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long = 0L
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

    // Persistent Lists: Active Tasks, Completed Archive, and Upcoming Events
    var todos by remember { mutableStateOf(loadTodos(prefs, "saved_todos")) }
    var completedTodos by remember { mutableStateOf(loadTodos(prefs, "completed_todos")) }
    var events by remember { mutableStateOf(loadEvents(prefs)) }
    var customAppPkg by remember { mutableStateOf(prefs.getString("custom_app_pkg", null)) }
    var showMusicPlayer by remember { mutableStateOf(false) }

    // Screen Navigation States
    var showDrawer by remember { mutableStateOf(false) }
    var isSelectingCustomApp by remember { mutableStateOf(false) }
    var showCompletedModal by remember { mutableStateOf(false) }
    var showAnalyticsModal by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    // --- WORKED HOURS TRACKER & DAILY HISTORY ---
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

    // Quick Creation States (With Category & Planned Duration Selectors)
    var isAddingEvent by remember { mutableStateOf(false) }
    var newTitleText by remember { mutableStateOf("") }
    var newEventDateText by remember { mutableStateOf("") }
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var selectedPlannedIndex by remember { mutableIntStateOf(1) } // Default 30m

    // Edit Dialog States
    var editingTodo by remember { mutableStateOf<TodoItem?>(null) }
    var editTodoText by remember { mutableStateOf("") }
    var editTodoCategory by remember { mutableStateOf("Core/Client") }
    var editTodoPlannedMins by remember { mutableIntStateOf(30) }
    var editTodoBlocker by remember { mutableStateOf("None") }

    var editingEvent by remember { mutableStateOf<EventItem?>(null) }
    var editEventTitle by remember { mutableStateOf("") }
    var editEventDate by remember { mutableStateOf("") }

    // --- AUDIO NEWS READER & NEW NEWS INDICATOR BADGE (•) ---
    var isReadingNews by remember { mutableStateOf(false) }
    var newsLanguageIsTamil by remember { mutableStateOf(prefs.getBoolean("news_lang_tamil", false)) }
    var hasUnheardNews by remember { mutableStateOf(false) }
    var latestFetchedHeadline by remember { mutableStateOf("") }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context, newsLanguageIsTamil) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = if (newsLanguageIsTamil) Locale("ta", "IN") else Locale.US
                val result = engine?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.language = Locale.getDefault()
                }
                engine?.setSpeechRate(0.95f)
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

    // Background Check for Fresh News Every 10 Minutes (Lights up the • badge on 📻)
    LaunchedEffect(newsLanguageIsTamil) {
        while (true) {
            val summaries = fetch3SentenceNewsSummaries(newsLanguageIsTamil)
            val topStory = summaries.firstOrNull() ?: ""
            val lastHeard = prefs.getString("last_heard_headline", "") ?: ""
            if (topStory.isNotBlank() && topStory != lastHeard && !topStory.startsWith("No fresh")) {
                latestFetchedHeadline = topStory
                hasUnheardNews = true
            }
            delay(10 * 60 * 1000L)
        }
    }

    // Live Clock & Background Work Timer Tick
    var currentDateTime by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(isWorkTimerRunning, workTimerStartMs, savedWorkSecondsToday) {
        while (true) {
            currentDateTime = LocalDateTime.now()
            val currentDay = LocalDate.now().toString()
            if (prefs.getString("work_date", "") != currentDay) {
                savedWorkSecondsToday = 0L
                prefs.edit()
                    .putString("work_date", currentDay)
                    .putLong("work_seconds_today", 0L)
                    .apply()
            }
            if (isWorkTimerRunning && workTimerStartMs > 0L) {
                val elapsed = ((System.currentTimeMillis() - workTimerStartMs) / 1000L).coerceAtLeast(0L)
                displayedWorkSeconds = savedWorkSecondsToday + elapsed
                saveDailyWorkHistory(prefs, currentDay, displayedWorkSeconds)
            } else {
                displayedWorkSeconds = savedWorkSecondsToday
            }
            delay(1000L)
        }
    }

    // 20-Minute Focus Countdown Timer
    LaunchedEffect(focusedTask) {
        val currentFocus = focusedTask
        if (currentFocus != null) {
            focusRemainingSeconds = 20 * 60
            runCatching { activity.startLockTask() }
            while (focusRemainingSeconds > 0 && focusedTask != null) {
                delay(1000L)
                focusRemainingSeconds -= 1
                savedWorkSecondsToday += 1L
                val dayStr = LocalDate.now().toString()
                prefs.edit().putLong("work_seconds_today", savedWorkSecondsToday).apply()
                saveDailyWorkHistory(prefs, dayStr, savedWorkSecondsToday)
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
        when {
            focusedTask != null -> { /* Locked during Focus Mode */ }
            showAnalyticsModal -> showAnalyticsModal = false
            showCompletedModal -> showCompletedModal = false
            showDrawer -> {
                showDrawer = false
                isSelectingCustomApp = false
            }
        }
    }

    // Helper: Complete a task & archive it into Completed Tasks
    val completeAndArchiveTask: (TodoItem) -> Unit = { item ->
        val archived = item.copy(completedAtMs = System.currentTimeMillis())
        todos = todos.filter { it.id != item.id }
        completedTodos = listOf(archived) + completedTodos
        saveTodos(prefs, "saved_todos", todos)
        saveTodos(prefs, "completed_todos", completedTodos)
        incrementDailyStat(prefs, "completed_count_${LocalDate.now()}")
    }

    val addCurrentItem = {
        if (newTitleText.isNotBlank()) {
            if (isAddingEvent) {
                val dateLabel = if (newEventDateText.isBlank()) "Upcoming  ·  All day" else newEventDateText.trim()
                events = listOf(EventItem(title = newTitleText.trim(), dateTimeText = dateLabel)) + events
                saveEvents(prefs, events)
                newEventDateText = ""
            } else {
                val newItem = TodoItem(
                    text = newTitleText.trim(),
                    category = TASK_CATEGORIES[selectedCategoryIndex],
                    plannedMinutes = PLANNED_MINUTES_OPTIONS[selectedPlannedIndex]
                )
                todos = listOf(newItem) + todos
                saveTodos(prefs, "saved_todos", todos)
                incrementDailyStat(prefs, "added_count_${LocalDate.now()}")
            }
            newTitleText = ""
        }
    }

    // --- 1. 20-MINUTE FULL-SCREEN FOCUS MODE ---
    if (focusedTask != null) {
        FocusLockScreen(
            task = focusedTask!!,
            remainingSeconds = focusRemainingSeconds,
            onMarkCompleted = {
                val target = focusedTask!!
                completeAndArchiveTask(target)
                runCatching { activity.stopLockTask() }
                focusedTask = null
            }
        )
        return
    }

    // --- 2. 4-PILLAR PRODUCTIVITY & SUSTAINABILITY ANALYTICS SCREEN ---
    if (showAnalyticsModal) {
        AnalyticsDashboardScreen(
            activeTodos = todos,
            completedTodos = completedTodos,
            workedSecondsToday = displayedWorkSeconds,
            prefs = prefs,
            onClose = { showAnalyticsModal = false }
        )
        return
    }

    // --- 3. COMPLETED TASKS ARCHIVE SCREEN ---
    if (showCompletedModal) {
        CompletedTasksScreen(
            completedTodos = completedTodos,
            onRestoreTask = { item ->
                completedTodos = completedTodos.filter { it.id != item.id }
                todos = listOf(item.copy(completedAtMs = 0L)) + todos
                saveTodos(prefs, "completed_todos", completedTodos)
                saveTodos(prefs, "saved_todos", todos)
            },
            onClearAll = {
                completedTodos = emptyList()
                saveTodos(prefs, "completed_todos", completedTodos)
            },
            onClose = { showCompletedModal = false }
        )
        return
    }

    // --- 4. TEXT-ONLY APP DRAWER ---
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

    // --- 5. PURE BLACK HOME SCREEN ---
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
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount > 50f) {
                        openPhonePeQrScanner(context)
                    }
                }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 64.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = dateFormatted,
                fontSize = 30.sp,
                fontWeight = FontWeight.Thin,
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(10.dp))

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
                                val elapsed = ((System.currentTimeMillis() - workTimerStartMs) / 1000L).coerceAtLeast(0L)
                                savedWorkSecondsToday += elapsed
                                isWorkTimerRunning = false
                                workTimerStartMs = 0L
                                val dayStr = LocalDate.now().toString()
                                prefs.edit()
                                    .putLong("work_seconds_today", savedWorkSecondsToday)
                                    .putLong("work_timer_start_ms", 0L)
                                    .apply()
                                saveDailyWorkHistory(prefs, dayStr, savedWorkSecondsToday)
                            } else {
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

            // Top Action Row: [+ TASK] [+ EVENT] [DONE]  &  (📊) (📻•) (♪) (→)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModeChip(
                        label = "+ TASK",
                        selected = !isAddingEvent,
                        onClick = { isAddingEvent = false }
                    )
                    ModeChip(
                        label = "+ EVENT",
                        selected = isAddingEvent,
                        onClick = { isAddingEvent = true }
                    )
                    ModeChip(
                        label = "DONE (${completedTodos.size})",
                        selected = false,
                        onClick = { showCompletedModal = true }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 1. Analytics Button (📊)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .border(1.2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                            .clickable { showAnalyticsModal = true }
                    ) {
                        AnalyticsVectorIcon(tint = Color.White)
                    }

                    // 2. Radio Audio News Button (📻) with Unheard News Dot Indicator (•)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .border(
                                width = 1.2.dp,
                                color = if (isReadingNews) Color.White else Color.White.copy(alpha = 0.45f),
                                shape = CircleShape
                            )
                            .background(
                                color = if (isReadingNews) Color.White.copy(alpha = 0.22f) else Color.Transparent,
                                shape = CircleShape
                            )
                            .combinedClickable(
                                onClick = {
                                    if (isReadingNews) {
                                        isReadingNews = false
                                        ttsEngine?.stop()
                                    } else {
                                        isReadingNews = true
                                        hasUnheardNews = false
                                        scope.launch {
                                            val summaries = fetch3SentenceNewsSummaries(newsLanguageIsTamil)
                                            val first = summaries.firstOrNull() ?: ""
                                            if (first.isNotBlank()) {
                                                prefs.edit().putString("last_heard_headline", first).apply()
                                            }
                                            if (isReadingNews && ttsEngine != null) {
                                                val locale = if (newsLanguageIsTamil) Locale("ta", "IN") else Locale.US
                                                ttsEngine?.setLanguage(locale)
                                                val intro = if (newsLanguageIsTamil) "இன்றைய முக்கிய செய்திகள்." else "Latest 3-sentence news updates."
                                                ttsEngine?.speak(intro, TextToSpeech.QUEUE_FLUSH, null, "NEWS_INTRO")
                                                summaries.forEachIndexed { idx, summary ->
                                                    val uId = if (idx == summaries.lastIndex) "NEWS_END" else "NEWS_$idx"
                                                    ttsEngine?.speak(summary, TextToSpeech.QUEUE_ADD, null, uId)
                                                }
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    newsLanguageIsTamil = !newsLanguageIsTamil
                                    prefs.edit().putBoolean("news_lang_tamil", newsLanguageIsTamil).apply()
                                    val toastMsg = if (newsLanguageIsTamil) "News Language: தமிழ் (Tamil)" else "News Language: English"
                                    android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                    ) {
                        RadioVectorIcon(
                            tint = if (isReadingNews) Color.White else Color.White.copy(alpha = 0.7f)
                        )
                        // White Dot Indicator (•) when new unheard news arrives
                        if (hasUnheardNews) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(6.dp)
                                    .background(Color.White, CircleShape)
                            )
                        }
                    }

                    // 3. Music Toggle Button (♪)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .border(
                                width = 1.2.dp,
                                color = if (showMusicPlayer) Color.White else Color.White.copy(alpha = 0.45f),
                                shape = CircleShape
                            )
                            .background(
                                color = if (showMusicPlayer) Color.White.copy(alpha = 0.22f) else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { showMusicPlayer = !showMusicPlayer }
                    ) {
                        Text(
                            text = "♪",
                            color = if (showMusicPlayer) Color.White else Color.White.copy(alpha = 0.65f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 4. App Drawer Button (→)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .border(1.2.dp, Color.White, CircleShape)
                            .clickable {
                                isSelectingCustomApp = false
                                showDrawer = true
                            }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open App Drawer",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Creation Input Box + Smart Category/Duration Tags
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
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(onClick = { addCurrentItem() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Item",
                            tint = Color.White
                        )
                    }
                }

                // Smart Category & Planned Duration Selector (Appears while typing a Task)
                if (!isAddingEvent && newTitleText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Category: [${TASK_CATEGORIES[selectedCategoryIndex]}]",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .border(0.8.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                                .clickable {
                                    selectedCategoryIndex = (selectedCategoryIndex + 1) % TASK_CATEGORIES.size
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Text(
                            text = "Plan: [${PLANNED_MINUTES_OPTIONS[selectedPlannedIndex]}m]",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .border(0.8.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                                .clickable {
                                    selectedPlannedIndex = (selectedPlannedIndex + 1) % PLANNED_MINUTES_OPTIONS.size
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
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

            // Main Scrollable Tasks & Events List
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
                            text = "No pending tasks",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }

                items(todos, key = { "todo_${it.id}" }) { item ->
                    val ageDays = ((System.currentTimeMillis() - item.createdAtMs) / (1000L * 60 * 60 * 24)).toInt()
                    val ageTag = if (ageDays >= 3) " · ⚠️ ${ageDays}d old" else ""
                    val blockerTag = if (item.blocker != "None") " · [${item.blocker}]" else ""

                    AgendaRowItem(
                        title = item.text,
                        subtitleHighlight = item.category,
                        subtitleRest = "  ·  Plan: ${item.plannedMinutes}m$ageTag$blockerTag",
                        onTapRemove = { completeAndArchiveTask(item) },
                        onLongPress = { focusedTask = item },
                        onEdit = {
                            editingTodo = item
                            editTodoText = item.text
                            editTodoCategory = item.category
                            editTodoPlannedMins = item.plannedMinutes
                            editTodoBlocker = item.blocker
                        },
                        onDelete = {
                            todos = todos.filter { it.id != item.id }
                            saveTodos(prefs, "saved_todos", todos)
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

            if (showMusicPlayer) {
                Spacer(modifier = Modifier.height(8.dp))
                MusicampConnectedWidget(context = context)
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
                    .border(1.5.dp, Color.White, RoundedCornerShape(8.dp))
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

    // Edit Task Dialog (Includes Category, Planned Minutes, and Blocker Status)
    if (editingTodo != null) {
        AlertDialog(
            onDismissRequest = { editingTodo = null },
            containerColor = Color.Black,
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("Edit Task & Metadata") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    Text(
                        text = "Category: [$editTodoCategory] (Tap to change)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .clickable {
                                val nextIdx = (TASK_CATEGORIES.indexOf(editTodoCategory) + 1) % TASK_CATEGORIES.size
                                editTodoCategory = TASK_CATEGORIES[nextIdx]
                            }
                            .padding(10.dp)
                    )
                    Text(
                        text = "Planned Time: [${editTodoPlannedMins}m] (Tap to change)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .clickable {
                                val nextIdx = (PLANNED_MINUTES_OPTIONS.indexOf(editTodoPlannedMins) + 1) % PLANNED_MINUTES_OPTIONS.size
                                editTodoPlannedMins = PLANNED_MINUTES_OPTIONS[nextIdx]
                            }
                            .padding(10.dp)
                    )
                    Text(
                        text = "Blocker: [$editTodoBlocker] (Tap to change)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .clickable {
                                val nextIdx = (BLOCKER_OPTIONS.indexOf(editTodoBlocker) + 1) % BLOCKER_OPTIONS.size
                                editTodoBlocker = BLOCKER_OPTIONS[nextIdx]
                            }
                            .padding(10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = editingTodo
                    if (current != null && editTodoText.isNotBlank()) {
                        todos = todos.map {
                            if (it.id == current.id) {
                                it.copy(
                                    text = editTodoText.trim(),
                                    category = editTodoCategory,
                                    plannedMinutes = editTodoPlannedMins,
                                    blocker = editTodoBlocker
                                )
                            } else it
                        }
                        saveTodos(prefs, "saved_todos", todos)
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

// --- COMPLETED TASKS ARCHIVE SCREEN ([ DONE ]) ---
@Composable
fun CompletedTasksScreen(
    completedTodos: List<TodoItem>,
    onRestoreTask: (TodoItem) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("dd MMM, hh:mm a", Locale.ENGLISH) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "COMPLETED TASKS (${completedTodos.size})",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (completedTodos.isNotEmpty()) {
                    Text(
                        text = "[CLEAR ALL]",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable(onClick = onClearAll)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.25f), thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        if (completedTodos.isEmpty()) {
            Text(
                text = "No completed tasks yet. Single-tap any task on the home screen to complete and archive it here.",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 14.sp
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(completedTodos, key = { "done_${it.id}" }) { item ->
                    val completedTimeStr = remember(item.completedAtMs) {
                        if (item.completedAtMs > 0L) {
                            Instant.ofEpochMilli(item.completedAtMs)
                                .atZone(ZoneId.systemDefault())
                                .format(timeFormatter)
                        } else "Completed"
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "✓ ${item.text}",
                                color = Color.White,
                                fontSize = 15.5.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "$completedTimeStr  ·  ${item.category}  ·  Plan: ${item.plannedMinutes}m",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 11.5.sp
                            )
                        }
                        IconButton(onClick = { onRestoreTask(item) }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Restore to Active",
                                tint = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f), thickness = 0.5.dp)
                }
            }
        }
    }
}

// --- 4-PILLAR EXECUTIVE WORKFLOW & SUSTAINABILITY ANALYTICS DASHBOARD (📊) ---
@Composable
fun AnalyticsDashboardScreen(
    activeTodos: List<TodoItem>,
    completedTodos: List<TodoItem>,
    workedSecondsToday: Long,
    prefs: SharedPreferences,
    onClose: () -> Unit
) {
    val today = LocalDate.now()
    val startOfTodayMs = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val sevenDaysAgoMs = today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // 1. PRODUCTIVITY & CAPACITY METRICS
    val completedToday = completedTodos.filter { it.completedAtMs >= startOfTodayMs }
    val completedLast7Days = completedTodos.filter { it.completedAtMs >= sevenDaysAgoMs }

    val plannedMinsToday = (completedToday.sumOf { it.plannedMinutes } + activeTodos.sumOf { it.plannedMinutes }).coerceAtLeast(1)
    val actualMinsToday = (workedSecondsToday / 60.0).roundToInt()
    val planningAccuracy = ((actualMinsToday.toDouble() / plannedMinsToday.toDouble()) * 100).roundToInt()

    // Peak vs Slump Productivity Windows (By Completion Hour)
    val hourBuckets = completedTodos
        .filter { it.completedAtMs > 0L }
        .groupingBy {
            Instant.ofEpochMilli(it.completedAtMs).atZone(ZoneId.systemDefault()).hour
        }
        .eachCount()

    val peakHour = hourBuckets.maxByOrNull { it.value }?.key
    val slumpHour = hourBuckets.minByOrNull { it.value }?.key
    val peakWindowStr = if (peakHour != null) formatHourWindow(peakHour) else "10:00 AM - 01:00 PM (Est.)"
    val slumpWindowStr = if (slumpHour != null && slumpHour != peakHour) formatHourWindow(slumpHour) else "02:00 PM - 04:00 PM (Est.)"

    val dailyVelocity = completedToday.size
    val weeklyVelocity = completedLast7Days.size
    val avgDailyCapacity = String.format(Locale.ENGLISH, "%.1f", weeklyVelocity / 7.0)

    // 2. TIME ALLOCATION & WORK DISTRIBUTION
    val allTrackedTasks = completedTodos + activeTodos
    val totalPlannedAll = allTrackedTasks.sumOf { it.plannedMinutes }.coerceAtLeast(1)

    val categoryMinutes = TASK_CATEGORIES.associateWith { cat ->
        allTrackedTasks.filter { it.category == cat }.sumOf { it.plannedMinutes }
    }
    val highImpactMinutes = (categoryMinutes["Core/Client"] ?: 0) + (categoryMinutes["Research"] ?: 0)
    val lowImpactMinutes = (categoryMinutes["Admin"] ?: 0) + (categoryMinutes["Growth/Meetings"] ?: 0)
    val highImpactPct = ((highImpactMinutes * 100.0) / totalPlannedAll).roundToInt()
    val lowImpactPct = (100 - highImpactPct).coerceAtLeast(0)

    // 3. WORKFLOW BOTTLENECK & PENDING TASK ANALYSIS
    val nowMs = System.currentTimeMillis()
    val staleOver7d = activeTodos.count { (nowMs - it.createdAtMs) >= 7L * 24 * 3600 * 1000 }
    val aging3to7d = activeTodos.count {
        val age = nowMs - it.createdAtMs
        age >= 3L * 24 * 3600 * 1000 && age < 7L * 24 * 3600 * 1000
    }
    val freshUnder3d = activeTodos.size - staleOver7d - aging3to7d

    val addedTodayCount = prefs.getInt("added_count_$today", activeTodos.size)
    val completedTodayCount = prefs.getInt("completed_count_$today", completedToday.size)
    val capacityStatus = when {
        addedTodayCount > completedTodayCount -> "⚠️ Influx (+$addedTodayCount) > Done ($completedTodayCount): Workload outpacing capacity (Delegate/Automate)"
        completedTodayCount > addedTodayCount -> "✓ Done ($completedTodayCount) > Influx (+$addedTodayCount): Clearing backlog steadily"
        else -> "Balanced: Influx (+$addedTodayCount) matches Completion ($completedTodayCount)"
    }

    val waitingApprovalCount = activeTodos.count { it.blocker == "Waiting Approval" }
    val unclearStepCount = activeTodos.count { it.blocker == "Unclear Next Step" }
    val deprioritizedCount = activeTodos.count { it.blocker == "Deprioritized" }

    // 4. WORK-LIFE BALANCE & SUSTAINABILITY
    val last7DaysHours = (0..6).map { offset ->
        val dayStr = today.minusDays(offset.toLong()).toString()
        val secs = if (offset == 0) workedSecondsToday else prefs.getLong("work_hist_$dayStr", 0L)
        secs / 3600.0
    }
    val activeDaysHours = last7DaysHours.filter { it > 0.0 }.ifEmpty { listOf(workedSecondsToday / 3600.0) }
    val minDailyHours = activeDaysHours.minOrNull() ?: 0.0
    val maxDailyHours = activeDaysHours.maxOrNull() ?: 0.0
    val meanHours = activeDaysHours.average()
    val variance = activeDaysHours.map { (it - meanHours) * (it - meanHours) }.average()
    val stdDevHours = sqrt(variance)

    val burnoutRisk = when {
        maxDailyHours > 9.5 && highImpactPct < 50 -> "HIGH RISK ⚠️ (Long hours + high low-impact admin load)"
        maxDailyHours > 8.5 || stdDevHours > 3.0 -> "MODERATE RISK (Erratic daily spikes detected)"
        else -> "LOW RISK ✓ (Sustainable pace & high-value alignment)"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(22.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EXECUTIVE WORKFLOW ANALYTICS",
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        HorizontalDivider(color = Color.White, thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // SECTION 1: PRODUCTIVITY & CAPACITY
            item {
                AnalyticsSectionCard(
                    title = "1. PRODUCTIVITY & CAPACITY ANALYSIS",
                    lines = listOf(
                        "Actual vs. Planned Today : ${actualMinsToday}m logged / ${plannedMinsToday}m planned ($planningAccuracy%)",
                        "Peak Productivity Window : $peakWindowStr",
                        "Slump Window             : $slumpWindowStr",
                        "Task Velocity            : $dailyVelocity today  ·  $weeklyVelocity this week ($avgDailyCapacity/day)"
                    )
                )
            }

            // SECTION 2: TIME ALLOCATION & WORK DISTRIBUTION
            item {
                val catLines = TASK_CATEGORIES.map { cat ->
                    val mins = categoryMinutes[cat] ?: 0
                    val pct = ((mins * 100.0) / totalPlannedAll).roundToInt()
                    val bars = "█".repeat((pct / 10).coerceIn(0, 10)) + "░".repeat(10 - (pct / 10).coerceIn(0, 10))
                    "${cat.padEnd(16)} : $bars $pct% (${mins}m)"
                }
                val adminWarning = if ((categoryMinutes["Admin"] ?: 0) * 100 / totalPlannedAll >= 50) {
                    "⚠️ Alert: High time spent on low-value Admin (>50%)."
                } else {
                    "✓ Healthy balance toward core high-value objectives."
                }

                AnalyticsSectionCard(
                    title = "2. TIME ALLOCATION & WORK DISTRIBUTION",
                    lines = catLines + listOf(
                        "High vs. Low Impact      : $highImpactPct% High-Impact / $lowImpactPct% Busy Work",
                        adminWarning
                    )
                )
            }

            // SECTION 3: WORKFLOW BOTTLENECK & PENDING TASK ANALYSIS
            item {
                AnalyticsSectionCard(
                    title = "3. WORKFLOW BOTTLENECK & PENDING TASKS",
                    lines = listOf(
                        "Task Aging (Pending)     : $staleOver7d Stale (>7d) · $aging3to7d Aging (3-7d) · $freshUnder3d Fresh",
                        "Completion vs. Influx    : $capacityStatus",
                        "Blocker Identification   : $waitingApprovalCount Waiting Approval · $unclearStepCount Unclear Step · $deprioritizedCount Deprioritized"
                    )
                )
            }

            // SECTION 4: WORK-LIFE BALANCE & SUSTAINABILITY
            item {
                val spreadStr = String.format(
                    Locale.ENGLISH,
                    "Min %.1fh / Max %.1fh (Spread SD: %.1fh)",
                    minDailyHours,
                    maxDailyHours,
                    stdDevHours
                )
                AnalyticsSectionCard(
                    title = "4. WORK-LIFE BALANCE & SUSTAINABILITY",
                    lines = listOf(
                        "Daily Variance (7d)      : $spreadStr",
                        "Burnout Risk Indicator   : $burnoutRisk"
                    )
                )
            }
        }
    }
}

@Composable
fun AnalyticsSectionCard(title: String, lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.2f), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(8.dp))
        lines.forEach { line ->
            Text(
                text = "• $line",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
    }
}

fun formatHourWindow(hour24: Int): String {
    val endHour = (hour24 + 2) % 24
    fun fmt(h: Int): String {
        val ampm = if (h < 12) "AM" else "PM"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.ENGLISH, "%02d:00 %s", h12, ampm)
    }
    return "${fmt(hour24)} - ${fmt(endHour)}"
}

// --- CUSTOM MONOCHROME VECTOR ICONS (📊 & 📻) ---
@Composable
fun AnalyticsVectorIcon(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        // 3 Crisp Bar Chart Columns
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.15f, h * 0.55f),
            size = Size(w * 0.16f, h * 0.35f)
        )
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.42f, h * 0.25f),
            size = Size(w * 0.16f, h * 0.65f)
        )
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.69f, h * 0.40f),
            size = Size(w * 0.16f, h * 0.50f)
        )
    }
}

@Composable
fun RadioVectorIcon(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        drawLine(
            color = tint,
            start = Offset(w * 0.25f, h * 0.35f),
            end = Offset(w * 0.78f, h * 0.12f),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.1f, h * 0.35f),
            size = Size(w * 0.8f, h * 0.52f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = 1.4.dp.toPx())
        )
        drawCircle(
            color = tint,
            radius = w * 0.13f,
            center = Offset(w * 0.33f, h * 0.61f),
            style = Stroke(width = 1.2.dp.toPx())
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.56f, h * 0.53f),
            end = Offset(w * 0.76f, h * 0.53f),
            strokeWidth = 1.2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.56f, h * 0.69f),
            end = Offset(w * 0.76f, h * 0.69f),
            strokeWidth = 1.2.dp.toPx(),
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
                        text = "${task.category} · Deep Focus Session (20m)",
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

// --- ♪ MUSIC AMP WIDGET ---
@Composable
fun MusicampConnectedWidget(context: Context) {
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var isPlaying by remember { mutableStateOf(audioManager.isMusicActive) }
    var trackTitle by remember { mutableStateOf("TAP TO OPEN MUSIC / APPLE MUSIC") }
    var beatWaveBars by remember { mutableStateOf(List(10) { 3 }) }
    var shuffleOn by remember { mutableStateOf(false) }

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
            val active = audioManager.isMusicActive
            isPlaying = active
            if (active) {
                beatWaveBars = List(10) { Random.nextInt(4, 21) }
                if (trackTitle.startsWith("TAP TO")) {
                    trackTitle = "APPLE MUSIC / SPOTIFY ACTIVE"
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
                text = " ♪ MUSIC AMP ",
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
                    .clickable { openMusicPlayer(context) }
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
                onClick = { sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            )

            MusicampButton(
                label = if (isPlaying) "||" else "▶",
                modifier = Modifier.weight(1.25f),
                onClick = {
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    isPlaying = !isPlaying
                }
            )

            MusicampButton(
                label = ">|",
                modifier = Modifier.weight(1f),
                onClick = { sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT) }
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
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f)
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
                            color = Color.White.copy(alpha = 0.55f),
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

// --- 3-SENTENCE NEWS SUMMARIES WITH ANTI-CACHING TIMESTAMP ---
suspend fun fetch3SentenceNewsSummaries(isTamil: Boolean): List<String> = withContext(Dispatchers.IO) {
    val summaries = mutableListOf<String>()
    runCatching {
        val timestamp = System.currentTimeMillis()
        val rssUrl = if (isTamil) {
            "https://news.google.com/rss?hl=ta&gl=IN&ceid=IN:ta&t=$timestamp"
        } else {
            "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en&t=$timestamp"
        }
        val url = URL(rssUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "GET"
            setRequestProperty("Cache-Control", "no-cache")
        }
        conn.inputStream.use { input ->
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(input, "UTF-8")
            var eventType = parser.eventType
            var insideItem = false
            var currentTitle = ""
            var currentDesc = ""

            while (eventType != XmlPullParser.END_DOCUMENT && summaries.size < 4) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase(Locale.ENGLISH)) {
                        "item" -> {
                            insideItem = true
                            currentTitle = ""
                            currentDesc = ""
                        }
                        "title" -> if (insideItem) currentTitle = parser.nextText().trim()
                        "description" -> if (insideItem) currentDesc = parser.nextText().trim()
                    }
                } else if (eventType == XmlPullParser.END_TAG && parser.name.equals("item", ignoreCase = true)) {
                    insideItem = false
                    val cleanTitle = currentTitle.substringBeforeLast(" - ").trim()
                    val plainDesc = currentDesc
                        .replace(Regex("<[^>]*>"), " ")
                        .replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    if (cleanTitle.isNotEmpty()) {
                        val extraSentences = plainDesc
                            .split(Regex("(?<=[.!?])\\s+"))
                            .filter { it.length > 20 && !it.contains(cleanTitle.take(15), ignoreCase = true) }
                            .take(2)

                        val threeSentenceStory = if (extraSentences.size >= 2) {
                            "$cleanTitle. ${extraSentences[0]} ${extraSentences[1]}"
                        } else if (extraSentences.size == 1) {
                            val s3 = if (isTamil) "இது குறித்த கூடுதல் தகவல்கள் தொடர்ந்து வெளியாகி வருகின்றன." else "Further updates on this developing story are being monitored closely."
                            "$cleanTitle. ${extraSentences[0]} $s3"
                        } else {
                            val s2 = if (isTamil) "இந்தச் செய்தி தற்போது முக்கியத்துவம் பெற்று வருகிறது." else "Key developments on this report are unfolding across major networks."
                            val s3 = if (isTamil) "இதன் முழு விவரங்கள் விரைவில் எதிர்பார்க்கப்படுகின்றன." else "Authorities and analysts are tracking the impact closely."
                            "$cleanTitle. $s2 $s3"
                        }
                        summaries.add(threeSentenceStory)
                    }
                }
                eventType = parser.next()
            }
        }
    }
    if (summaries.isEmpty()) {
        if (isTamil) listOf("செய்திகள் கிடைக்கவில்லை.") else listOf("No fresh news available right now.")
    } else {
        summaries
    }
}

// --- WORKED TIME, ANALYTICS HISTORY & SYSTEM HELPERS ---
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

fun saveDailyWorkHistory(prefs: SharedPreferences, dayStr: String, seconds: Long) {
    prefs.edit().putLong("work_hist_$dayStr", seconds).apply()
}

fun incrementDailyStat(prefs: SharedPreferences, key: String) {
    val current = prefs.getInt(key, 0)
    prefs.edit().putInt(key, current + 1).apply()
}

fun openPhonePeQrScanner(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("com.phonepe.app")?.apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("phonepe://pay")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    } ?: Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
}

fun sendMediaKey(context: Context, keyCode: Int) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val eventTime = SystemClock.uptimeMillis()
    val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
    val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
    audioManager.dispatchMediaKeyEvent(downEvent)
    audioManager.dispatchMediaKeyEvent(upEvent)
}

fun openMusicPlayer(context: Context) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_APP_MUSIC)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
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

fun saveTodos(prefs: SharedPreferences, prefKey: String, list: List<TodoItem>) {
    val array = JSONArray()
    list.forEach { item ->
        val obj = JSONObject().apply {
            put("id", item.id)
            put("text", item.text)
            put("category", item.category)
            put("plannedMinutes", item.plannedMinutes)
            put("blocker", item.blocker)
            put("createdAtMs", item.createdAtMs)
            put("completedAtMs", item.completedAtMs)
        }
        array.put(obj)
    }
    prefs.edit().putString(prefKey, array.toString()).apply()
}

fun loadTodos(prefs: SharedPreferences, prefKey: String): List<TodoItem> {
    val raw = prefs.getString(prefKey, null) ?: return emptyList()
    val result = mutableListOf<TodoItem>()
    runCatching {
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                TodoItem(
                    id = obj.getString("id"),
                    text = obj.getString("text"),
                    category = obj.optString("category", "Core/Client"),
                    plannedMinutes = obj.optInt("plannedMinutes", 30),
                    blocker = obj.optString("blocker", "None"),
                    createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                    completedAtMs = obj.optLong("completedAtMs", 0L)
                )
            )
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
            result.add(
                EventItem(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    dateTimeText = obj.optString("dateTimeText", "Upcoming  ·  All day")
                )
            )
        }
    }
    return result
}
