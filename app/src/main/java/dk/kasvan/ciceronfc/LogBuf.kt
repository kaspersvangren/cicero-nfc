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

    /** Fjern det, der kan være personoplysninger: forespørgsler i adresser, e-mails og lange tal */
    fun scrub(msg: String) = msg
        .replace(Regex("\\?[^\\s\"']*"), "?…")
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "…@…")
        .replace(Regex("\\d{6,}"), "…")
        .take(250)
}

/**
 * Loggen ligger kun i hukommelsen og forsvinder ved et nedbrud. Derfor gemmes en kort
 * beskrivelse af nedbruddet (fejltype og de øverste kodelinjer, ingen lånerdata) i en fil,
 * som lægges ind i loggen ved næste start og derefter slettes.
 */
object CrashLog {
    fun install(ctx: android.content.Context) {
        val f = java.io.File(ctx.filesDir, "crash.txt")
        if (f.exists()) {
            try { LogBuf.add("Sidste nedbrud: " + f.readText().take(1500)) } catch (_: Exception) {}
            f.delete()
        }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val top = e.stackTrace.take(8).joinToString("\n") { "  at $it" }
                // Fejlteksten kan i sjældne tilfælde indeholde data fra siden
                val msg = LogBuf.scrub(e.message ?: "").take(200)
                f.writeText("${Date()} ${e.javaClass.name}: $msg\n$top")
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
}
