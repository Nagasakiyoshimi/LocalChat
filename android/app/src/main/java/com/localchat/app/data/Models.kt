package com.localchat.app.data

data class Person(val id: String, val name: String)

data class Room(
    val id: String,
    val type: String, // lobby | group | dm
    val name: String,
    val peerId: String? = null,
)

data class PeerRoom(val id: String, val name: String)

data class Peer(
    val id: String,
    val name: String,
    val device: String,
    val address: String,
    val port: Int,
    val rooms: List<PeerRoom>,
    val lastSeen: Long,
)

data class FileInfo(
    val name: String,
    val size: Long,
    val path: String? = null,
)

data class ChatMessage(
    val id: String,
    val roomId: String,
    val from: Person,
    val ts: Long,
    val text: String = "",
    val incoming: Boolean = false,
    val status: String? = null,
    val recipients: Int = 0,
    val delivered: Int = 0,
    val file: FileInfo? = null,
)

data class DiscoverRoom(val id: String, val name: String, val members: Int)

data class Me(val id: String, val name: String)

data class AppState(
    val me: Me,
    val rooms: List<Room>,
    val messages: Map<String, List<ChatMessage>>,
    val peers: List<Peer>,
    val discover: List<DiscoverRoom>,
)
