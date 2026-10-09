package com.localchat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.localchat.app.update.AppUpdate
import com.localchat.app.update.downloadUpdate
import com.localchat.app.update.findUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val app get() = application as LocalChatApplication
    private var focused = true
    private var currentRoomId = "lobby"
    private var pendingPickRoom: String? = null
    private var pendingUpdate: AppUpdate? = null

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
                val file = withContext(Dispatchers.IO) { copyUriToCache(uri) } ?: run {
                    DebugLog.log("could not read picked file $uri")
                    return@forEach
                }
                runCatching { app.chat.sendFile(roomId, file) }
                    .onFailure { DebugLog.log("send file failed", it) }
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

        val updateVersion = androidx.compose.runtime.mutableStateOf<String?>(null)
        val updateBusy = androidx.compose.runtime.mutableStateOf(false)
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) {
                runCatching { findUpdate(BuildConfig.VERSION_NAME) }.getOrNull()
            }
            pendingUpdate = update
            updateVersion.value = update?.version
        }

        setContent {
            val version = updateVersion.value
            val busy = updateBusy.value
            LocalChatTheme {
                LocalChatApp(
                    chat = app.chat,
                    onRoomVisible = { currentRoomId = it },
                    onPickFiles = { roomId ->
                        pendingPickRoom = roomId
                        filePicker.launch("*/*")
                    },
                    onOpenFile = { path -> openFile(path) },
                    onShareLog = { text -> shareLog(text) },
                    updateVersion = version,
                    updateBusy = busy,
                    onInstallUpdate = {
                        val update = pendingUpdate ?: return@LocalChatApp
                        updateBusy.value = true
                        lifecycleScope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    downloadUpdate(update, File(cacheDir, update.name))
                                }
                                installApk(file)
                            } catch (err: Exception) {
                                DebugLog.log("update failed", err)
                            } finally {
                                updateBusy.value = false
                            }
                        }
                    },
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

    private fun shareLog(text: String) {
        val file = File(cacheDir, "localchat-log.txt")
        file.writeText(text.ifBlank { "(empty log)" })
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LocalChat debug log")
            putExtra(Intent.EXTRA_TEXT, text.take(80_000))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Share debug log")) }
            .onFailure { DebugLog.log("share log failed", it) }
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
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
