package com.localchat.app.network

import android.content.Context
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
                .onFailure { android.util.Log.e("LocalChat", "network start failed", it) }
        }.start()
        publish()
    }

    fun stop() {
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
        store.rooms.removeAll { it.id == roomId }
        store.messages.remove(roomId)
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
            targets.count { peer -> runCatching { send(peer, envelope) }.isSuccess }
        }
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
        rooms = store.rooms.filter { it.type == "group" }.take(50).map {
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
        )
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
