package com.ksjd.testem.hub

import android.content.Context
import android.os.Build
import android.os.Process
import com.ksjd.testem.BuildConfig
import java.io.File

/** What a bug report attaches: this app's own logcat lines and the last crash, nothing else. */
object AppLogs {
    private const val CRASH_FILE = "last_crash.txt"

    /** Keeps the stack trace of a crash so the next bug report can include it. */
    fun installCrashRecorder(context: Context) {
        val file = File(context.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText("Crash at ${java.time.Instant.now()} on ${thread.name}\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun device(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    fun collect(context: Context): String = buildString {
        append("testEM ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${device()}\n")
        File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.let {
            append("\n--- last crash ---\n").append(it.readText().take(20_000)).append('\n')
        }
        append("\n--- logcat (this app only) ---\n")
        // Apps can read their own process's log without any permission.
        val lines = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-t", "800", "-v", "threadtime", "--pid=${Process.myPid()}")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "logcat unavailable: ${it.message}" }
        append(scrub(lines))
    }

    /** Drops anything that looks like a secret before the log leaves the phone. */
    private fun scrub(text: String): String = text
        .replace(Regex("""(?i)(password|passwd|pin|token|cookie|wpis|authorization)(["'=:\s]+)\S+"""), "$1$2[removed]")
        .replace(Regex("""[A-Za-z0-9+/_-]{60,}={0,2}"""), "[long value removed]")
}
