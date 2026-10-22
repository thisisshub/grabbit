package dev.usenetstream.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.usenetstream.BuildConfig
import dev.usenetstream.engine.update.AppRelease
import dev.usenetstream.engine.update.Releases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val fraction: Float) : UpdateState
    /** Android needs "allow installs from Grabbit" switched on once. */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val release: AppRelease?, val message: String) : UpdateState
}

/**
 * Updates from GitHub Releases: check (at most every few hours), download the APK, hand it to PackageInstaller.
 * Android always asks the user once for a sideloaded update; on 12+ later updates can go through without a prompt
 * once Grabbit is the app that installed itself.
 */
class Updater(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.contains('/')

    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    /** On launch: quiet check (throttled); with [auto] on and Wi-Fi, also downloads and starts the install. */
    fun checkInBackground() {
        if (!enabled || System.currentTimeMillis() - prefs.getLong("checked_at", 0) < CHECK_EVERY_MS) return
        check(userAsked = false)
    }

    fun check(userAsked: Boolean = true) {
        if (!enabled || job?.isActive == true) return
        job = scope.launch {
            if (userAsked) _state.value = UpdateState.Checking
            val found = try {
                Releases.updateFor(BuildConfig.VERSION_NAME, fetch("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=10"))
            } catch (e: IOException) {
                if (userAsked) _state.value = UpdateState.Failed(null, "Couldn't reach GitHub")
                return@launch
            }
            prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply()
            if (found == null) {
                _state.value = if (userAsked) UpdateState.UpToDate else UpdateState.Idle
                return@launch
            }
            _state.value = UpdateState.Available(found)
            if (!userAsked && auto && onWifi()) download(found)
        }
    }

    fun install(release: AppRelease) {
        if (job?.isActive == true) return
        job = scope.launch { download(release) }
    }

    /** The installer gave up (cancelled, or the APK didn't match). */
    fun installFailed(message: String) {
        val r = (_state.value as? UpdateState.Installing)?.release
        _state.value = UpdateState.Failed(r, message)
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /** Back from the "allow installs" settings screen. */
    fun resume() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && canInstall()) install(s.release)
    }

    fun openInstallPermission() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(i) }
    }

    private fun canInstall() = app.packageManager.canRequestPackageInstalls()

    private suspend fun download(release: AppRelease) {
        val file = File(app.cacheDir, "update-${release.tag}.apk")
        try {
            if (!(file.exists() && release.apkBytes > 0 && file.length() == release.apkBytes)) {
                _state.value = UpdateState.Downloading(release, 0f)
                val part = File(app.cacheDir, "update.part")
                val conn = URI(release.apkUrl).toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkBytes
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastShown = 0f
                            while (scope.isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val f = if (total > 0) done.toFloat() / total else 0f
                                if (f - lastShown >= 0.01f) { lastShown = f; _state.value = UpdateState.Downloading(release, f) }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (release.apkBytes > 0 && part.length() != release.apkBytes) throw IOException("download incomplete")
                app.cacheDir.listFiles { f -> f.name.startsWith("update-") }?.forEach { it.delete() }
                part.renameTo(file)
            }
            if (!canInstall()) {
                _state.value = UpdateState.NeedsPermission(release)
                return
            }
            _state.value = UpdateState.Installing(release)
            installApk(file)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(release, e.message ?: "Update failed")
        }
    }

    private fun installApk(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            // Android 12+: no prompt when we installed the current version ourselves.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("grabbit.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }

    private fun onWifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun fetch(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Grabbit/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { String(it.readBytes()) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        @Volatile var instance: Updater? = null
        fun get(context: Context): Updater = instance ?: synchronized(this) { instance ?: Updater(context).also { instance = it } }
    }
}

/** PackageInstaller's answer: show Android's confirm screen when it wants one, report failures to the card. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            else -> Updater.get(context).installFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install cancelled")
        }
    }
}
