package net.extrawdw.apps.locationhistory.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight, app-wide file logger. Every component logs through here; output goes both to logcat
 * and to a rotating file under `noBackupFilesDir/logs/` — sessions can contain location traces, so
 * they must stay out of Google cloud auto-backup. A **new session file is started each time the
 * recorder service starts** (and when a file grows too large), so individual logs stay small and
 * easy to read. Old session files are pruned to a fixed count.
 */
object AppLog {
    private const val TAG_PREFIX = "Pathline"
    private const val MAX_FILES = 20
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val EXIT_INFO_PREFS = "app_exit_info"
    private const val LAST_LOGGED_EXIT_TIMESTAMP = "last_logged_exit_timestamp"
    private const val MAX_EXIT_RECORDS = 10
    private const val MAX_EXIT_TRACE_FILES = 20
    private const val MAX_EXIT_TRACE_BYTES = 512_000
    private const val MAX_EXIT_DESCRIPTION_CHARS = 512
    private const val MAX_PROCESS_STATE_SUMMARY_BYTES = 512

    private val lock = Any()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val exitInfoTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var logsDir: File? = null
    private var exitTracesDir: File? = null
    private var current: File? = null

    /** Call once from the Application. */
    fun init(context: Context) = synchronized(lock) {
        if (logsDir == null) {
            // noBackupFilesDir: session logs carry location traces and must never ride along in
            // Google cloud auto-backup (filesDir is backed up by default).
            logsDir = File(context.noBackupFilesDir, "logs").apply { mkdirs() }
            exitTracesDir = File(context.noBackupFilesDir, "logs/exit-traces").apply { mkdirs() }
            // Purge sessions written to the old, backed-up location (filesDir/logs, pre-move).
            File(context.filesDir, "logs")
                .listFiles { f -> f.name.startsWith("session-") }
                ?.forEach { runCatching { it.delete() } }
            exitTracesDir?.let { pruneExitTraces(it) }
            startSession("app-start")
        }
    }

    /** Begin a fresh log file. Called at every service start so each session is its own file. */
    fun startSession(reason: String) = synchronized(lock) {
        val dir = logsDir ?: return
        current = File(dir, "session-${fileFmt.format(Date())}.log")
        prune(dir)
        write("=== session start: $reason ===")
    }

