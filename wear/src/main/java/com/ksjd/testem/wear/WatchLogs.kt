package com.ksjd.testem.wear

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/** Path of the watch's logs sent to the phone for a bug report (the phone's WatchStopService). */
const val LOG_PATH = "/plan-ride/watch-log"

/** The watch app's logs for a bug report, kept across a crash like the phone's AppLogs. */
object WatchLogs {
    private const val CRASH_FILE = "last_crash.txt"

    /** A crash's stack trace and the last log lines before it, written before the process dies. */
    fun installCrashRecorder(context: Context) {
        val file = File(context.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(
                    "Crash at ${java.time.Instant.now()} on ${thread.name}\n${error.stackTraceToString()}\n" +
                        "--- log before the crash ---\n" + logcat("-t", "300", "--pid=${Process.myPid()}")
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Version, device, the last crash and this app's log (all its processes, so lines before a crash too). Under 90 kB. */
    fun collect(context: Context): String = buildString {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        append("Plan&Ride watch ${info.versionName} (${info.longVersionCode}) on ${Build.MANUFACTURER} ${Build.MODEL}, ")
        append("Wear OS / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.let { append("\n--- last crash on the watch ---\n").append(it.readText().take(30_000)).append('\n') }
        append("\n--- watch logcat (this app only) ---\n")
        append(logcat("-t", "800").takeLast(55_000))
    }.let(::scrub)

    private fun logcat(vararg args: String): String = runCatching {
        val process = ProcessBuilder(listOf("logcat", "-d", "-v", "threadtime") + args).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { "logcat unavailable: ${it.message}" }

    private fun scrub(text: String): String = text
        .replace(Regex("""(?i)(password|passwd|pin|token|cookie|authorization)(["'=:\s]+)\S+"""), "$1$2[removed]")
        .replace(Regex("""[A-Za-z0-9+/_-]{60,}={0,2}"""), "[long value removed]")
}

/** Runs first in every watch-app process (also when only the Data Layer listener starts it). */
class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WatchLogs.installCrashRecorder(this)
    }
}
