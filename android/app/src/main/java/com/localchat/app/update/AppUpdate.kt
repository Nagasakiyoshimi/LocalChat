package com.localchat.app.update

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdate(val version: String, val name: String, val url: String)

private const val RELEASES = "https://api.github.com/repos/Nagasakiyoshimi/LocalChat/releases/latest"

fun isNewerVersion(latest: String, current: String): Boolean {
    fun parse(value: String) = value.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    val next = parse(latest)
    val now = parse(current)
    val length = maxOf(next.size, now.size)
    for (i in 0 until length) {
        val diff = (next.getOrElse(i) { 0 }) - (now.getOrElse(i) { 0 })
        if (diff != 0) return diff > 0
    }
    return false
}

fun findUpdate(currentVersion: String, client: OkHttpClient = defaultClient()): AppUpdate? {
    val request = Request.Builder()
        .url(RELEASES)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "LocalChat")
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val release = JSONObject(response.body?.string().orEmpty())
        val version = release.optString("tag_name").removePrefix("v")
        if (version.isBlank() || !isNewerVersion(version, currentVersion)) return null
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.endsWith(".apk")) {
                return AppUpdate(version, name, asset.getString("browser_download_url"))
            }
        }
        return null
    }
}

fun downloadUpdate(update: AppUpdate, dest: File, client: OkHttpClient = defaultClient()): File {
    val request = Request.Builder().url(update.url).header("User-Agent", "LocalChat").build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("Download failed (${response.code})")
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out -> response.body?.byteStream()?.copyTo(out) }
    }
    return dest
}

private fun defaultClient() = OkHttpClient.Builder()
    .callTimeout(20, TimeUnit.SECONDS)
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .followRedirects(true)
    .build()
