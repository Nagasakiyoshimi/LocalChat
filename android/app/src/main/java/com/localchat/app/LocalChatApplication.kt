package com.localchat.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.localchat.app.network.ChatEngine
import java.io.File

class LocalChatApplication : Application() {
    lateinit var chat: ChatEngine
        private set

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFY_CHANNEL,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        DebugLog.attach(File(filesDir, "debug.log"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DebugLog.log("crash on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        DebugLog.log("LocalChat ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android ${Build.VERSION.RELEASE} ${Build.MANUFACTURER} ${Build.MODEL}")
        chat = ChatEngine(this)
    }

    companion object {
        const val NOTIFY_CHANNEL = "messages"
    }
}