    fun i(tag: String, msg: String) = log('I', tag, msg)
    fun w(tag: String, msg: String) = log('W', tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        log('E', tag, if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg)

    /**
     * Write ApplicationExitInfo records from previous process deaths into the current session log.
     * The platform keeps these historical records outside this process, so they are most useful at
     * the next app start after a crash, ANR, low-memory kill, user stop, or dependency death.
     */
    fun logRecentApplicationExitInfo(context: Context) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(EXIT_INFO_PREFS, Context.MODE_PRIVATE)
        val lastLoggedTimestamp = prefs.getLong(LAST_LOGGED_EXIT_TIMESTAMP, 0L)
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                MAX_EXIT_RECORDS,
            )
        }.onFailure { w("AppExitInfo", "read failed: ${it.message}") }
            .getOrDefault(emptyList())
            .filter { it.timestamp > lastLoggedTimestamp }
            .sortedBy { it.timestamp }

        if (exits.isEmpty()) return

        exits.forEach { exitInfo ->
            i("AppExitInfo", exitInfo.toLogLine(exitInfo.writeTraceFileIfPresent()))
        }
        prefs.edit()
            .putLong(LAST_LOGGED_EXIT_TIMESTAMP, exits.maxOf { it.timestamp })
            .apply()
    }

    private fun log(level: Char, tag: String, msg: String) {
        when (level) {
            'E' -> Log.e("$TAG_PREFIX/$tag", msg)
            'W' -> Log.w("$TAG_PREFIX/$tag", msg)
            else -> Log.i("$TAG_PREFIX/$tag", msg)
        }
        synchronized(lock) { write("${timeFmt.format(Date())} $level/$tag: $msg") }
    }

    private fun write(line: String) {
        val file = current ?: return
        runCatching {
            if (file.length() > MAX_FILE_BYTES) startSession("rollover")
            (current ?: file).appendText(line + "\n")
        }
    }

    private fun prune(dir: File) {
        val files =
            dir.listFiles { f -> f.name.startsWith("session-") }?.sortedBy { it.name } ?: return
        if (files.size > MAX_FILES) files.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun pruneExitTraces(dir: File) {
        val files =
            dir.listFiles { f -> f.name.startsWith("exit-") }?.sortedBy { it.name } ?: return
        if (files.size > MAX_EXIT_TRACE_FILES) {
            files.take(files.size - MAX_EXIT_TRACE_FILES).forEach { it.delete() }
        }
    }

    /** Newest session files first — for an in-app log viewer / export. */
    fun sessionFiles(): List<File> =
        logsDir?.listFiles { f -> f.name.startsWith("session-") }?.sortedByDescending { it.name }
            ?: emptyList()

    /** Delete one session log, ignoring files outside the app's session-log directory. */
    fun deleteSessionFile(file: File): Boolean = synchronized(lock) {
        val dir = logsDir ?: return false
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val logDir = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        val parent = runCatching { target.parentFile?.canonicalFile }.getOrNull()
        if (parent != logDir || !target.name.startsWith("session-")) return false
        runCatching { target.delete() }.getOrDefault(false)
    }

    /** Delete every current session log file and return the number removed. */
    fun deleteSessionFiles(): Int = synchronized(lock) {
        logsDir?.listFiles { f -> f.name.startsWith("session-") }
            ?.count { runCatching { it.delete() }.getOrDefault(false) }
            ?: 0
    }

    private fun ApplicationExitInfo.toLogLine(traceFile: File?): String =
        buildString {
            append("process exit")
            append(" time=").append(exitInfoTimeFmt.format(Date(timestamp)))
            append(" reason=").append(reasonName(reason))
            append(" status=").append(status)
            append(" importance=").append(importanceName(importance))
            append(" pid=").append(pid)
            append(" realUid=").append(realUid)
            append(" packageUid=").append(packageUid)
            append(" definingUid=").append(definingUid)
            append(" process=").append(processName ?: "<unknown>")
            append(" pssKb=").append(pss)
            append(" rssKb=").append(rss)
            description?.limitedSingleLine(MAX_EXIT_DESCRIPTION_CHARS)?.takeIf { it.isNotBlank() }
                ?.let {
                    append(" description=\"").append(it).append("\"")
            }
            processStateSummaryText()?.let { append(" processStateSummary=").append(it) }
            traceFile?.let { append(" traceFile=").append(it.name) }
        }

    private fun ApplicationExitInfo.writeTraceFileIfPresent(): File? {
        val dir = exitTracesDir ?: return null
        val input = runCatching { traceInputStream }
            .onFailure { w("AppExitInfo", "trace unavailable: ${it.message}") }
            .getOrNull()
            ?: return null
        val file = File(dir, "exit-${fileFmt.format(Date(timestamp))}-pid$pid.trace")
        val wrote = runCatching {
            input.use { source ->
                file.outputStream().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = MAX_EXIT_TRACE_BYTES
                    var truncated = false
                    while (remaining > 0) {
                        val read = source.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        remaining -= read
                    }
                    if (remaining == 0 && source.read() >= 0) {
                        truncated = true
                    }
                    if (truncated) {
                        sink.write("\n\n[truncated at $MAX_EXIT_TRACE_BYTES bytes]\n".toByteArray())
                    }
                }
            }
            pruneExitTraces(dir)
        }.onFailure {
            w("AppExitInfo", "trace write failed: ${it.message}")
            runCatching { file.delete() }
        }.isSuccess
        return file.takeIf { wrote && it.length() > 0L }
    }

    private fun ApplicationExitInfo.processStateSummaryText(): String? {
        val summary = processStateSummary ?: return null
        if (summary.isEmpty()) return null
        val prefix = summary.take(MAX_PROCESS_STATE_SUMMARY_BYTES).toByteArray()
        val suffix = if (summary.size > prefix.size) "..." else ""
        val text = prefix.toString(Charsets.UTF_8).singleLine()
        return if (text.isReadableText()) {
            "\"$text$suffix\""
        } else {
            "base64:${Base64.encodeToString(prefix, Base64.NO_WRAP)}$suffix"
        }
    }

    private fun reasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else -> "UNKNOWN_$reason"
        }

    private fun importanceName(importance: Int): String =
        when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "CANT_SAVE_STATE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
            else -> "UNKNOWN_$importance"
        }

    private fun String.singleLine(): String =
        lineSequence().joinToString(" ").trim()

    private fun String.limitedSingleLine(maxChars: Int): String {
        val text = singleLine()
        return if (text.length > maxChars) text.take(maxChars) + "..." else text
    }

    private fun String.isReadableText(): Boolean =
        isNotBlank() && count { it.isISOControl() && !it.isWhitespace() } == 0
}
