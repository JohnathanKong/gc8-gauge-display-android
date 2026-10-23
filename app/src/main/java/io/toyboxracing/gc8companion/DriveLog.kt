package io.toyboxracing.gc8companion

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local bounded event journal; no cloud upload. May contain road names and destinations. */
object DriveLog {
    private const val FILE = "gc8_drive_log.txt"
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_BYTES = 384 * 1024
    private val lock = Any()
    private fun file(context: Context) = File(context.filesDir, FILE)
    fun record(context: Context, stage: String, detail: String) {
        synchronized(lock) {
            try {
                val f = file(context)
                if (f.length() > MAX_BYTES) {
                    val previous = f.readText(Charsets.UTF_8)
                    val tail = previous.takeLast(KEEP_BYTES)
                    f.writeText("[older events truncated]\n" + tail.substringAfter('\n'), Charsets.UTF_8)
                }
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val safe = detail.replace('\n', ' ').replace('\r', ' ').take(900)
                f.appendText("$stamp uptime=${SystemClock.elapsedRealtime()}ms [$stage] $safe\n", Charsets.UTF_8)
            } catch (_: Exception) { /* Never crash navigation for diagnostics. */ }
        }
    }
    fun export(context: Context): File = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) f.writeText("No drive events recorded yet.\n")
        f
    }
    fun clear(context: Context) = synchronized(lock) { file(context).writeText("") }
    fun summary(context: Context): String = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) "No drive log yet" else "Drive log: ${f.length()} bytes"
    }
}
