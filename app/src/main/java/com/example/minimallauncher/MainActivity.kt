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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

val PLANNED_MINUTES_OPTIONS = listOf(20, 30, 60, 120)
val BLOCKER_OPTIONS = listOf("None", "Waiting Approval", "Unclear Next Step", "Deprioritized")

data class UserCategory(
    val name: String,
    val isHighImpact: Boolean = true
)

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val category: String,
    val plannedMinutes: Int = 30,
    val blocker: String = "None",
    val createdAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long = 0L
)

data class EventItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startTime: String = "09:00",
    val endTime: String = "",
    val dateLabel: String = "Today"
)

data class AppInfo(
    val label: String,
    val packageName: String
)

data class WellnessPopupData(
    val type: String,
    val title: String,
    val subtitle: String,
    val bullets: List<String>
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
    val prefs = remember { context.getSharedPreferences("launcher_prefs_v4", Context.MODE_PRIVATE) }

    var userCategories by remember { mutableStateOf(loadUserCategories(prefs)) }
    var todos by remember { mutableStateOf(loadTodos(prefs, "saved_todos")) }
    var completedTodos by remember { mutableStateOf(loadTodos(prefs, "completed_todos")) }
    var events by remember { mutableStateOf(loadEvents(prefs)) }
    var customAppPkg by remember { mutableStateOf(prefs.getString("custom_app_pkg", null)) }
    var showMusicPlayer by remember { mutableStateOf(false) }

    // Switchable View Mode: "TASKS" or "TIMELINE"
    var homeSectionTab by remember { mutableStateOf("TASKS") }

    // Inline Bottom-Right App Search Bar State
    var isAppSearchExpanded by remember { mutableStateOf(false) }
    var inlineAppSearchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    var currentJobId by remember { mutableStateOf(prefs.getString("current_job_id", null)) }
    val activeJobItem = remember(todos, currentJobId) {
        todos.find { it.id == currentJobId }
    }

    var showDrawer by remember { mutableStateOf(false) }
    var isSelectingCustomApp by remember { mutableStateOf(false) }
    var showCompletedModal by remember { mutableStateOf(false) }
    var showAnalyticsModal by remember { mutableStateOf(false) }
    var showNewItemModal by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var newCategoryHighImpact by remember { mutableStateOf(true) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

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

    // Smooth Breathing / Blinking Pulse Animation for the Active Working Dot
    val infiniteTransition = rememberInfiniteTransition(label = "dot_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Continuous Work Session Tracking & Random Pop-Up Trigger (> 20 Mins Continuous Only)
    var continuousWorkSeconds by remember { mutableLongStateOf(0L) }
    var nextRandomPopupAtSec by remember {
        mutableLongStateOf(20 * 60L + Random.nextLong(15, 180))
    }
    var activeWellnessPopup by remember { mutableStateOf<WellnessPopupData?>(null) }

    var focusedTask by remember { mutableStateOf<TodoItem?>(null) }
    var focusRemainingSeconds by remember { mutableIntStateOf(20 * 60) }

    var isCreatingEvent by remember { mutableStateOf(false) }
    var newTitleText by remember { mutableStateOf("") }
    var newEventStartTime by remember { mutableStateOf("12:10") }
    var newEventEndTime by remember { mutableStateOf("13:10") }
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var selectedPlannedIndex by remember { mutableIntStateOf(1) }

    var editingTodo by remember { mutableStateOf<TodoItem?>(null) }
    var editTodoText by remember { mutableStateOf("") }
    var editTodoCategory by remember { mutableStateOf("") }
    var editTodoPlannedMins by remember { mutableIntStateOf(30) }
    var editTodoBlocker by remember { mutableStateOf("None") }

    var editingEvent by remember { mutableStateOf<EventItem?>(null) }
    var editEventTitle by remember { mutableStateOf("") }
    var editEventStart by remember { mutableStateOf("") }
    var editEventEnd by remember { mutableStateOf("") }

    var isReadingNews by remember { mutableStateOf(false) }
    var newsLanguageIsTamil by remember { mutableStateOf(prefs.getBoolean("news_lang_tamil", false)) }
    var hasUnheardNews by remember { mutableStateOf(false) }
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

    LaunchedEffect(newsLanguageIsTamil) {
        while (true) {
            val summaries = fetch3SentenceNewsSummaries(newsLanguageIsTamil)
            val topStory = summaries.firstOrNull() ?: ""
            val lastHeard = prefs.getString("last_heard_headline", "") ?: ""
            if (topStory.isNotBlank() && topStory != lastHeard && !topStory.startsWith("No fresh")) {
                hasUnheardNews = true
            }
            delay(10 * 60 * 1000L)
        }
    }

    LaunchedEffect(isAppSearchExpanded) {
        if (isAppSearchExpanded) {
            delay(100L)
            runCatching { searchFocusRequester.requestFocus() }
        } else {
            inlineAppSearchQuery = ""
        }
    }

    var currentDateTime by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(isWorkTimerRunning, workTimerStartMs, savedWorkSecondsToday, focusedTask) {
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

            val activelyWorkingNow = isWorkTimerRunning || focusedTask != null

            if (isWorkTimerRunning && workTimerStartMs > 0L) {
                val elapsed = ((System.currentTimeMillis() - workTimerStartMs) / 1000L).coerceAtLeast(0L)
                displayedWorkSeconds = savedWorkSecondsToday + elapsed
                continuousWorkSeconds = elapsed
                saveDailyWorkHistory(prefs, currentDay, displayedWorkSeconds)
            } else if (focusedTask != null) {
                displayedWorkSeconds = savedWorkSecondsToday
                continuousWorkSeconds += 1L
            } else {
                displayedWorkSeconds = savedWorkSecondsToday
                continuousWorkSeconds = 0L
                nextRandomPopupAtSec = 20 * 60L + Random.nextLong(15, 180)
            }

            if (activelyWorkingNow &&
                continuousWorkSeconds >= (20 * 60L) &&
                continuousWorkSeconds >= nextRandomPopupAtSec &&
                activeWellnessPopup == null
            ) {
                val taskRef = focusedTask ?: activeJobItem ?: todos.firstOrNull()
                activeWellnessPopup = generateRandomWellnessPopup(
                    task = taskRef,
                    continuousMinutes = (continuousWorkSeconds / 60L).toInt()
                )
                nextRandomPopupAtSec = continuousWorkSeconds + Random.nextLong(8 * 60L, 18 * 60L)
            }

            delay(1000L)
        }
    }

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
            activeWellnessPopup != null -> activeWellnessPopup = null
            focusedTask != null -> { }
            isAppSearchExpanded -> {
                isAppSearchExpanded = false
                inlineAppSearchQuery = ""
            }
            showNewItemModal -> showNewItemModal = false
            showAnalyticsModal -> showAnalyticsModal = false
            showCompletedModal -> showCompletedModal = false
            showDrawer -> {
                showDrawer = false
                isSelectingCustomApp = false
            }
        }
    }

    val toggleWorkTimer: (Boolean?) -> Unit = { forceState ->
        val targetRun = forceState ?: !isWorkTimerRunning
        if (!targetRun && isWorkTimerRunning) {
            val elapsed = ((System.currentTimeMillis() - workTimerStartMs) / 1000L).coerceAtLeast(0L)
            savedWorkSecondsToday += elapsed
            isWorkTimerRunning = false
            workTimerStartMs = 0L
            continuousWorkSeconds = 0L
            val dayStr = LocalDate.now().toString()
            prefs.edit()
                .putLong("work_seconds_today", savedWorkSecondsToday)
                .putLong("work_timer_start_ms", 0L)
                .apply()
            saveDailyWorkHistory(prefs, dayStr, savedWorkSecondsToday)
        } else if (targetRun && !isWorkTimerRunning) {
            val nowMs = System.currentTimeMillis()
            workTimerStartMs = nowMs
            isWorkTimerRunning = true
            continuousWorkSeconds = 0L
            nextRandomPopupAtSec = 20 * 60L + Random.nextLong(15, 180)
            if (currentJobId == null && todos.isNotEmpty()) {
                currentJobId = todos.first().id
                prefs.edit().putString("current_job_id", currentJobId).apply()
            }
            prefs.edit().putLong("work_timer_start_ms", nowMs).apply()
        }
    }

    val completeAndArchiveTask: (TodoItem) -> Unit = { item ->
        val archived = item.copy(completedAtMs = System.currentTimeMillis())
        todos = todos.filter { it.id != item.id }
        completedTodos = listOf(archived) + completedTodos
        if (currentJobId == item.id) {
            val nextJob = todos.firstOrNull()?.id
            currentJobId = nextJob
            prefs.edit().putString("current_job_id", nextJob).apply()
            if (nextJob == null && isWorkTimerRunning) {
                toggleWorkTimer(false)
            }
        }
        saveTodos(prefs, "saved_todos", todos)
        saveTodos(prefs, "completed_todos", completedTodos)
        incrementDailyStat(prefs, "completed_count_${LocalDate.now()}")
    }

    val activeScreenState = when {
        focusedTask != null -> "FOCUS"
        showAnalyticsModal -> "ANALYTICS"
        showCompletedModal -> "COMPLETED"
        showDrawer -> "DRAWER"
        else -> "HOME"
    }

    AnimatedContent(
        targetState = activeScreenState,
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
        },
        label = "screen_transition"
    ) { screen ->
        when (screen) {
            "FOCUS" -> {
                if (focusedTask != null) {
                    FocusLockScreen(
                        task = focusedTask!!,
                        remainingSeconds = focusRemainingSeconds,
                        pulseAlpha = pulseAlpha,
                        onMarkCompleted = {
                            val target = focusedTask!!
                            completeAndArchiveTask(target)
                            runCatching { activity.stopLockTask() }
                            focusedTask = null
                        }
                    )
                }
            }
            "ANALYTICS" -> {
                AnalyticsDashboardScreen(
                    userCategories = userCategories,
                    activeTodos = todos,
                    completedTodos = completedTodos,
                    workedSecondsToday = displayedWorkSeconds,
                    prefs = prefs,
                    onClose = { showAnalyticsModal = false }
                )
            }
            "COMPLETED" -> {
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
            }
            "DRAWER" -> {
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
            }
            else -> {
                val timeFormatted = remember(currentDateTime) {
                    currentDateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
                }
                val dateFormatted = remember(currentDateTime) {
                    val dayName = currentDateTime.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
                    val dayNum = currentDateTime.dayOfMonth
                    val suffix = getDayOfMonthSuffix(dayNum)
                    val monthName = currentDateTime.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
                    "$dayName, $dayNum$suffix $monthName"
                }

                val startOfTodayMs = remember(todayKey) {
                    LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                val completedTodayList = remember(completedTodos, startOfTodayMs) {
                    completedTodos.filter { it.completedAtMs >= startOfTodayMs }
                }
                val totalTasksToday = completedTodayList.size + todos.size
                val taskRatio = if (totalTasksToday > 0) {
                    completedTodayList.size.toFloat() / totalTasksToday.toFloat()
                } else 0f

                val totalPlannedSecondsToday = ((completedTodayList.sumOf { it.plannedMinutes } + todos.sumOf { it.plannedMinutes }).coerceAtLeast(60)) * 60f
                val workRatio = (displayedWorkSeconds.toFloat() / totalPlannedSecondsToday).coerceIn(0f, 1f)

                val targetProgress = if (totalTasksToday > 0) {
                    ((taskRatio * 0.6f) + (workRatio * 0.4f)).coerceIn(0f, 1f)
                } else {
                    (displayedWorkSeconds.toFloat() / (4 * 3600f)).coerceIn(0f, 1f)
                }

                val animatedProgress by animateFloatAsState(
                    targetValue = targetProgress.coerceIn(0.04f, 1f),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "productivity_bar_anim"
                )
                val productivityPct = (targetProgress * 100).roundToInt()

                // Compute Data for the 3 Home-Screen Micro-Charts
                val last7DaysCompletedCounts = remember(completedTodos, todayKey) {
                    val today = LocalDate.now()
                    (6 downTo 0).map { offset ->
                        val day = today.minusDays(offset.toLong())
                        val dayStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val dayEnd = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val count = completedTodos.count { it.completedAtMs in dayStart until dayEnd }
                        val label = day.dayOfWeek.name.take(1)
                        label to count
                    }
                }

                val allTrackedTasks = completedTodos + todos
                val totalPlannedAll = allTrackedTasks.sumOf { it.plannedMinutes }.coerceAtLeast(1)
                val highImpactSet = userCategories.filter { it.isHighImpact }.map { it.name.lowercase(Locale.ENGLISH) }.toSet()
                val highImpactMinutes = allTrackedTasks
                    .filter { highImpactSet.contains(it.category.lowercase(Locale.ENGLISH)) }
                    .sumOf { it.plannedMinutes }
                val highImpactPct = if (allTrackedTasks.isEmpty()) 100 else ((highImpactMinutes * 100.0) / totalPlannedAll).roundToInt().coerceIn(0, 100)

                val workedHours = displayedWorkSeconds / 3600
                val workedMinutes = (displayedWorkSeconds % 3600) / 60
                val workedFormatted = String.format(Locale.ENGLISH, "%dh %02dm", workedHours, workedMinutes)

                // Filtered Apps for Bottom-Right Floating Search Bar
                val matchingQuickApps = remember(installedApps, inlineAppSearchQuery) {
                    if (inlineAppSearchQuery.isBlank()) emptyList()
                    else installedApps.filter {
                        it.label.contains(inlineAppSearchQuery, ignoreCase = true)
                    }.take(5)
                }

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
                        .padding(horizontal = 22.dp, vertical = 14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 64.dp)
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = timeFormatted,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = dateFormatted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White.copy(alpha = 0.65f)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Single Rounded Capsule Progress Bar + Equal-Width Labels Below
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2B2B2B))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(animatedProgress)
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Productivity in progress $productivityPct%",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isWorkTimerRunning) "▶ Working $workedFormatted" else "Worked $workedFormatted",
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isWorkTimerRunning) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isWorkTimerRunning) Color.White else Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.clickable { toggleWorkTimer(null) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // --- 3 COMPACT PERFORMANCE MICRO-CHARTS ROW ---
                        PerformanceMicroChartsRow(
                            productivityPct = productivityPct,
                            animatedProgress = animatedProgress,
                            last7DaysCounts = last7DaysCompletedCounts,
                            highImpactPct = highImpactPct,
                            onOpenAnalytics = { showAnalyticsModal = true }
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Rounded Pill & Circle Action Controls: ( + New ) ( Done · X )  &  (📊) (📻) (♪) (→)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .clickable {
                                            newTitleText = ""
                                            newEventStartTime = "12:10"
                                            newEventEndTime = "13:10"
                                            isCreatingEvent = (homeSectionTab == "TIMELINE")
                                            showNewItemModal = true
                                        }
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "+ New",
                                        color = Color.Black,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFF1C1C1C))
                                        .clickable { showCompletedModal = true }
                                        .padding(horizontal = 13.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "Done · ${completedTodos.size}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1C1C1C))
                                        .clickable { showAnalyticsModal = true }
                                ) {
                                    AnalyticsVectorIcon(tint = Color.White)
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (isReadingNews) Color.White else Color(0xFF1C1C1C))
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
                                        tint = if (isReadingNews) Color.Black else Color.White
                                    )
                                    if (hasUnheardNews && !isReadingNews) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(6.dp)
                                                .background(Color.White, CircleShape)
                                        )
                                    }
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (showMusicPlayer) Color.White else Color(0xFF1C1C1C))
                                        .clickable { showMusicPlayer = !showMusicPlayer }
                                ) {
                                    Text(
                                        text = "♪",
                                        color = if (showMusicPlayer) Color.Black else Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .clickable {
                                            isSelectingCustomApp = false
                                            showDrawer = true
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Open App Menu",
                                        tint = Color.Black,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // --- SWITCHABLE ( Tasks | Timeline ) PILL TOGGLE ---
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF161616))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (homeSectionTab == "TASKS") Color.White else Color.Transparent)
                                    .clickable { homeSectionTab = "TASKS" }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Tasks (${todos.size})",
                                    color = if (homeSectionTab == "TASKS") Color.Black else Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (homeSectionTab == "TIMELINE") Color.White else Color.Transparent)
                                    .clickable { homeSectionTab = "TIMELINE" }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Timeline (${events.size})",
                                    color = if (homeSectionTab == "TIMELINE") Color.Black else Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // --- SWITCHABLE CONTENT: TASKS LIST OR VERTICAL TIMELINE ---
                        AnimatedContent(
                            targetState = homeSectionTab,
                            transitionSpec = {
                                fadeIn(tween(180)) togetherWith fadeOut(tween(150))
                            },
                            modifier = Modifier.weight(1f),
                            label = "tasks_timeline_switch"
                        ) { tab ->
                            if (tab == "TASKS") {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    if (todos.isEmpty()) {
                                        item {
                                            Text(
                                                text = "Tap + New to add a task",
                                                fontSize = 14.sp,
                                                color = Color.White.copy(alpha = 0.4f),
                                                modifier = Modifier.padding(vertical = 12.dp)
                                            )
                                        }
                                    }

                                    items(todos, key = { "todo_${it.id}" }) { item ->
                                        val isSelectedJob = currentJobId == item.id
                                        val isActivelyWorkingThisJob = isSelectedJob && isWorkTimerRunning
                                        val ageDays = ((System.currentTimeMillis() - item.createdAtMs) / (1000L * 60 * 60 * 24)).toInt()
                                        val ageTag = if (ageDays >= 3) " · ${ageDays}d old" else ""
                                        val blockerTag = if (item.blocker != "None") " · ${item.blocker}" else ""
                                        val statusTag = if (isActivelyWorkingThisJob) " · Working now" else ""

                                        MinimalTaskRow(
                                            title = item.text,
                                            subtitle = "${item.category} · ${item.plannedMinutes}m$statusTag$ageTag$blockerTag",
                                            isWorkingTask = isActivelyWorkingThisJob,
                                            pulseAlpha = pulseAlpha,
                                            pulseScale = pulseScale,
                                            showDot = true,
                                            onToggleWorkThisTask = {
                                                if (isActivelyWorkingThisJob) {
                                                    toggleWorkTimer(false)
                                                } else {
                                                    currentJobId = item.id
                                                    prefs.edit().putString("current_job_id", item.id).apply()
                                                    toggleWorkTimer(true)
                                                }
                                            },
                                            onTapComplete = { completeAndArchiveTask(item) },
                                            onLongPress = {
                                                currentJobId = item.id
                                                prefs.edit().putString("current_job_id", item.id).apply()
                                                focusedTask = item
                                            },
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
                                }
                            } else {
                                // VERTICAL TIMELINE VIEW (MATCHING REFERENCE IMAGE)
                                VerticalTimelineView(
                                    events = events,
                                    pendingTodos = todos,
                                    onCompleteEvent = { event ->
                                        events = events.filter { it.id != event.id }
                                        saveEvents(prefs, events)
                                    },
                                    onEditEvent = { event ->
                                        editingEvent = event
                                        editEventTitle = event.title
                                        editEventStart = event.startTime
                                        editEventEnd = event.endTime
                                    },
                                    onStartTodoFromFreeTime = { todo ->
                                        currentJobId = todo.id
                                        prefs.edit().putString("current_job_id", todo.id).apply()
                                        toggleWorkTimer(true)
                                        homeSectionTab = "TASKS"
                                    }
                                )
                            }
                        }

                        // Smooth Animated Expansion for Musicamp Widget
                        AnimatedVisibility(
                            visible = showMusicPlayer,
                            enter = fadeIn(tween(220)) + expandVertically(tween(240)),
                            exit = fadeOut(tween(180)) + shrinkVertically(tween(200))
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(6.dp))
                                MusicampConnectedWidget(context = context)
                            }
                        }
                    }

                    // --- BOTTOM FLOATING INSTALLED APP SEARCH RESULTS + SEARCH BAR + 4-ICON DOCK ---
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        // Instant App Results Popover Above Search Bar
                        AnimatedVisibility(
                            visible = isAppSearchExpanded && matchingQuickApps.isNotEmpty(),
                            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                            exit = fadeOut(tween(140)) + shrinkVertically(tween(160))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFF181818))
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
                                    .padding(vertical = 6.dp, horizontal = 14.dp)
                            ) {
                                matchingQuickApps.forEach { app ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                isAppSearchExpanded = false
                                                inlineAppSearchQuery = ""
                                                launchPackage(context, app.packageName)
                                            }
                                            .padding(vertical = 9.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = app.label,
                                            color = Color.White,
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Open ↗",
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Bottom Row: Either Expanded App Search Pill OR Original 4-Icon Dock + Right Search Circle
                        if (isAppSearchExpanded) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E1E1E))
                                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                    .padding(horizontal = 14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                BasicTextField(
                                    value = inlineAppSearchQuery,
                                    onValueChange = { inlineAppSearchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                                    cursorBrush = SolidColor(Color.White),
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(searchFocusRequester),
                                    decorationBox = { innerTextField ->
                                        if (inlineAppSearchQuery.isEmpty()) {
                                            Text(
                                                text = "Search installed apps...",
                                                color = Color.White.copy(alpha = 0.45f),
                                                fontSize = 14.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2E2E2E))
                                        .clickable {
                                            isAppSearchExpanded = false
                                            inlineAppSearchQuery = ""
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Search",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DockIconButton(icon = Icons.Default.Call, description = "Dialer") {
                                    openDialer(context)
                                }

                                DockIconButton(icon = Icons.Default.Email, description = "Messages") {
                                    openMessages(context)
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

                                // Bottom-Right Dark Circular Search Button (Matching Reference Screenshot)
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF242424))
                                        .clickable { isAppSearchExpanded = true }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search Installed Apps",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ROUNDED POP-UP SHEET FOR (+ New) TASK OR EVENT ---
    if (showNewItemModal) {
        AlertDialog(
            onDismissRequest = { showNewItemModal = false },
            containerColor = Color(0xFF121212),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (!isCreatingEvent) Color.White else Color(0xFF262626))
                                .clickable { isCreatingEvent = false }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Task",
                                color = if (!isCreatingEvent) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isCreatingEvent) Color.White else Color(0xFF262626))
                                .clickable { isCreatingEvent = true }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Event",
                                color = if (isCreatingEvent) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    IconButton(onClick = { showNewItemModal = false }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.7f))
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newTitleText,
                        onValueChange = { newTitleText = it },
                        placeholder = {
                            Text(
                                text = if (isCreatingEvent) "Event title (e.g., Lunch MT, Client Call)..." else "What needs to be done?",
                                color = Color.White.copy(alpha = 0.45f)
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                            cursorColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isCreatingEvent) {
                        Text(
                            text = "TIMELINE SCHEDULE (24H FORMAT)",
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newEventStartTime,
                                onValueChange = { newEventStartTime = it },
                                label = { Text("Start (12:10)", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = newEventEndTime,
                                onValueChange = { newEventEndTime = it },
                                label = { Text("End (13:10)", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Text(
                            text = "CATEGORY (USER-CREATED)",
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            userCategories.forEachIndexed { idx, cat ->
                                val selected = idx == selectedCategoryIndex
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (selected) Color.White else Color(0xFF262626))
                                        .clickable { selectedCategoryIndex = idx }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = cat.name,
                                        color = if (selected) Color.Black else Color.White,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .border(1.dp, Color.White, CircleShape)
                                    .clickable {
                                        newCategoryName = ""
                                        newCategoryHighImpact = true
                                        showAddCategoryDialog = true
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "+ Create Category",
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = "PLANNED DURATION",
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PLANNED_MINUTES_OPTIONS.forEachIndexed { idx, mins ->
                                val selected = idx == selectedPlannedIndex
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (selected) Color.White else Color(0xFF262626))
                                        .clickable { selectedPlannedIndex = idx }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "${mins}m",
                                        color = if (selected) Color.Black else Color.White,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
                            if (newTitleText.isNotBlank()) {
                                if (isCreatingEvent) {
                                    val sTime = newEventStartTime.trim().ifBlank { "09:00" }
                                    val eTime = newEventEndTime.trim()
                                    val updatedList = (events + EventItem(
                                        title = newTitleText.trim(),
                                        startTime = sTime,
                                        endTime = eTime
                                    )).sortedBy { parseMinutesFromHHMM(it.startTime) ?: 9999 }
                                    events = updatedList
                                    saveEvents(prefs, events)
                                    homeSectionTab = "TIMELINE"
                                    showNewItemModal = false
                                } else {
                                    if (userCategories.isEmpty()) {
                                        newCategoryName = ""
                                        newCategoryHighImpact = true
                                        showAddCategoryDialog = true
                                    } else {
                                        val chosenCat = userCategories.getOrElse(selectedCategoryIndex) { userCategories.first() }.name
                                        val newItem = TodoItem(
                                            text = newTitleText.trim(),
                                            category = chosenCat,
                                            plannedMinutes = PLANNED_MINUTES_OPTIONS[selectedPlannedIndex]
                                        )
                                        todos = listOf(newItem) + todos
                                        if (currentJobId == null) {
                                            currentJobId = newItem.id
                                            prefs.edit().putString("current_job_id", newItem.id).apply()
                                        }
                                        saveTodos(prefs, "saved_todos", todos)
                                        incrementDailyStat(prefs, "added_count_${LocalDate.now()}")
                                        homeSectionTab = "TASKS"
                                        showNewItemModal = false
                                    }
                                }
                            }
                        }
                ) {
                    Text(
                        text = if (isCreatingEvent) "Add to Timeline" else "Save Task",
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // --- CREATE CUSTOM USER CATEGORY DIALOG ---
    if (showAddCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            containerColor = Color(0xFF121212),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Create Category", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        placeholder = { Text("e.g., MCOP, Drafting, Claims...", color = Color.White.copy(alpha = 0.4f)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "ANALYTICS IMPACT TYPE",
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (newCategoryHighImpact) Color.White else Color(0xFF262626))
                                .clickable { newCategoryHighImpact = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "High-Impact",
                                color = if (newCategoryHighImpact) Color.Black else Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (!newCategoryHighImpact) Color.White else Color(0xFF262626))
                                .clickable { newCategoryHighImpact = false }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Admin / Busy",
                                color = if (!newCategoryHighImpact) Color.Black else Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
                            val trimmed = newCategoryName.trim()
                            if (trimmed.isNotEmpty()) {
                                if (userCategories.none { it.name.equals(trimmed, ignoreCase = true) }) {
                                    userCategories = userCategories + UserCategory(trimmed, newCategoryHighImpact)
                                    saveUserCategories(prefs, userCategories)
                                }
                                selectedCategoryIndex = userCategories.indexOfFirst {
                                    it.name.equals(trimmed, ignoreCase = true)
                                }.coerceAtLeast(0)
                                if (editingTodo != null) {
                                    editTodoCategory = trimmed
                                }
                            }
                            showAddCategoryDialog = false
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text("Add Category", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }

    // --- RANDOM WELLNESS POP-UP DIALOG ---
    if (activeWellnessPopup != null) {
        RandomWellnessPopupDialog(
            popup = activeWellnessPopup!!,
            onDismiss = { activeWellnessPopup = null }
        )
    }

    // --- EDIT TASK DIALOG ---
    if (editingTodo != null) {
        AlertDialog(
            onDismissRequest = { editingTodo = null },
            containerColor = Color(0xFF121212),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Edit Task", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editTodoText,
                        onValueChange = { editTodoText = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(Color(0xFF262626))
                                .clickable {
                                    if (userCategories.isNotEmpty()) {
                                        val idx = userCategories.indexOfFirst { it.name == editTodoCategory }
                                        val nextIdx = (idx + 1) % userCategories.size
                                        editTodoCategory = userCategories[nextIdx].name
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Category: $editTodoCategory",
                                color = Color.White,
                                fontSize = 11.5.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable {
                                    newCategoryName = ""
                                    showAddCategoryDialog = true
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("+ New", color = Color.Black, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(Color(0xFF262626))
                            .clickable {
                                val nextIdx = (PLANNED_MINUTES_OPTIONS.indexOf(editTodoPlannedMins) + 1) % PLANNED_MINUTES_OPTIONS.size
                                editTodoPlannedMins = PLANNED_MINUTES_OPTIONS[nextIdx]
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Planned: ${editTodoPlannedMins}m (Tap to change)", color = Color.White, fontSize = 11.5.sp)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(Color(0xFF262626))
                            .clickable {
                                val nextIdx = (BLOCKER_OPTIONS.indexOf(editTodoBlocker) + 1) % BLOCKER_OPTIONS.size
                                editTodoBlocker = BLOCKER_OPTIONS[nextIdx]
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Blocker: $editTodoBlocker (Tap to change)", color = Color.White, fontSize = 11.5.sp)
                    }
                }
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
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
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTodo = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }

    // --- EDIT TIMELINE EVENT DIALOG ---
    if (editingEvent != null) {
        AlertDialog(
            onDismissRequest = { editingEvent = null },
            containerColor = Color(0xFF121212),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Edit Timeline Event", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editEventTitle,
                        onValueChange = { editEventTitle = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editEventStart,
                            onValueChange = { editEventStart = it },
                            label = { Text("Start", fontSize = 11.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editEventEnd,
                            onValueChange = { editEventEnd = it },
                            label = { Text("End", fontSize = 11.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
                            val current = editingEvent
                            if (current != null && editEventTitle.isNotBlank()) {
                                events = events.map {
                                    if (it.id == current.id) {
                                        it.copy(
                                            title = editEventTitle.trim(),
                                            startTime = editEventStart.trim().ifBlank { "09:00" },
                                            endTime = editEventEnd.trim()
                                        )
                                    } else it
                                }.sortedBy { parseMinutesFromHHMM(it.startTime) ?: 9999 }
                                saveEvents(prefs, events)
                            }
                            editingEvent = null
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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

// --- 3 COMPACT PERFORMANCE MICRO-CHARTS ROW (INSPIRED BY REFERENCE IMAGE) ---
@Composable
fun PerformanceMicroChartsRow(
    productivityPct: Int,
    animatedProgress: Float,
    last7DaysCounts: List<Pair<String, Int>>,
    highImpactPct: Int,
    onOpenAnalytics: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenAnalytics),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Circular Ring Gauge (Like the top-right widget in the reference screenshot)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(0.95f)
                .height(68.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141414))
                .padding(8.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.16f),
                        style = Stroke(width = 3.5.dp.toPx())
                    )
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Text(
                    text = "$productivityPct%",
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "GOAL",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 8.5.sp,
                letterSpacing = 1.sp
            )
        }

        // 2. 7-Day Velocity Mini Spark-Bars
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .weight(1.25f)
                .height(68.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141414))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = "7D VELOCITY",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 8.5.sp,
                letterSpacing = 0.8.sp
            )
            val maxCount = (last7DaysCounts.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                last7DaysCounts.forEach { (dayLabel, count) ->
                    val ratio = (count.toFloat() / maxCount.toFloat()).coerceIn(0.15f, 1f)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height((18.dp * ratio))
                                .clip(CircleShape)
                                .background(if (count > 0) Color.White else Color.White.copy(alpha = 0.2f))
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = dayLabel,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 7.sp
                        )
                    }
                }
            }
        }

        // 3. Core High-Impact Ratio Micro-Bar
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .weight(1.05f)
                .height(68.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141414))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = "CORE FOCUS",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 8.5.sp,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "$highImpactPct%",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((highImpactPct / 100f).coerceIn(0.08f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

// --- VERTICAL TIMELINE VIEW WITH FREE-TIME GAP CARD (MATCHING REFERENCE SCREENSHOT) ---
@Composable
fun VerticalTimelineView(
    events: List<EventItem>,
    pendingTodos: List<TodoItem>,
    onCompleteEvent: (EventItem) -> Unit,
    onEditEvent: (EventItem) -> Unit,
    onStartTodoFromFreeTime: (TodoItem) -> Unit
) {
    if (events.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)) {
            Text(
                text = "No timeline events today. Tap + New → Event to schedule.",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 13.5.sp
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(events.size) { index ->
            val event = events[index]
            val nextEvent = events.getOrNull(index + 1)

            // 1. Event Timeline Node + Rounded Event Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Time Column
                Text(
                    text = event.startTime,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(42.dp)
                )

                // Vertical Timeline Line + Dot
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(20.dp)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Rounded Dark Event Card with Completion Circle Ring on Right
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF161616))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                        .clickable { onEditEvent(event) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val timeRange = if (event.endTime.isNotBlank()) "${event.startTime} ~ ${event.endTime}" else event.startTime
                        Text(
                            text = timeRange,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = event.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Circular Completion Ring (Matches Reference Image)
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                            .clickable { onCompleteEvent(event) }
                    )
                }
            }

            // 2. Check for Free Time Gap Between This Event and Next Event (>= 2 Hours)
            if (nextEvent != null) {
                val endMins = parseMinutesFromHHMM(event.endTime.ifBlank { event.startTime })
                val nextStartMins = parseMinutesFromHHMM(nextEvent.startTime)
                if (endMins != null && nextStartMins != null && (nextStartMins - endMins) >= 120) {
                    val freeHours = (nextStartMins - endMins) / 60
                    val endStr = event.endTime.ifBlank { event.startTime }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.width(42.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = endStr,
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(28.dp))
                            Text(
                                text = nextEvent.startTime,
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .width(20.dp)
                                .fillMaxHeight()
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(Color.White.copy(alpha = 0.25f))
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Free Time Gap Container Card (Matching Reference Image)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 5.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFF111111))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "🕒 Free Time",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "$freeHours hrs",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (pendingTodos.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    pendingTodos.take(4).forEach { todo ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF222222))
                                                .clickable { onStartTodoFromFreeTime(todo) }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = todo.text,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
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
    }
}

fun parseMinutesFromHHMM(timeStr: String): Int? {
    val parts = timeStr.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return h * 60 + m
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MinimalTaskRow(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    isWorkingTask: Boolean,
    pulseAlpha: Float,
    pulseScale: Float,
    showDot: Boolean,
    onToggleWorkThisTask: () -> Unit,
    onTapComplete: () -> Unit,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp)
    ) {
        if (showDot) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onToggleWorkThisTask)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .scale(if (isWorkingTask) pulseScale else 1f)
                        .alpha(if (isWorkingTask) pulseAlpha else 1f)
                        .background(
                            color = if (isWorkingTask) Color.White else Color.Transparent,
                            shape = CircleShape
                        )
                        .border(
                            width = if (isWorkingTask) 1.5.dp else 1.2.dp,
                            color = if (isWorkingTask) Color.White else Color.White.copy(alpha = 0.45f),
                            shape = CircleShape
                        )
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = onTapComplete,
                    onLongClick = onLongPress
                )
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = if (isWorkingTask) FontWeight.Bold else FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = if (isWorkingTask) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.5f),
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onEdit,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit",
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp)
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

fun generateRandomWellnessPopup(task: TodoItem?, continuousMinutes: Int): WellnessPopupData {
    val taskLabel = if (task != null) "${task.text} (${task.category})" else "Active Work Session"
    return when (Random.nextInt(3)) {
        0 -> WellnessPopupData(
            type = "WATER",
            title = "💧 Hydration Break ($continuousMinutes mins)",
            subtitle = "Working on: $taskLabel",
            bullets = listOf(
                "You have worked continuously for $continuousMinutes minutes.",
                "Drink a full glass of water right now to maintain focus.",
                "Rest your eyes by looking 20 feet away for 15 seconds."
            )
        )
        1 -> WellnessPopupData(
            type = "BREATH",
            title = "🌬️ Deep Breath Reset ($continuousMinutes mins)",
            subtitle = "Working on: $taskLabel",
            bullets = listOf(
                "Inhale slowly through your nose for 4 seconds.",
                "Hold your breath gently for 4 seconds.",
                "Exhale completely for 4 seconds (Repeat 3 times)."
            )
        )
        else -> {
            val (workoutTitle, steps) = getWorkoutForTask(task)
            WellnessPopupData(
                type = "WORKOUT",
                title = "💪 $workoutTitle ($continuousMinutes mins)",
                subtitle = "Tailored for: $taskLabel",
                bullets = steps
            )
        }
    }
}

@Composable
fun RandomWellnessPopupDialog(
    popup: WellnessPopupData,
    onDismiss: () -> Unit
) {
    var breathSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            breathSec = (breathSec + 1) % 12
        }
    }
    val breathLabel = when (breathSec) {
        in 0..3 -> "🌬️ Inhale... (${4 - breathSec}s)"
        in 4..7 -> "⏸ Hold... (${8 - breathSec}s)"
        else -> "💨 Exhale... (${12 - breathSec}s)"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121212),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    text = "20M+ CONTINUOUS WORK STREAK",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = popup.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = popup.subtitle,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )

                if (popup.type == "BREATH") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(Color(0xFF242424))
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = breathLabel,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                popup.bullets.forEach { line ->
                    Text(
                        text = "• $line",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onDismiss)
            ) {
                Text(
                    text = "Done — Resume Work",
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

fun getWorkoutForTask(task: TodoItem?): Pair<String, List<String>> {
    if (task == null) {
        return "Posture & Eye Reset" to listOf(
            "10x Slow Seated Neck Rolls & Shoulder Shrugs",
            "20-20-20 Eye Rule: Look 20 feet away for 20 seconds",
            "10x Standing Calf Raises to boost blood circulation"
        )
    }
    val combined = "${task.category} ${task.text}".lowercase(Locale.ENGLISH)
    return when {
        combined.contains("admin") || combined.contains("bill") || combined.contains("pay") -> {
            "Wrist & Squat Reset" to listOf(
                "20s Wrist Flexor & Finger Extension Stretch (both hands)",
                "15x Bodyweight Standing Squats to wake up lower body",
                "10x Scapular Wall Slides to counter desk hunching"
            )
        }
        combined.contains("research") || combined.contains("read") || combined.contains("code") || combined.contains("draft") -> {
            "Neck, Spine & Eye Relief" to listOf(
                "20-20-20 Eye Rule: Focus on a distant object for 20 seconds",
                "30s Seated Spinal Twist (15s left, 15s right)",
                "10x Chin Tucks & Upper Trapezius Neck Stretches"
            )
        }
        combined.contains("meeting") || combined.contains("call") || combined.contains("growth") -> {
            "Decompression Walk & Stretch" to listOf(
                "2-Minute Brisk Walk around the room to release tension",
                "30s Doorway Chest Opener Stretch for deeper breathing",
                "5x Deep Diaphragmatic Box Breaths"
            )
        }
        else -> {
            "Core Desk Mobility" to listOf(
                "10x Seated Neck & Shoulder Rolls",
                "20s Overhead Reach & Side Bend Stretch",
                "12x Standing Desk Push-ups or Calf Raises"
            )
        }
    }
}

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
                text = "Completed (${completedTodos.size})",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (completedTodos.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF1C1C1C))
                            .clickable(onClick = onClearAll)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Clear All", color = Color.White, fontSize = 11.5.sp)
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1C1C1C))
                        .clickable(onClick = onClose)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (completedTodos.isEmpty()) {
            Text(
                text = "No completed tasks yet. Single-tap any task title on the home screen to complete and archive it.",
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
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "$completedTimeStr · ${item.category} · ${item.plannedMinutes}m",
                                color = Color.White.copy(alpha = 0.5f),
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
                }
            }
        }
    }
}

@Composable
fun AnalyticsDashboardScreen(
    userCategories: List<UserCategory>,
    activeTodos: List<TodoItem>,
    completedTodos: List<TodoItem>,
    workedSecondsToday: Long,
    prefs: SharedPreferences,
    onClose: () -> Unit
) {
    val today = LocalDate.now()
    val startOfTodayMs = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val sevenDaysAgoMs = today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val completedToday = completedTodos.filter { it.completedAtMs >= startOfTodayMs }
    val completedLast7Days = completedTodos.filter { it.completedAtMs >= sevenDaysAgoMs }

    val plannedMinsToday = (completedToday.sumOf { it.plannedMinutes } + activeTodos.sumOf { it.plannedMinutes }).coerceAtLeast(1)
    val actualMinsToday = (workedSecondsToday / 60.0).roundToInt()
    val planningAccuracy = ((actualMinsToday.toDouble() / plannedMinsToday.toDouble()) * 100).roundToInt()

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

    val allTrackedTasks = completedTodos + activeTodos
    val totalPlannedAll = allTrackedTasks.sumOf { it.plannedMinutes }.coerceAtLeast(1)

    val allCategoryNames = (userCategories.map { it.name } + allTrackedTasks.map { it.category }).distinct()
    val categoryMinutes = allCategoryNames.associateWith { cat ->
        allTrackedTasks.filter { it.category == cat }.sumOf { it.plannedMinutes }
    }
    val highImpactSet = userCategories.filter { it.isHighImpact }.map { it.name.lowercase(Locale.ENGLISH) }.toSet()
    val highImpactMinutes = allTrackedTasks
        .filter { highImpactSet.contains(it.category.lowercase(Locale.ENGLISH)) }
        .sumOf { it.plannedMinutes }
    val highImpactPct = ((highImpactMinutes * 100.0) / totalPlannedAll).roundToInt().coerceIn(0, 100)
    val lowImpactPct = (100 - highImpactPct).coerceAtLeast(0)

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
        addedTodayCount > completedTodayCount -> "Influx (+$addedTodayCount) > Done ($completedTodayCount): Workload outpacing capacity"
        completedTodayCount > addedTodayCount -> "Done ($completedTodayCount) > Influx (+$addedTodayCount): Clearing backlog steadily"
        else -> "Balanced: Influx (+$addedTodayCount) matches Done ($completedTodayCount)"
    }

    val waitingApprovalCount = activeTodos.count { it.blocker == "Waiting Approval" }
    val unclearStepCount = activeTodos.count { it.blocker == "Unclear Next Step" }
    val deprioritizedCount = activeTodos.count { it.blocker == "Deprioritized" }

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
        maxDailyHours > 9.5 && highImpactPct < 50 -> "HIGH RISK (Long hours + high low-impact load)"
        maxDailyHours > 8.5 || stdDevHours > 3.0 -> "MODERATE RISK (Erratic daily spikes detected)"
        else -> "LOW RISK (Sustainable pace & high-value alignment)"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Workflow Analytics",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1C1C1C))
                    .clickable(onClick = onClose)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AnalyticsSectionCard(
                    title = "1. PRODUCTIVITY & CAPACITY",
                    lines = listOf(
                        "Actual vs Planned : ${actualMinsToday}m logged / ${plannedMinsToday}m planned ($planningAccuracy%)",
                        "Peak Window       : $peakWindowStr",
                        "Slump Window      : $slumpWindowStr",
                        "Task Velocity     : $dailyVelocity today · $weeklyVelocity this week ($avgDailyCapacity/day)"
                    )
                )
            }

            item {
                val catLines = if (allCategoryNames.isEmpty()) {
                    listOf("No categories created yet. Tap + New to create one.")
                } else {
                    allCategoryNames.map { cat ->
                        val mins = categoryMinutes[cat] ?: 0
                        val pct = ((mins * 100.0) / totalPlannedAll).roundToInt()
                        val bars = "█".repeat((pct / 10).coerceIn(0, 10)) + "░".repeat(10 - (pct / 10).coerceIn(0, 10))
                        "${cat.take(14).padEnd(14)} : $bars $pct% (${mins}m)"
                    }
                }
                AnalyticsSectionCard(
                    title = "2. TIME ALLOCATION & CATEGORIES",
                    lines = catLines + listOf(
                        "Impact Ratio   : $highImpactPct% High-Impact / $lowImpactPct% Admin-Busy"
                    )
                )
            }

            item {
                AnalyticsSectionCard(
                    title = "3. WORKFLOW BOTTLENECKS",
                    lines = listOf(
                        "Task Aging     : $staleOver7d Stale (>7d) · $aging3to7d Aging (3-7d) · $freshUnder3d Fresh",
                        "Influx vs Done : $capacityStatus",
                        "Blockers       : $waitingApprovalCount Approval · $unclearStepCount Unclear · $deprioritizedCount Deprioritized"
                    )
                )
            }

            item {
                val spreadStr = String.format(
                    Locale.ENGLISH,
                    "Min %.1fh / Max %.1fh (SD: %.1fh)",
                    minDailyHours,
                    maxDailyHours,
                    stdDevHours
                )
                AnalyticsSectionCard(
                    title = "4. WORK-LIFE SUSTAINABILITY",
                    lines = listOf(
                        "Daily Variance : $spreadStr",
                        "Burnout Risk   : $burnoutRisk"
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
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        lines.forEach { line ->
            Text(
                text = "• $line",
                color = Color.White.copy(alpha = 0.85f),
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

fun getDayOfMonthSuffix(n: Int): String {
    if (n in 11..13) return "th"
    return when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
}

@Composable
fun AnalyticsVectorIcon(tint: Color) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val w = size.width
        val h = size.height
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
    Canvas(modifier = Modifier.size(15.dp)) {
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

@Composable
fun FocusLockScreen(
    task: TodoItem,
    remainingSeconds: Int,
    pulseAlpha: Float,
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
                text = "FOCUS LOCK ACTIVE",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "OTHER APPS BLOCKED FOR 20 MINS",
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF121212))
                    .padding(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .alpha(pulseAlpha)
                        .background(color = Color.White, shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = task.text,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${task.category} · Deep Focus Session (20m)",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onMarkCompleted)
            ) {
                Text(
                    text = "✓ Mark Task Completed",
                    color = Color.Black,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = "LOCKED UNTIL TIMER EXPIRES",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.5.sp,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun MusicampConnectedWidget(context: Context) {
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var isPlaying by remember { mutableStateOf(audioManager.isMusicActive) }
    var trackTitle by remember { mutableStateOf("Tap to open Music / Spotify") }
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
                if (trackTitle.startsWith("Tap to")) {
                    trackTitle = "Apple Music / Spotify Active"
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
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF121212))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .width(68.dp)
                    .height(24.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                beatWaveBars.forEach { h ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(h.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = trackTitle,
                color = Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { openMusicPlayer(context) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MusicampPillButton(
                label = "|<",
                modifier = Modifier.weight(1f),
                onClick = { sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            )

            MusicampPillButton(
                label = if (isPlaying) "||" else "▶",
                modifier = Modifier.weight(1.25f),
                onClick = {
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    isPlaying = !isPlaying
                }
            )

            MusicampPillButton(
                label = ">|",
                modifier = Modifier.weight(1f),
                onClick = { sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT) }
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1.1f)
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(if (shuffleOn) Color.White else Color(0xFF242424))
                    .clickable { shuffleOn = !shuffleOn }
            ) {
                Text(
                    text = "SHUF",
                    color = if (shuffleOn) Color.Black else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MusicampPillButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(Color(0xFF242424))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
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
                shape = CircleShape,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    cursorColor = Color.White
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1C1C1C))
                    .clickable(onClick = onClose)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
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

fun loadUserCategories(prefs: SharedPreferences): List<UserCategory> {
    val raw = prefs.getString("user_categories_v2", null) ?: return emptyList()
    val result = mutableListOf<UserCategory>()
    runCatching {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name", "").trim()
            if (name.isNotEmpty()) {
                result.add(UserCategory(name = name, isHighImpact = obj.optBoolean("isHighImpact", true)))
            }
        }
    }
    return result
}

fun saveUserCategories(prefs: SharedPreferences, list: List<UserCategory>) {
    val arr = JSONArray()
    list.forEach { cat ->
        val obj = JSONObject().apply {
            put("name", cat.name)
            put("isHighImpact", cat.isHighImpact)
        }
        arr.put(obj)
    }
    prefs.edit().putString("user_categories_v2", arr.toString()).apply()
}

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
                    category = obj.optString("category", "General"),
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
            put("startTime", item.startTime)
            put("endTime", item.endTime)
            put("dateLabel", item.dateLabel)
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
                    startTime = obj.optString("startTime", "09:00"),
                    endTime = obj.optString("endTime", ""),
                    dateLabel = obj.optString("dateLabel", "Today")
                )
            )
        }
    }
    return result
}
