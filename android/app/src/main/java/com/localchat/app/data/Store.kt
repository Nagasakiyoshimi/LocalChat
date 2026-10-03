package com.localchat.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Store(private val file: File, defaultName: String) {
    var me: Me
    var rooms: MutableList<Room>
    var messages: MutableMap<String, MutableList<ChatMessage>>

    init {
        val loaded = runCatching { JSONObject(file.readText()) }.getOrNull()
        me = if (loaded?.has("me") == true) {
            val m = loaded.getJSONObject("me")
            Me(m.getString("id"), m.getString("name"))
        } else {
            Me(UUID.randomUUID().toString(), defaultName)
        }

        rooms = mutableListOf()
        if (loaded?.has("rooms") == true) {
            val arr = loaded.getJSONArray("rooms")
            for (i in 0 until arr.length()) {
                rooms.add(parseRoom(arr.getJSONObject(i)))
            }
        }
        if (rooms.none { it.id == LOBBY.id }) rooms.add(0, LOBBY)

        messages = mutableMapOf()
        if (loaded?.has("messages") == true) {
            val obj = loaded.getJSONObject("messages")
            for (key in obj.keys()) {
                val arr = obj.getJSONArray(key)
                val list = mutableListOf<ChatMessage>()
                for (i in 0 until arr.length()) {
                    val msg = parseMessage(arr.getJSONObject(i))
                    list.add(if (msg.status == "sending") msg.copy(status = "failed") else msg)
                }
                messages[key] = list
            }
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        val roomsArr = JSONArray()
        rooms.forEach { roomsArr.put(roomJson(it)) }
        val messagesObj = JSONObject()
        messages.forEach { (roomId, list) ->
            val arr = JSONArray()
            list.forEach { arr.put(messageJson(it)) }
            messagesObj.put(roomId, arr)
        }
        val root = JSONObject()
            .put("me", JSONObject().put("id", me.id).put("name", me.name))
            .put("rooms", roomsArr)
            .put("messages", messagesObj)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    companion object {
        val LOBBY = Room(id = "lobby", type = "lobby", name = "Everyone")

        private fun parseRoom(o: JSONObject) = Room(
            id = o.getString("id"),
            type = o.getString("type"),
            name = o.getString("name"),
            peerId = if (o.has("peerId") && !o.isNull("peerId")) o.getString("peerId") else null,
        )

        private fun roomJson(r: Room) = JSONObject()
            .put("id", r.id)
            .put("type", r.type)
            .put("name", r.name)
            .put("peerId", r.peerId)

        private fun parseMessage(o: JSONObject): ChatMessage {
            val from = o.getJSONObject("from")
            val file = if (o.has("file") && !o.isNull("file")) {
                val f = o.getJSONObject("file")
                FileInfo(
                    name = f.getString("name"),
                    size = f.getLong("size"),
                    path = if (f.has("path") && !f.isNull("path")) f.getString("path") else null,
                )
            } else null
            return ChatMessage(
                id = o.getString("id"),
                roomId = o.getString("roomId"),
                from = Person(from.getString("id"), from.getString("name")),
                ts = o.getLong("ts"),
                text = o.optString("text", ""),
                incoming = o.optBoolean("incoming", false),
                status = if (o.has("status") && !o.isNull("status")) o.getString("status") else null,
                recipients = o.optInt("recipients", 0),
                delivered = o.optInt("delivered", 0),
                file = file,
            )
        }

        private fun messageJson(m: ChatMessage): JSONObject {
            val o = JSONObject()
                .put("id", m.id)
                .put("roomId", m.roomId)
                .put("from", JSONObject().put("id", m.from.id).put("name", m.from.name))
                .put("ts", m.ts)
                .put("text", m.text)
                .put("incoming", m.incoming)
                .put("status", m.status)
                .put("recipients", m.recipients)
                .put("delivered", m.delivered)
            if (m.file != null) {
                o.put(
                    "file",
                    JSONObject()
                        .put("name", m.file.name)
                        .put("size", m.file.size)
                        .put("path", m.file.path),
                )
            }
            return o
        }
    }
}
