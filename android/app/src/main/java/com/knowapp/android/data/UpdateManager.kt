package com.knowapp.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.knowapp.android.BuildConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    object Available : UpdateState()
    object Unknown : UpdateState()
    data class Downloading(val percent: Int) : UpdateState()
    object NeedPermission : UpdateState()
    object Installing : UpdateState()
    data class Error(val message: String) : UpdateState()
}

// Checks the rolling GitHub release, downloads the APK and hands it to the system installer
class UpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    init {
        File(appContext.cacheDir, "updates").deleteRecursively()
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "know-android-updater")
        return conn
    }

    // A small file on the release has no request limit, unlike GitHub's API, which refuses shared mobile addresses
    private fun latestFromVersionFile(): String? = try {
        val conn = open(VERSION_URL)
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { Regex("[0-9a-f]{40}").matches(it) } else null
    } catch (e: Exception) { null }

    private fun latestFromApi(): String? = try {
        val conn = open(RELEASE_API)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val text = JsonParser.parseString(body).asJsonObject.get("body")?.asString ?: ""
            Regex("commit ([0-9a-f]{40})").find(text)?.groupValues?.get(1)
        } else null
    } catch (e: Exception) { null }

    suspend fun check() = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        val latest = latestFromVersionFile() ?: latestFromApi()
        _state.value = when {
            latest == null -> UpdateState.Unknown
            latest == BuildConfig.GIT_SHA -> UpdateState.UpToDate
            else -> UpdateState.Available
        }
    }

    suspend fun install() = withContext(Dispatchers.IO) {
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedPermission
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            return@withContext
        }
        try {
            val apk = download()
            _state.value = UpdateState.Installing
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Update download failed.")
        }
    }

    private fun download(): File {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "know-update.apk")
        val conn = open(APK_URL)
        if (conn.responseCode != 200) throw RuntimeException("Download failed (${conn.responseCode}).")
        val total = conn.contentLengthLong
        var done = 0L
        var lastPercent = -1
        conn.inputStream.use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (total > 0) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != lastPercent && percent % 5 == 0) {
                            lastPercent = percent
                            _state.value = UpdateState.Downloading(percent)
                        }
                    }
                }
            }
        }
        if (target.length() < 100_000L || (total > 0 && target.length() != total)) {
            target.delete()
            throw RuntimeException("Downloaded file was incomplete. Try again.")
        }
        return target
    }

    private companion object {
        const val RELEASE_API = "https://api.github.com/repos/bashevent01-commits/teach-app/releases/tags/android-latest"
        const val VERSION_URL = "https://github.com/bashevent01-commits/teach-app/releases/download/android-latest/version.txt"
        const val APK_URL = "https://github.com/bashevent01-commits/teach-app/releases/download/android-latest/app-debug.apk"
    }
}
