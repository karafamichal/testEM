package com.ksjd.testem.hub

import android.content.Context
import android.os.Build
import android.os.Process
import com.ksjd.testem.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** What a bug report attaches: this app's own logcat lines, the last crash and the watch's logs, nothing else. */
object AppLogs {
    private const val CRASH_FILE = "last_crash.txt"
    private const val WATCH_FILE = "watch_log.txt"
    /** Logs from the watch are attached to reports sent within this long after they arrived. */
    private const val WATCH_FRESH_MS = 2 * 60 * 60_000L

    /** "Report a problem" on the watch: open the phone's bug report form. */
    val reportRequested = MutableStateFlow(false)

    /** The open report was started on the watch: it's filed as the watch's (device, platform, version). */
    @Volatile var fromWatch = false

    /** The watch's version and device from its logs' first line ("Plan&Ride watch 2.13.0 (1000022) on samsung SM-L310, Wear OS…"). */
    fun watchInfo(context: Context): Pair<String, String>? {
        val first = File(context.filesDir, WATCH_FILE).takeIf { it.exists() }?.useLines { it.firstOrNull() } ?: return null
        val m = Regex("""Plan&Ride watch (\S+) \(\d+\) on (.+)""").find(first) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /**
     * Keeps a crash for the next bug report: the stack trace and the crashed session's last log lines, written
     * before the process dies (installed from PlanRideApp, so the background service is covered too).
     */
    fun installCrashRecorder(context: Context) {
        val file = File(context.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(
                    "Crash at ${java.time.Instant.now()} on ${thread.name}\n${error.stackTraceToString()}\n" +
                        "--- log before the crash ---\n" + scrub(logcat("-t", "400", "--pid=${Process.myPid()}"))
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun device(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    /** Logs sent from the Plan&Ride watch app ("Report a problem" there). */
    fun saveWatchLog(context: Context, text: String) = File(context.filesDir, WATCH_FILE).writeText(text)

    fun collect(context: Context): String = buildString {
        append("testEM ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${device()}\n")
        File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.let {
            append("\n--- last crash ---\n").append(it.readText().take(40_000)).append('\n')
        }
        File(context.filesDir, WATCH_FILE).takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < WATCH_FRESH_MS }?.let {
            append("\n--- watch ---\n").append(it.readText().takeLast(80_000)).append('\n')
        }
        append("\n--- logcat (this app only) ---\n")
        // All of this app's processes, not only the current one: after a crash the app restarts as a new process,
        // and the lines leading up to the crash belong to the old one. Apps only ever see their own lines.
        append(scrub(logcat("-t", "1500")))
    }

    private fun logcat(vararg args: String): String = runCatching {
        val process = ProcessBuilder(listOf("logcat", "-d", "-v", "threadtime") + args).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { "logcat unavailable: ${it.message}" }

    /** Drops anything that looks like a secret before the log leaves the phone. */
    private fun scrub(text: String): String = text
        .replace(Regex("""(?i)(password|passwd|pin|token|cookie|wpis|authorization)(["'=:\s]+)\S+"""), "$1$2[removed]")
        .replace(Regex("""[A-Za-z0-9+/_-]{60,}={0,2}"""), "[long value removed]")
}
