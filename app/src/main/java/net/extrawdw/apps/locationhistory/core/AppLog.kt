package net.extrawdw.apps.locationhistory.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight, app-wide file logger. Every component logs through here; output goes both to logcat
 * and to a rotating file under `filesDir/logs/`. A **new session file is started each time the
 * recorder service starts** (and when a file grows too large), so individual logs stay small and
 * easy to read. Old session files are pruned to a fixed count.
 */
object AppLog {
    private const val TAG_PREFIX = "Pathline"
    private const val MAX_FILES = 20
    private const val MAX_FILE_BYTES = 1_000_000L

    private val lock = Any()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private var logsDir: File? = null
    private var current: File? = null

    /** Call once from the Application. */
    fun init(context: Context) = synchronized(lock) {
        if (logsDir == null) {
            logsDir = File(context.filesDir, "logs").apply { mkdirs() }
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
        val files = dir.listFiles { f -> f.name.startsWith("session-") }?.sortedBy { it.name } ?: return
        if (files.size > MAX_FILES) files.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    /** Newest session files first — for an in-app log viewer / export. */
    fun sessionFiles(): List<File> =
        logsDir?.listFiles { f -> f.name.startsWith("session-") }?.sortedByDescending { it.name }
            ?: emptyList()
}
