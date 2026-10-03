package com.localchat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localchat.app.data.AppState
import com.localchat.app.data.ChatMessage
import com.localchat.app.data.Room
import com.localchat.app.network.ChatEngine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun LocalChatApp(
    chat: ChatEngine,
    onRoomVisible: (String) -> Unit,
    onPickFiles: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val state by chat.state.collectAsState()
    var currentRoomId by remember { mutableStateOf("lobby") }
    var showChat by remember { mutableStateOf(false) }
    val unread = remember { mutableStateMapOf<String, Int>() }
    var error by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<PromptKind?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentRoomId) {
        onRoomVisible(currentRoomId)
        unread.remove(currentRoomId)
    }

    val room = state.rooms.find { it.id == currentRoomId } ?: state.rooms.firstOrNull()

    fun run(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { error = it.message }
        }
    }

    if (error != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(error ?: "") },
        )
    }

    when (val p = prompt) {
        is PromptKind.Rename -> NameDialog(
            title = "Your display name",
            initial = state.me.name,
            confirmLabel = "Save",
            onDismiss = { prompt = null },
            onConfirm = {
                prompt = null
                run { chat.setName(it) }
            },
        )
        is PromptKind.CreateRoom -> NameDialog(
            title = "Create a room",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { prompt = null },
            onConfirm = {
                prompt = null
                run {
                    val created = chat.createRoom(it)
                    currentRoomId = created.id
                    showChat = true
                }
            },
        )
        null -> Unit
    }

    if (showChat && room != null) {
        ChatScreen(
            state = state,
            room = room,
            onBack = { showChat = false },
            onLeave = {
                run {
                    chat.leaveRoom(room.id)
                    currentRoomId = "lobby"
                    showChat = false
                }
            },
            onSend = { text -> run { chat.sendText(room.id, text) } },
            onAttach = { onPickFiles(room.id) },
            onOpenFile = onOpenFile,
        )
    } else {
        HomeScreen(
            state = state,
            currentRoomId = currentRoomId,
            unread = unread,
            onRename = { prompt = PromptKind.Rename },
            onCreateRoom = { prompt = PromptKind.CreateRoom },
            onSelectRoom = {
                currentRoomId = it
                unread.remove(it)
                showChat = true
            },
            onJoinRoom = { id ->
                run {
                    val joined = chat.joinRoom(id)
                    currentRoomId = joined.id
                    showChat = true
                }
            },
            onOpenDirect = { peerId ->
                run {
                    val dm = chat.openDirect(peerId)
                    currentRoomId = dm.id
                    showChat = true
                }
            },
        )
    }

    // Update unread badges when messages change
    UnreadTracker(state, currentRoomId, showChat, unread)
}

@Composable
private fun UnreadTracker(
    state: AppState,
    currentRoomId: String,
    showChat: Boolean,
    unread: MutableMap<String, Int>,
) {
    var seen by remember { mutableStateOf(mapOf<String, String?>()) }
    LaunchedEffect(state.messages, currentRoomId, showChat) {
        val next = mutableMapOf<String, String?>()
        state.messages.forEach { (roomId, list) ->
            val last = list.lastOrNull()
            next[roomId] = last?.id
            val prevId = seen[roomId]
            if (last != null && last.incoming && last.id != prevId && !(showChat && roomId == currentRoomId)) {
                unread[roomId] = (unread[roomId] ?: 0) + 1
            }
        }
        seen = next
    }
}

