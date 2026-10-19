package com.localchat.app.network

import android.content.Context
import com.localchat.app.DebugLog
import com.localchat.app.data.AppState
import com.localchat.app.data.ChatMessage
import com.localchat.app.data.DiscoverRoom
import com.localchat.app.data.FileInfo
import com.localchat.app.data.Me
import com.localchat.app.data.Person
import com.localchat.app.data.Room
import com.localchat.app.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ChatEngine(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloadDir = File(appContext.getExternalFilesDir(null), "LocalChat").also { it.mkdirs() }
    private val store = Store(
        file = File(appContext.filesDir, "localchat.json"),
        defaultName = deviceName(),
    )

    private val relay = RelayClient(
        self = { SelfInfo(store.me.id, store.me.name, deviceName(), emptyList()) },
        onRoom = { packet -> scope.launch { ensureInviteRoom(packet) } },
        onMembers = { scope.launch { publish() } },
        onPacket = { packet -> scope.launch { onRelayPacket(packet) } },
    )
    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var network: Network? = null
    var onIncoming: ((ChatMessage, Room) -> Unit)? = null

    fun start() {
        if (network != null) return
        val net = Network(
            context = appContext,
            downloadDir = downloadDir,
            selfProvider = { selfInfo() },
            onPeersChanged = { publish() },
            onMessage = { envelope -> scope.launch { onIncomingEnvelope(envelope) } },
        )
        network = net
        Thread {
            runCatching { net.start() }
                .onFailure { DebugLog.log("network start failed", it) }
        }.start()
        relay.start()
        for (room in store.rooms) {
            val code = room.code
            if (room.via == "invite" && !code.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) {
                    val pending = room.inviteState == "pending" || room.inviteState == "failed"
                    val result = if (pending) runCatching { relay.create(room.name, code) } else runCatching { relay.join(code) }
                    result.onSuccess {
                        scope.launch { markInviteReady(room.id) }
                    }.onFailure { DebugLog.log("invite $code failed", it) }
                }
            }
        }
        publish()
    }

    fun stop() {
        relay.stop()
        network?.stop()
        network = null
        store.save()
    }

    fun setName(name: String) {
        store.me = Me(store.me.id, cleanName(name))
        changed(announce = true)
    }

    fun createRoom(name: String): Room {
        val room = Room(id = UUID.randomUUID().toString(), type = "group", name = cleanName(name))
        store.rooms.add(room)
        changed(announce = true)
        return room
    }

    fun createInvite(name: String): Room {
        val clean = cleanName(name)
        val code = makeInviteCode()
        val room = Room(
            id = "invite:$code",
            type = "group",
            name = clean,
            code = code,
            via = "invite",
            inviteState = "pending",
        )
        store.rooms.add(room)
        changed(announce = false)
        DebugLog.log("create room \"$clean\" $code shown locally")
        scope.launch(Dispatchers.IO) {
            runCatching { relay.create(clean, code) }
                .onSuccess {
                    DebugLog.log("create room $code confirmed by server")
                    withContext(Dispatchers.Main) { markInviteReady(room.id) }
                }
                .onFailure { error ->
                    DebugLog.log("create room $code failed", error)
                    withContext(Dispatchers.Main) {
                        val idx = store.rooms.indexOfFirst { it.id == room.id }
                        if (idx >= 0 && store.rooms[idx].inviteState != "ready") {
                            store.rooms[idx] = store.rooms[idx].copy(inviteState = "failed")
                            changed(announce = false)
                        }
                    }
                }
        }
        return room
    }

    suspend fun joinInvite(code: String): Room {
        val joined = withContext(Dispatchers.IO) { relay.join(code) }
        return store.rooms.find { it.id == joined.getString("roomId") } ?: ensureInviteRoom(joined)
    }

    fun joinRoom(roomId: String): Room {
        store.rooms.find { it.id == roomId }?.let { return it }
        val found = network?.peerList()?.flatMap { it.rooms }?.find { it.id == roomId }
            ?: error("Room is no longer available")
        val room = Room(id = found.id, type = "group", name = found.name)
        store.rooms.add(room)
        changed(announce = true)
        return room
    }

    fun leaveRoom(roomId: String) {
        if (roomId == Store.LOBBY.id) error("You cannot leave the Everyone room")
        val room = store.rooms.find { it.id == roomId }
        store.rooms.removeAll { it.id == roomId }
        if (room?.via == "invite" && room.code != null) relay.leave(room.code)
        if (room?.type == "dm") store.messages.remove(roomId)
        changed(announce = true)
    }

    fun deleteRoom(roomId: String) {
        if (roomId == Store.LOBBY.id) error("You cannot delete the Everyone room")
        val room = store.rooms.find { it.id == roomId }
        store.rooms.removeAll { it.id == roomId }
        store.messages.remove(roomId)
        if (room?.via == "invite" && room.code != null) relay.leave(room.code)
        changed(announce = true)
    }

    fun openDirect(peerId: String): Room {
        val id = dmRoomId(store.me.id, peerId)
        store.rooms.find { it.id == id }?.let { return it }
        val peer = network?.peerList()?.find { it.id == peerId } ?: error("That device is no longer online")
        val room = Room(id = id, type = "dm", name = peer.name, peerId = peerId)
        store.rooms.add(room)
        changed(announce = false)
        return room
    }

    suspend fun sendText(roomId: String, text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        if (value.length > MAX_TEXT_LENGTH) error("Messages are limited to $MAX_TEXT_LENGTH characters")
        val room = requireRoom(roomId)
        val record = newRecord(room, text = value)
        deliver(record, room) { peer, envelope ->
            network?.sendMessage(peer, envelope) ?: error("Network not started")
        }
    }

    suspend fun sendFile(roomId: String, file: File) {
        if (!file.isFile) return
        val room = requireRoom(roomId)
        val record = newRecord(
            room,
            file = FileInfo(name = file.name, size = file.length(), path = file.absolutePath),
        )
        deliver(record, room) { peer, envelope ->
            network?.sendFile(peer, envelope, file, file.length()) ?: error("Network not started")
        }
    }

    private suspend fun deliver(
        record: ChatMessage,
        room: Room,
        send: (com.localchat.app.data.Peer, JSONObject) -> Unit,
    ) {
        append(record)
        publish()
        if (room.via == "invite") {
            val code = room.code ?: error("This room has no invite code")
            val envelope = JSONObject()
                .put("id", record.id)
                .put("from", JSONObject().put("id", record.from.id).put("name", record.from.name))
                .put("ts", record.ts)
                .put("text", record.text)
                .put("room", JSONObject().put("id", room.id).put("type", "group").put("name", room.name))
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val path = record.file?.path
                    if (path != null) relay.sendFile(code, envelope, File(path))
                    else relay.sendMessage(code, envelope)
                }.onFailure { DebugLog.log("invite send failed", it) }.isSuccess
            }
            val others = ((relay.counts()[room.id] ?: 1) - 1).coerceAtLeast(0)
            updateMessage(
                record.copy(
                    status = if (ok) "sent" else "failed",
                    recipients = others,
                    delivered = if (ok) others else 0,
                ),
            )
            return
        }
        val targets = targets(room)
        val envelope = JSONObject()
            .put("id", record.id)
            .put("from", JSONObject().put("id", record.from.id).put("name", record.from.name))
            .put("ts", record.ts)
            .put("text", record.text)
            .put(
                "room",
                JSONObject()
                    .put("id", room.id)
                    .put("type", room.type)
                    .put("name", if (room.type == "group") room.name else ""),
            )
        val delivered = withContext(Dispatchers.IO) {
            targets.count { peer ->
                runCatching { send(peer, envelope) }
                    .onFailure { DebugLog.log("delivery to ${peer.name} failed", it) }
                    .isSuccess
            }
        }
        DebugLog.log("delivery ${record.text.take(40).ifBlank { record.file?.name ?: record.id }} -> $delivered/${targets.size}")
        updateMessage(
            record.copy(
                recipients = targets.size,
                delivered = delivered,
                status = when {
                    targets.isEmpty() -> "nobody"
                    delivered == targets.size -> "sent"
                    delivered == 0 -> "failed"
                    else -> "partial"
                },
            ),
        )
    }

    private fun targets(room: Room): List<com.localchat.app.data.Peer> {
        val peers = network?.peerList().orEmpty()
        return when (room.type) {
            "lobby" -> peers
            "dm" -> peers.filter { it.id == room.peerId }
            else -> peers.filter { p -> p.rooms.any { it.id == room.id } }
        }
    }

    private fun onIncomingEnvelope(msg: IncomingEnvelope) {
        val room = roomForIncoming(msg) ?: return
        val list = store.messages.getOrPut(room.id) { mutableListOf() }
        if (list.any { it.id == msg.id }) return
        val record = ChatMessage(
            id = msg.id,
            roomId = room.id,
            from = msg.from,
            ts = msg.ts,
            text = msg.text,
            incoming = true,
            file = if (msg.fileName != null) {
                FileInfo(msg.fileName, msg.fileSize ?: 0L, msg.filePath)
            } else null,
        )
        append(record)
        publish()
        onIncoming?.invoke(record, room)
    }

    private fun roomForIncoming(msg: IncomingEnvelope): Room? {
        if (msg.roomType == "lobby") return store.rooms.find { it.id == Store.LOBBY.id }
        if (msg.roomType == "dm") {
            val id = dmRoomId(store.me.id, msg.from.id)
            if (msg.roomId != id) return null
            val existing = store.rooms.find { it.id == id }
            if (existing != null) {
                if (existing.name != msg.from.name) {
                    val idx = store.rooms.indexOfFirst { it.id == id }
                    store.rooms[idx] = existing.copy(name = msg.from.name)
                    store.save()
                }
                return store.rooms.find { it.id == id }
            }
            val room = Room(id = id, type = "dm", name = msg.from.name, peerId = msg.from.id)
            store.rooms.add(room)
            store.save()
            return room
        }
        return store.rooms.find { it.id == msg.roomId }
    }

    private fun selfInfo() = SelfInfo(
        id = store.me.id,
        name = store.me.name,
        device = deviceName(),
        rooms = store.rooms.filter { it.type == "group" && it.via != "invite" }.take(50).map {
            com.localchat.app.data.PeerRoom(it.id, it.name)
        },
    )

    private fun newRecord(room: Room, text: String = "", file: FileInfo? = null) = ChatMessage(
        id = UUID.randomUUID().toString(),
        roomId = room.id,
        from = Person(store.me.id, store.me.name),
        ts = System.currentTimeMillis(),
        text = text,
        status = "sending",
        file = file,
    )

    private fun append(record: ChatMessage) {
        val list = store.messages.getOrPut(record.roomId) { mutableListOf() }
        list.add(record)
        if (list.size > 2000) {
            val drop = list.size - 2000
            repeat(drop) { list.removeAt(0) }
        }
        store.save()
    }

    private fun updateMessage(record: ChatMessage) {
        val list = store.messages[record.roomId] ?: return
        val idx = list.indexOfFirst { it.id == record.id }
        if (idx >= 0) list[idx] = record
        store.save()
        publish()
    }

    private fun requireRoom(id: String) = store.rooms.find { it.id == id } ?: error("Room not found")

    private fun changed(announce: Boolean) {
        store.save()
        if (announce) network?.announce()
        publish()
    }

    private fun publish() {
        _state.update { buildState() }
    }

    private fun buildState(): AppState {
        val peers = network?.peerList().orEmpty()
        val joined = store.rooms.map { it.id }.toSet()
        val discover = linkedMapOf<String, DiscoverRoom>()
        for (peer in peers) {
            for (room in peer.rooms) {
                if (room.id in joined) continue
                val prev = discover[room.id]
                discover[room.id] = DiscoverRoom(room.id, room.name, (prev?.members ?: 0) + 1)
            }
        }
        return AppState(
            me = store.me,
            rooms = store.rooms.toList(),
            messages = store.messages.mapValues { it.value.toList() },
            peers = peers,
            discover = discover.values.toList(),
            inviteMembers = relay.counts(),
        )
    }

    private fun markInviteReady(roomId: String) {
        val idx = store.rooms.indexOfFirst { it.id == roomId }
        if (idx < 0 || store.rooms[idx].inviteState == "ready") return
        store.rooms[idx] = store.rooms[idx].copy(inviteState = "ready")
        changed(announce = false)
    }

    private fun ensureInviteRoom(packet: JSONObject): Room {
        val id = packet.getString("roomId")
        store.rooms.find { it.id == id }?.let { existing ->
            if (existing.inviteState == "pending" || existing.inviteState == "failed") {
                val ready = existing.copy(
                    inviteState = "ready",
                    name = packet.optString("name").ifBlank { existing.name },
                )
                store.rooms[store.rooms.indexOf(existing)] = ready
                changed(announce = false)
                return ready
            }
            return existing
        }
        val room = Room(
            id = id,
            type = "group",
            name = packet.optString("name").ifBlank { "Room" },
            code = packet.optString("code"),
            via = "invite",
            inviteState = "ready",
        )
        store.rooms.add(room)
        changed(announce = false)
        return room
    }

    private fun onRelayPacket(packet: JSONObject) {
        val envelope = packet.optJSONObject("envelope") ?: return
        val from = envelope.optJSONObject("from") ?: return
        val room = envelope.optJSONObject("room") ?: return
        val fileObj = packet.optJSONObject("file")
        var fileName: String? = null
        var fileSize = 0L
        var filePath: String? = null
        if (fileObj != null) {
            fileName = fileObj.optString("name").ifBlank { "file" }
            fileSize = fileObj.optLong("size")
            val data = fileObj.optString("data")
            if (data.isNotBlank()) {
                val saved = saveRelayFile(fileName, data)
                fileName = saved.name
                fileSize = saved.length()
                filePath = saved.absolutePath
            }
        }
        val history = packet.optBoolean("history")
        val existing = if (history) store.rooms.find { it.id == room.optString("id") } else roomForIncoming(
            IncomingEnvelope(
                id = envelope.optString("id"),
                from = Person(from.optString("id"), from.optString("name")),
                roomId = room.optString("id"),
                roomType = room.optString("type"),
                roomName = room.optString("name"),
                ts = envelope.optLong("ts"),
                text = envelope.optString("text"),
            ),
        )
        val target = existing ?: return
        val id = envelope.optString("id")
        if (id.isBlank() || store.messages[target.id]?.any { it.id == id } == true) return
        val mine = from.optString("id") == store.me.id
        val record = ChatMessage(
            id = id,
            roomId = target.id,
            from = Person(from.optString("id"), from.optString("name")),
            ts = envelope.optLong("ts", System.currentTimeMillis()),
            text = envelope.optString("text"),
            incoming = !mine,
            status = if (mine) "sent" else null,
            file = fileName?.let { FileInfo(it, fileSize, filePath) },
        )
        append(record)
        publish()
        if (!mine && !history) onIncoming?.invoke(record, target)
    }

    private fun saveRelayFile(name: String, base64: String): File {
        downloadDir.mkdirs()
        val safe = safeFileName(name)
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val base = safe.substringBeforeLast('.', safe)
        val ext = if (safe.contains('.')) ".${safe.substringAfterLast('.')}" else ""
        var i = 0
        while (true) {
            val out = File(downloadDir, if (i == 0) safe else "$base ($i)$ext")
            if (!out.exists()) {
                out.writeBytes(bytes)
                return out
            }
            i++
        }
    }

    companion object {
        private fun cleanName(name: String): String {
            val value = name.trim().take(40)
            if (value.isEmpty()) error("Name cannot be empty")
            return value
        }

        private fun dmRoomId(a: String, b: String) = "dm:" + listOf(a, b).sorted().joinToString(":")
    }
}
