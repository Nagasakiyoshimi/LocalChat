package com.localchat.app.network

import com.localchat.app.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val RELAY_URL_DEFAULT = "wss://magnificent-equipment-guides-connect.trycloudflare.com"
private const val RELAY_URL_SOURCE = "https://raw.githubusercontent.com/Nagasakiyoshimi/LocalChat/main/relay/url.txt"
private const val MAX_FILE_BYTES = 8 * 1024 * 1024

class RelayClient(
    private val self: () -> SelfInfo,
    private val onRoom: (JSONObject) -> Unit,
    private val onMembers: () -> Unit,
    private val onPacket: (JSONObject) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val queue = ConcurrentLinkedQueue<JSONObject>()
    private val codes = ConcurrentHashMap.newKeySet<String>()
    private val members = ConcurrentHashMap<String, Int>()
    private val closing = AtomicBoolean(false)
    private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var url = RELAY_URL_DEFAULT
    private var retryMs = 1000L

    fun counts(): Map<String, Int> = members.toMap()

    fun start() {
        closing.set(false)
        scope.launch {
            url = resolveUrl()
            DebugLog.log("invite server $url")
            connect()
        }
    }

    fun stop() {
        closing.set(true)
        ready = false
        socket?.close(1000, "bye")
        socket = null
    }

    suspend fun create(name: String): JSONObject = request(JSONObject().put("type", "create").put("name", name))

    suspend fun join(code: String): JSONObject {
        val normalized = normalizeCode(code)
        codes.add(normalized)
        return request(JSONObject().put("type", "join").put("code", normalized))
    }

    fun leave(code: String) {
        val normalized = runCatching { normalizeCode(code) }.getOrElse { return }
        codes.remove(normalized)
        if (ready) socket?.send(JSONObject().put("type", "leave").put("code", normalized).toString())
    }

    suspend fun sendMessage(code: String, envelope: JSONObject) {
        request(JSONObject().put("type", "message").put("code", code).put("envelope", envelope))
    }

    suspend fun sendFile(code: String, envelope: JSONObject, file: java.io.File) {
        if (file.length() > MAX_FILE_BYTES) error("Invite rooms can send files up to 8 MB")
        val data = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
        request(
            JSONObject()
                .put("type", "message")
                .put("code", code)
                .put("envelope", envelope)
                .put("file", JSONObject().put("name", file.name).put("size", file.length()).put("data", data)),
        )
    }

    private suspend fun request(packet: JSONObject): JSONObject {
        val req = java.util.UUID.randomUUID().toString()
        packet.put("req", req)
        val deferred = CompletableDeferred<JSONObject>()
        pending[req] = deferred
        queue.add(packet)
        flush()
        return try {
            withTimeout(20_000) { deferred.await() }
        } finally {
            pending.remove(req)
        }
    }

    private fun connect() {
        if (closing.get() || socket != null) return
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ready = false
                retryMs = 1000
                val me = self()
                webSocket.send(JSONObject().put("type", "hello").put("id", me.id).put("name", me.name).put("device", me.device).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val packet = runCatching { JSONObject(text) }.getOrNull() ?: return
                handle(packet)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.log("invite connection failed", t)
                dropSocket()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropSocket()
            }
        })
    }

    private fun dropSocket() {
        socket = null
        ready = false
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Invite server disconnected")) }
        pending.clear()
        if (!closing.get()) {
            scope.launch {
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(15_000)
                connect()
            }
        }
    }

    private fun flush() {
        if (!ready) {
            if (socket == null && !closing.get()) connect()
            return
        }
        val current = socket ?: return
        while (true) {
            val packet = queue.poll() ?: break
            current.send(packet.toString())
        }
    }

    private fun handle(packet: JSONObject) {
        when (packet.optString("type")) {
            "hello" -> {
                ready = true
                for (code in codes) {
                    queue.add(JSONObject().put("type", "join").put("code", code).put("req", "rejoin:$code"))
                }
                flush()
            }
            "members" -> {
                val roomId = packet.optString("roomId")
                val list = packet.optJSONArray("members") ?: JSONArray()
                if (roomId.isNotBlank()) members[roomId] = list.length()
                onMembers()
            }
            "message" -> onPacket(packet)
            "error" -> {
                val req = packet.optString("req")
                val error = IllegalStateException(packet.optString("error").ifBlank { "Invite failed" })
                if (req.isNotBlank()) pending.remove(req)?.completeExceptionally(error)
                else DebugLog.log(error.message ?: "invite error")
            }
            "created", "joined", "ack" -> {
                val code = packet.optString("code")
                if (code.isNotBlank()) codes.add(code)
                val req = packet.optString("req")
                if (req.isNotBlank()) pending.remove(req)?.complete(packet)
                val roomId = packet.optString("roomId")
                val list = packet.optJSONArray("members")
                if (roomId.isNotBlank() && list != null) members[roomId] = list.length()
                if (packet.optString("type") != "ack") onRoom(packet)
                onMembers()
            }
        }
    }

    private fun resolveUrl(): String {
        val remote = runCatching {
            http.newCall(Request.Builder().url(RELAY_URL_SOURCE).build()).execute().use { response ->
                response.body?.string()?.trim().orEmpty()
            }
        }.getOrNull()
        return if (remote != null && remote.startsWith("ws")) remote else url
    }
}

fun normalizeCode(input: String): String {
    val code = input.uppercase().filter { it.isLetterOrDigit() }
    if (code.length !in 4..12) error("Enter the invite code")
    return code
}
