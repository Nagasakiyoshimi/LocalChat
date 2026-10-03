package com.localchat.app

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object DebugLog {
    private const val MAX_LINES = 400
    private const val MAX_BYTES = 200_000
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()
    private var file: File? = null

    fun attach(logFile: File) {
        file = logFile
        val existing = runCatching { logFile.readText() }.getOrDefault("")
        if (existing.isNotBlank()) {
            synchronized(lines) {
                existing.lines().filter { it.isNotBlank() }.takeLast(MAX_LINES).forEach { lines.addLast(it) }
                _text.value = lines.joinToString("\n")
            }
        }
    }

    fun log(message: String, error: Throwable? = null) {
        val entry = buildString {
            append(stamp.format(Date()))
            append(' ')
            append(message)
            if (error != null) {
                append('\n')
                append(stack(error).prependIndent("    "))
            }
        }
        synchronized(lines) {
            lines.addLast(entry)
            while (lines.size > MAX_LINES) lines.removeFirst()
            _text.value = lines.joinToString("\n")
            val target = file
            if (target != null) {
                runCatching {
                    target.appendText(entry + "\n")
                    if (target.length() > MAX_BYTES) {
                        val kept = target.readText().takeLast(MAX_BYTES / 2)
                        target.writeText(kept)
                    }
                }
            }
        }
        if (error != null) Log.e(TAG, message, error) else Log.i(TAG, message)
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            _text.value = ""
            runCatching { file?.writeText("") }
        }
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    private fun stack(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString().trim()
    }

    private const val TAG = "LocalChat"
}