private sealed class PromptKind {
    data object Rename : PromptKind()
    data object CreateRoom : PromptKind()
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.length <= 40) value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HomeScreen(
    state: AppState,
    currentRoomId: String,
    unread: Map<String, Int>,
    onRename: () -> Unit,
    onCreateRoom: () -> Unit,
    onSelectRoom: (String) -> Unit,
    onJoinRoom: (String) -> Unit,
    onOpenDirect: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRename)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(state.me.name, state.me.id, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(state.me.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Tap to rename", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }

            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { SectionHeader("Rooms", actionIcon = Icons.Default.Add, onAction = onCreateRoom) }
                items(state.rooms.filter { it.type != "dm" }, key = { it.id }) { room ->
                    RoomRow(
                        title = room.name,
                        subtitle = null,
                        icon = if (room.type == "lobby") Icons.Default.Public else Icons.Default.Tag,
                        selected = room.id == currentRoomId,
                        badge = unread[room.id] ?: 0,
                        onClick = { onSelectRoom(room.id) },
                    )
                }

                if (state.discover.isNotEmpty()) {
                    item { SectionHeader("Rooms nearby") }
                    items(state.discover, key = { it.id }) { room ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconBox(Icons.Default.Tag)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(room.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${room.members} online", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onJoinRoom(room.id) }) { Text("Join") }
                        }
                    }
                }

                val dms = state.rooms.filter { it.type == "dm" }
                if (dms.isNotEmpty()) {
                    item { SectionHeader("Direct messages") }
                    items(dms, key = { it.id }) { room ->
                        val online = state.peers.any { it.id == room.peerId }
                        RoomRow(
                            title = state.peers.find { it.id == room.peerId }?.name ?: room.name,
                            subtitle = if (online) "Online" else "Offline",
                            avatarName = state.peers.find { it.id == room.peerId }?.name ?: room.name,
                            avatarId = room.peerId ?: room.id,
                            online = online,
                            selected = room.id == currentRoomId,
                            badge = unread[room.id] ?: 0,
                            onClick = { onSelectRoom(room.id) },
                        )
                    }
                }

                item { SectionHeader("Nearby devices${if (state.peers.isNotEmpty()) " ${state.peers.size}" else ""}") }
                if (state.peers.isEmpty()) {
                    item {
                        Text(
                            "No devices found yet. Open LocalChat on another device on the same Wi‑Fi.",
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    items(state.peers, key = { it.id }) { peer ->
                        RoomRow(
                            title = peer.name,
                            subtitle = peer.device.ifBlank { peer.address },
                            avatarName = peer.name,
                            avatarId = peer.id,
                            online = true,
                            selected = false,
                            badge = 0,
                            onClick = { onOpenDirect(peer.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, actionIcon: ImageVector? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        if (actionIcon != null && onAction != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(28.dp)) {
                Icon(actionIcon, contentDescription = title, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RoomRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    avatarName: String? = null,
    avatarId: String? = null,
    online: Boolean? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            avatarName != null && avatarId != null -> Avatar(avatarName, avatarId, 28.dp, online)
            icon != null -> IconBox(icon)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (badge > 0) {
            Badge { Text(if (badge > 99) "99+" else badge.toString()) }
        }
    }
}

@Composable
private fun IconBox(icon: ImageVector) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Avatar(name: String, id: String, size: androidx.compose.ui.unit.Dp, online: Boolean? = null) {
    val hue = id.fold(7) { acc, c -> (acc * 31 + c.code) % 360 }
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(hslColor(hue.toFloat(), 0.65f, 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (name.trim().firstOrNull() ?: '?').uppercaseChar().toString(),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.4f).sp,
            )
        }
        if (online != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.32f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(if (online) Color(0xFF30A46C) else MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    state: AppState,
    room: Room,
    onBack: () -> Unit,
    onLeave: () -> Unit,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val messages = state.messages[room.id].orEmpty()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    val title = when (room.type) {
        "group" -> "# ${room.name}"
        "dm" -> state.peers.find { it.id == room.peerId }?.name ?: room.name
        else -> room.name
    }
    val subtitle = when (room.type) {
        "lobby" -> {
            val n = state.peers.size
            if (n == 0) "Looking for devices…" else "Everyone on this network · $n nearby"
        }
        "dm" -> {
            val peer = state.peers.find { it.id == room.peerId }
            if (peer != null) "Online · ${peer.device.ifBlank { peer.address }}" else "Offline"
        }
        else -> {
            val online = state.peers.count { p -> p.rooms.any { it.id == room.id } }
            "${online + 1} online"
        }
    }

    LaunchedEffect(messages.size, room.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (room.type != "lobby") {
                        TextButton(onClick = onLeave) {
                            Text(if (room.type == "dm") "Delete" else "Leave")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Default.Forum, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                when (room.type) {
                                    "lobby" -> "Say hi to everyone nearby"
                                    "dm" -> "Start of your chat with $title"
                                    else -> "Welcome to #${room.name}"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        mine = message.from.id == state.me.id,
                        onOpenFile = onOpenFile,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (draft.isNotBlank()) {
                            onSend(draft)
                            draft = ""
                        }
                    }),
                )
                IconButton(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onSend(draft)
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, mine: Boolean, onOpenFile: (String) -> Unit) {
    val time = remember(message.ts) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.ts))
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (!mine) {
            Avatar(message.from.name, message.from.id, 32.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (!mine) {
                Text(
                    "${message.from.name} · $time",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            if (message.file != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message.file.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(message.file.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (message.file.path != null) {
                            TextButton(onClick = { onOpenFile(message.file.path) }) { Text("Open") }
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    border = if (mine) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text(
                        message.text,
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (mine && message.status != null) {
                Text(
                    statusText(message),
                    fontSize = 11.sp,
                    color = if (message.status == "failed") Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                )
            }
        }
    }
}

private fun statusText(message: ChatMessage): String = when (message.status) {
    "sending" -> "Sending…"
    "sent" -> if (message.recipients > 1) "Delivered to ${message.recipients}" else "Delivered"
    "partial" -> "Delivered to ${message.delivered} of ${message.recipients}"
    "failed" -> "Not delivered"
    "nobody" -> "No one online to receive this"
    else -> ""
}

private fun hslColor(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r1, g1, b1) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r1 + m, g1 + m, b1 + m)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.1f GB", mb / 1024.0)
}
