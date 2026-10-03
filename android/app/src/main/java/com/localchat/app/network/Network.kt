package com.localchat.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.localchat.app.DebugLog
import com.localchat.app.data.Peer
import com.localchat.app.data.PeerRoom
import com.localchat.app.data.Person
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val PROTOCOL = "localchat/1"
const val MULTICAST_GROUP = "224.0.0.168"
const val DISCOVERY_PORT = 53318
const val MAX_TEXT_LENGTH = 10_000
private const val MAX_JSON_BYTES = 256 * 1024
private const val ANNOUNCE_INTERVAL_MS = 3_000L
private const val SWEEP_INTERVAL_MS = 2_000L
private const val PEER_TTL_MS = 10_000L
private const val TAG = "LocalChatNet"

data class SelfInfo(val id: String, val name: String, val device: String, val rooms: List<PeerRoom>)

data class IncomingEnvelope(
    val id: String,
    val from: Person,
    val roomId: String,
    val roomType: String,
    val roomName: String,
    val ts: Long,
    val text: String,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val filePath: String? = null,
)

class Network(
    private val context: Context,
    private val downloadDir: File,
    private val selfProvider: () -> SelfInfo,
    private val onPeersChanged: () -> Unit,
    private val onMessage: (IncomingEnvelope) -> Unit,
) {
    private val peers = ConcurrentHashMap<String, Peer>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    private var socket: MulticastSocket? = null
    private var serverSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val joined = Collections.synchronizedSet(mutableSetOf<String>())
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val receiveExecutor = Executors.newSingleThreadExecutor()
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)

    @Volatile var httpPort: Int = 0
        private set

    fun peerList(): List<Peer> = peers.values.toList()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            downloadDir.mkdirs()
            acquireMulticastLock()

            val ss = ServerSocket(0)
            serverSocket = ss
            httpPort = ss.localPort
            acceptExecutor.execute { acceptLoop(ss) }

            val ms = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                broadcast = true
                timeToLive = 1
                loopbackMode = false
            }
            socket = ms
            joinMulticast()
            receiveExecutor.execute { receiveLoop(ms) }
            scheduler.scheduleAtFixedRate({
                runCatching { announce() }.onFailure { DebugLog.log("announce failed", it) }
            }, 0, ANNOUNCE_INTERVAL_MS, TimeUnit.MILLISECONDS)
            scheduler.scheduleAtFixedRate({ sweep() }, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS)
            announce()
            DebugLog.log("network up  http=:$httpPort  udp=$DISCOVERY_PORT  multicastLock=${multicastLock?.isHeld == true}")
            DebugLog.log("interfaces ${describeInterfaces()}")
        } catch (e: Exception) {
            running.set(false)
            DebugLog.log("network start failed", e)
            throw e
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching {
            sendPacket(JSONObject().put("protocol", PROTOCOL).put("type", "bye").put("id", selfProvider().id))
        }
        Thread.sleep(150)
        scheduler.shutdownNow()
        receiveExecutor.shutdownNow()
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
        runCatching { socket?.close() }
        socket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        multicastLock?.release()
        multicastLock = null
        peers.clear()
    }

    fun announce(target: String? = null) {
        val self = selfProvider()
        val rooms = JSONArray()
        self.rooms.forEach { rooms.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val packet = JSONObject()
            .put("protocol", PROTOCOL)
            .put("type", "announce")
            .put("id", self.id)
            .put("name", self.name)
            .put("device", self.device)
            .put("port", httpPort)
            .put("rooms", rooms)
        sendPacket(packet, target)
    }

    fun sendMessage(peer: Peer, envelope: JSONObject) {
        val url = "http://${peer.address}:${peer.port}/api/message"
        try {
            val body = envelope.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            http.newCall(req).execute().use { res ->
                val response = res.body?.string()
                if (!res.isSuccessful) error("Peer responded with ${res.code} ${response.orEmpty()}")
            }
            DebugLog.log("sent message to ${peer.name} $url")
        } catch (e: Exception) {
            DebugLog.log("send message failed $url", e)
            throw e
        }
    }

    fun sendFile(peer: Peer, envelope: JSONObject, file: File, size: Long) {
        val meta = JSONObject(envelope.toString())
            .put("file", JSONObject().put("name", file.name).put("size", size))
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                file.source().use { sink.writeAll(it) }
            }
        }
        val req = Request.Builder()
            .url("http://${peer.address}:${peer.port}/api/file")
            .header("x-localchat-meta", java.net.URLEncoder.encode(meta.toString(), Charsets.UTF_8.name()))
            .post(body)
            .build()
        try {
            http.newCall(req).execute().use { res ->
                val response = res.body?.string()
                if (!res.isSuccessful) error("Peer responded with ${res.code} ${response.orEmpty()}")
            }
            DebugLog.log("sent file ${file.name} ($size bytes) to ${peer.name} ${peer.address}:${peer.port}")
        } catch (e: Exception) {
            DebugLog.log("send file failed ${peer.address}:${peer.port} ${file.name}", e)
            throw e
        }
    }

    private fun String.toRequestBody(mediaType: okhttp3.MediaType) =
        RequestBody.create(mediaType, this)

    private fun acquireMulticastLock() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("localchat").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun localIpv4Interfaces(): List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { ni ->
            runCatching {
                ni.isUp && !ni.isLoopback && ni.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
            }.getOrDefault(false)
        }

    private fun joinMulticast() {
        val group = InetAddress.getByName(MULTICAST_GROUP)
        val ms = socket ?: return
        for (ni in localIpv4Interfaces()) {
            val key = ni.name
            if (!joined.add(key)) continue
            runCatching {
                ms.joinGroup(InetSocketAddress(group, DISCOVERY_PORT), ni)
            }.onSuccess {
                DebugLog.log("joined multicast on $key")
            }.onFailure {
                joined.remove(key)
                DebugLog.log("multicast join failed on $key", it)
            }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        out.add(InetAddress.getByName(MULTICAST_GROUP))
        for (ni in localIpv4Interfaces()) {
            for (addr in ni.interfaceAddresses) {
                val inet = addr.address
                if (inet !is Inet4Address || inet.isLoopbackAddress) continue
                val prefix = addr.networkPrefixLength.toInt()
                if (prefix !in 0..32) continue
                val ip = inet.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
                val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
                val broadcast = ip or mask.inv()
                val bytes = byteArrayOf(
                    (broadcast ushr 24).toByte(),
                    (broadcast ushr 16).toByte(),
                    (broadcast ushr 8).toByte(),
                    broadcast.toByte(),
                )
                out.add(InetAddress.getByAddress(bytes))
            }
        }
        return out.distinctBy { it.hostAddress }
    }

    private fun sendPacket(json: JSONObject, target: String? = null) {
        val ms = socket ?: return
        joinMulticast()
        val data = json.toString().toByteArray(Charsets.UTF_8)
        val targets = if (target != null) {
            listOf(InetAddress.getByName(target))
        } else {
            broadcastAddresses()
        }
        for (addr in targets) {
            runCatching { ms.send(DatagramPacket(data, data.size, addr, DISCOVERY_PORT)) }
                .onFailure { DebugLog.log("udp send to ${addr.hostAddress} failed", it) }
        }
        if (target == null && !loggedBroadcast) {
            loggedBroadcast = true
            DebugLog.log("announcing to ${targets.joinToString { it.hostAddress ?: "?" }}")
        }
    }

    private var loggedBroadcast = false

    private fun describeInterfaces(): String =
        localIpv4Interfaces().joinToString("; ") { ni ->
            val ips = ni.interfaceAddresses
                .mapNotNull { it.address }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .joinToString(",") { it.hostAddress ?: "?" }
            "${ni.name}=$ips"
        }.ifBlank { "(none)" }

    private fun receiveLoop(ms: MulticastSocket) {
        val buf = ByteArray(64 * 1024)
        while (running.get() && !ms.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                ms.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                onPacket(text, packet.address.hostAddress ?: continue)
            } catch (_: SocketException) {
                if (!running.get()) break
            } catch (e: Exception) {
                DebugLog.log("udp receive failed", e)
            }
        }
    }

    private fun onPacket(text: String, address: String) {
        val packet = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (packet.optString("protocol") != PROTOCOL) return
        val id = packet.optString("id")
        if (id.isBlank() || id.length > 100 || id == selfProvider().id) return

        if (packet.optString("type") == "bye") {
            val removed = peers.remove(id)
            if (removed != null) {
                DebugLog.log("peer left ${removed.name} ($id)")
                onPeersChanged()
            }
            return
        }
        if (packet.optString("type") != "announce") return
        val port = packet.optInt("port", -1)
        if (port !in 1..65535) return

        val roomsArr = packet.optJSONArray("rooms") ?: JSONArray()
        val rooms = buildList {
            for (i in 0 until minOf(roomsArr.length(), 100)) {
                val r = roomsArr.optJSONObject(i) ?: continue
                val rid = r.optString("id")
                if (rid.isNotBlank() && rid.length <= 200) {
                    add(PeerRoom(rid, r.optString("name").take(100)))
                }
            }
        }
        val name = packet.optString("name").ifBlank { "Unknown" }.take(100)
        val device = packet.optString("device").take(100)
        val prev = peers[id]
        peers[id] = Peer(id, name, device, address, port, rooms, System.currentTimeMillis())
        if (prev == null) {
            DebugLog.log("peer found $name at $address:$port device=$device rooms=${rooms.size}")
            announce(address)
        }
        val changed = prev == null ||
            prev.name != name ||
            prev.address != address ||
            prev.port != port ||
            prev.rooms != rooms
        if (changed) onPeersChanged()
    }

    private fun sweep() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        var changed = false
        peers.entries.removeIf {
            if (it.value.lastSeen < cutoff) {
                changed = true
                true
            } else false
        }
        if (changed) onPeersChanged()
    }

    private fun touch(peerId: String, address: String?) {
        val peer = peers[peerId] ?: return
        peers[peerId] = peer.copy(
            lastSeen = System.currentTimeMillis(),
            address = address?.removePrefix("::ffff:") ?: peer.address,
        )
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                clientExecutor.execute { handleClient(client) }
            } catch (_: SocketException) {
                if (!running.get()) break
            } catch (e: Exception) {
                DebugLog.log("http accept failed", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 5 * 60 * 1000
        socket.use { sock ->
            val input = BufferedInputStream(sock.getInputStream())
            val output = BufferedOutputStream(sock.getOutputStream())
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                val remote = sock.inetAddress?.hostAddress
                when {
                    method == "GET" && path == "/api/info" -> {
                        val self = selfProvider()
                        val json = JSONObject()
                            .put("protocol", PROTOCOL)
                            .put("id", self.id)
                            .put("name", self.name)
                            .put("device", self.device)
                            .put("port", httpPort)
                        writeJson(output, 200, json)
                    }
                    method == "POST" && path == "/api/message" -> {
                        val length = headers["content-length"]?.toIntOrNull() ?: error("Missing content-length")
                        if (length > MAX_JSON_BYTES) error("Payload too large")
                        val body = readExact(input, length)
                        val envelope = parseEnvelope(JSONObject(String(body, Charsets.UTF_8)))
                        touch(envelope.from.id, remote)
                        DebugLog.log("got message from ${envelope.from.name} room=${envelope.roomType}:${envelope.roomId}")
                        onMessage(envelope)
                        writeJson(output, 200, JSONObject().put("ok", true))
                    }
                    method == "POST" && path == "/api/file" -> {
                        val encoded = headers["x-localchat-meta"] ?: error("Missing metadata")
                        val meta = JSONObject(java.net.URLDecoder.decode(encoded, Charsets.UTF_8.name()))
                        val envelope = parseEnvelope(meta)
                        val fileObj = meta.getJSONObject("file")
                        val fileName = safeFileName(fileObj.getString("name"))
                        val size = fileObj.getLong("size")
                        require(size >= 0)
                        touch(envelope.from.id, remote)
                        val saved = receiveFile(input, fileName, size)
                        DebugLog.log("got file ${saved.name} ($size bytes) from ${envelope.from.name}")
                        onMessage(
                            envelope.copy(
                                text = "",
                                fileName = saved.name,
                                fileSize = size,
                                filePath = saved.absolutePath,
                            ),
                        )
                        writeJson(output, 200, JSONObject().put("ok", true))
                    }
                    else -> writeJson(output, 404, JSONObject().put("error", "Not found"))
                }
            } catch (e: Exception) {
                DebugLog.log("http $method $path failed", e)
                runCatching { writeJson(output, 400, JSONObject().put("error", e.message ?: "error")) }
            }
        }
    }

    private fun parseEnvelope(body: JSONObject): IncomingEnvelope {
        val from = body.getJSONObject("from")
        val room = body.getJSONObject("room")
        val id = body.getString("id")
        val fromId = from.getString("id")
        val fromName = from.getString("name")
        val roomId = room.getString("id")
        val roomType = room.getString("type")
        require(id.isNotBlank() && id.length <= 100)
        require(fromId.isNotBlank() && fromName.isNotBlank())
        require(roomId.isNotBlank() && roomType in setOf("lobby", "group", "dm"))
        val text = body.optString("text", "")
        require(text.length <= MAX_TEXT_LENGTH)
        return IncomingEnvelope(
            id = id,
            from = Person(fromId, fromName),
            roomId = roomId,
            roomType = roomType,
            roomName = room.optString("name").take(100),
            ts = body.optLong("ts", System.currentTimeMillis()),
            text = text,
        )
    }

    private fun receiveFile(input: InputStream, name: String, expectedSize: Long): File {
        downloadDir.mkdirs()
        val out = uniqueFile(name)
        var received = 0L
        try {
            FileOutputStream(out).use { fos ->
                val buf = ByteArray(64 * 1024)
                while (received < expectedSize) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), expectedSize - received).toInt())
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    received += n
                }
            }
            if (received != expectedSize) error("File transfer incomplete")
            return out
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    private fun uniqueFile(name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""
        var i = 0
        while (true) {
            val candidate = File(downloadDir, if (i == 0) name else "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }
}

private fun readLine(input: InputStream): String? {
    val bos = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b == -1) return if (bos.size() == 0) null else bos.toString(Charsets.UTF_8.name())
        if (b == '\n'.code) break
        if (b != '\r'.code) bos.write(b)
    }
    return bos.toString(Charsets.UTF_8.name())
}

private fun readExact(input: InputStream, length: Int): ByteArray {
    val data = ByteArray(length)
    var off = 0
    while (off < length) {
        val n = input.read(data, off, length - off)
        if (n < 0) error("Unexpected end of stream")
        off += n
    }
    return data
}

private fun writeJson(output: OutputStream, status: Int, json: JSONObject) {
    val body = json.toString().toByteArray(Charsets.UTF_8)
    val reason = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "OK"
    }
    val header = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
    output.write(header.toByteArray(Charsets.US_ASCII))
    output.write(body)
    output.flush()
}

fun safeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[<>:\"|?*\\x00-\\x1f]"), "_")
        .trimStart('.')
        .take(200)
    return base.ifBlank { "file" }
}

fun deviceName(): String = Build.MODEL?.take(100) ?: "Android"
