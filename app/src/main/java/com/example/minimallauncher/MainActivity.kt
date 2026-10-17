package com.example.minimallauncher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isDone: Boolean = false
)

data class EventItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val dateTimeText: String = "Tomorrow  ·  All day",
    val isDone: Boolean = false
)

data class AppInfo(
    val label: String,
    val packageName: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    LauncherScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE) }

    var todos by remember { mutableStateOf(loadTodos(prefs)) }
    var events by remember { mutableStateOf(loadEvents(prefs)) }
    var customAppPkg by remember { mutableStateOf(prefs.getString("custom_app_pkg", null)) }

    var showDrawer by remember { mutableStateOf(false) }
    var isSelectingCustomApp by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    var isAddingEvent by remember { mutableStateOf(false) }
    var newTitleText by remember { mutableStateOf("") }
    var newEventDateText by remember { mutableStateOf("") }

    var editingTodo by remember { mutableStateOf<TodoItem?>(null) }
    var editTodoText by remember { mutableStateOf("") }

    var editingEvent by remember { mutableStateOf<EventItem?>(null) }
    var editEventTitle by remember { mutableStateOf("") }
    var editEventDate by remember { mutableStateOf("") }

    var currentDateTime by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentDateTime = LocalDateTime.now()
            delay(60_000L)
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
        if (showDrawer) {
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
    } else {
        val dateFormatted = remember(currentDateTime) {
            val formatter = DateTimeFormatter.ofPattern("dd. MMM.", Locale.ENGLISH)
            "Today | ${currentDateTime.format(formatter)}"
        }
        val hoursRemaining = (24 - currentDateTime.hour).coerceAtLeast(0)
        val dayProgress = (currentDateTime.hour * 60 + currentDateTime.minute) / 1440f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 76.dp)
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = dateFormatted,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Thin,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.DarkGray)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(dayProgress.coerceIn(0.05f, 1f))
                            .height(2.dp)
                            .background(Color.White)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${hoursRemaining}h available",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(22.dp))

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
                        Spacer(modifier = Modifier.width(10.dp))
                        ModeChip(
                            label = "EVENT",
                            selected = isAddingEvent,
                            onClick = { isAddingEvent = true }
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .border(
                                width = 1.5.dp,
                                color = Color.White.copy(alpha = 0.6f),
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
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

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
                                    color = Color.Gray
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
                                focusedBorderColor = Color(0xFF66BB6A),
                                unfocusedBorderColor = Color.DarkGray,
                                cursorColor = Color(0xFF66BB6A)
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
                                    color = Color.Gray,
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
                                focusedBorderColor = Color(0xFF66BB6A),
                                unfocusedBorderColor = Color.DarkGray,
                                cursorColor = Color(0xFF66BB6A)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        Text(
                            text = "TODAY'S TASKS",
                            fontSize = 12.sp,
                            letterSpacing = 1.5.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }

                    if (todos.isEmpty()) {
                        item {
                            Text(
                                text = "No tasks for today",
                                fontSize = 14.sp,
                                color = Color.DarkGray,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }

                    items(todos, key = { "todo_${it.id}" }) { item ->
                        AgendaRowItem(
                            title = item.text,
                            subtitleHighlight = if (item.isDone) "Completed" else "Today",
                            subtitleRest = "  ·  All day",
                            isDone = item.isDone,
                            onToggleDone = {
                                todos = todos.map {
                                    if (it.id == item.id) it.copy(isDone = !it.isDone) else it
                                }
                                saveTodos(prefs, todos)
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
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "UPCOMING EVENTS",
                            fontSize = 12.sp,
                            letterSpacing = 1.5.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }

                    if (events.isEmpty()) {
                        item {
                            Text(
                                text = "No upcoming events",
                                fontSize = 14.sp,
                                color = Color.DarkGray,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }

                    items(events, key = { "event_${it.id}" }) { event ->
                        val parts = event.dateTimeText.split("·", limit = 2)
                        val highlight = parts.getOrNull(0)?.trim() ?: event.dateTimeText
                        val rest = if (parts.size > 1) "  ·  ${parts[1].trim()}" else ""

                        AgendaRowItem(
                            title = event.title,
                            subtitleHighlight = if (event.isDone) "Completed" else highlight,
                            subtitleRest = rest,
                            isDone = event.isDone,
                            onToggleDone = {
                                events = events.map {
                                    if (it.id == event.id) it.copy(isDone = !it.isDone) else it
                                }
                                saveEvents(prefs, events)
                            },
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
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                        .size(36.dp)
                        .border(
                            width = 1.75.dp,
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
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (editingTodo != null) {
        AlertDialog(
            onDismissRequest = { editingTodo = null },
            title = { Text("Edit Task") },
            text = {
                OutlinedTextField(
                    value = editTodoText,
                    onValueChange = { editTodoText = it },
                    singleLine = true
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
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTodo = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (editingEvent != null) {
        AlertDialog(
            onDismissRequest = { editingEvent = null },
            title = { Text("Edit Event") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editEventTitle,
                        onValueChange = { editEventTitle = it },
                        label = { Text("Event Title") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editEventDate,
                        onValueChange = { editEventDate = it },
                        label = { Text("Date / Time") },
                        singleLine = true
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
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingEvent = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (selected) Color(0xFF66BB6A) else Color.DarkGray,
                shape = RoundedCornerShape(4.dp)
            )
            .background(
                color = if (selected) Color(0xFF66BB6A).copy(alpha = 0.15f) else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "+ $label",
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color(0xFF66BB6A) else Color.Gray
        )
    }
}

@Composable
fun AgendaRowItem(
    title: String,
    subtitleHighlight: String,
    subtitleRest: String,
    isDone: Boolean,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(38.dp)
                    .background(if (isDone) Color.DarkGray else Color(0xFF66BB6A))
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleDone)
            ) {
                Text(
                    text = title,
                    color = if (isDone) Color.Gray else Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    style = TextStyle(
                        textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = subtitleHighlight,
                        color = if (isDone) Color.DarkGray else Color(0xFF66BB6A),
                        fontSize = 12.sp
                    )
                    if (subtitleRest.isNotEmpty()) {
                        Text(
                            text = subtitleRest,
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(
            color = Color.DarkGray.copy(alpha = 0.5f),
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
            modifier = Modifier.size(28.dp)
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
        Spacer(modifier = Modifier.height(16.dp))

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
                        color = Color.Gray
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.DarkGray
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
            put("isDone", item.isDone)
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
            result.add(
                TodoItem(
                    id = obj.getString("id"),
                    text = obj.getString("text"),
                    isDone = obj.getBoolean("isDone")
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
            put("isDone", item.isDone)
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
                    dateTimeText = obj.optString("dateTimeText", "Upcoming  ·  All day"),
                    isDone = obj.optBoolean("isDone", false)
                )
            )
        }
    }
    return result
}
