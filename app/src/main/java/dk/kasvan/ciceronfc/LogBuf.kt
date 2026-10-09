package dk.kasvan.ciceronfc

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log der vises i app'en og kan deles. Gentagne linjer slås sammen. */
object LogBuf {
    private val lines = ArrayDeque<String>()
    private var last = ""
    private var repeats = 1
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Volatile
    var listener: (() -> Unit)? = null

    fun add(msg: String) {
        synchronized(this) {
            val ts = fmt.format(Date())
            if (msg == last && lines.isNotEmpty()) {
                repeats++
                lines.removeLast()
                lines.addLast("$ts $msg (×$repeats)")
            } else {
                last = msg
                repeats = 1
                lines.addLast("$ts $msg")
                while (lines.size > 1000) lines.removeFirst()
            }
        }
        // Bevidst ikke skrevet til telefonens systemlog (logcat)
        listener?.invoke()
    }

    fun text(): String = synchronized(this) { lines.joinToString("\n") }
}
