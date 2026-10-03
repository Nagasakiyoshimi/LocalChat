package com.localchat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.localchat.app.ui.LocalChatApp
import com.localchat.app.ui.theme.LocalChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val app get() = application as LocalChatApplication
    private var focused = true
    private var currentRoomId = "lobby"
    private var pendingPickRoom: String? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op */ }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        val roomId = pendingPickRoom ?: return@registerForActivityResult
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            uris.forEach { uri ->
                val file = withContext(Dispatchers.IO) { copyUriToCache(uri) } ?: return@forEach
                runCatching { app.chat.sendFile(roomId, file) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        app.chat.onIncoming = { message, room ->
            if (!focused || room.id != currentRoomId) notifyMessage(message.from.name, message.text, message.file?.name, room.id)
        }
        app.chat.start()

        setContent {
            LocalChatTheme {
                LocalChatApp(
                    chat = app.chat,
                    onRoomVisible = { currentRoomId = it },
                    onPickFiles = { roomId ->
                        pendingPickRoom = roomId
                        filePicker.launch("*/*")
                    },
                    onOpenFile = { path -> openFile(path) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        focused = true
    }

    override fun onPause() {
        focused = false
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) app.chat.stop()
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notifyMessage(from: String, text: String, fileName: String?, roomId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) return
        val body = fileName?.let { "Sent a file: $it" } ?: text.take(200)
        val notification = NotificationCompat.Builder(this, LocalChatApplication.NOTIFY_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(from)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(roomId.hashCode(), notification)
    }

    private fun copyUriToCache(uri: Uri): File? {
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        } ?: "file"
        val safe = name.replace(Regex("[<>:\"/\\\\|?*]"), "_").take(200)
        val out = File(cacheDir, "${System.currentTimeMillis()}_$safe")
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun openFile(path: String) {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }
}
