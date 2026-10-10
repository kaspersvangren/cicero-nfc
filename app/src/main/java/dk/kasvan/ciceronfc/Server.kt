package dk.kasvan.ciceronfc

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Lille HTTP-server på localhost:1667, der svarer som Deichmans go-feig.
 * Cicero Mobile taler med den præcis som med en FEIG-læser på en pc.
 *
 * Sikkerhed: kun Cicero Mobile (https://cicero.systematic.com) får svar, og kun når
 * forespørgslen er rettet til localhost. Mens app'en er låst, udføres ingen handlinger.
 * NB: en ondsindet app på samme telefon kan stadig forfalske Origin-headeren; browsere kan ikke.
 */
object Server {
    const val PORT = 1667

    private const val ALLOWED_ORIGIN = "https://cicero.systematic.com"
    private const val MAX_SSE_CLIENTS = 4
    private const val MAX_HEADER_LINES = 64
    private const val MAX_HEADER_BYTES = 16_384
    private const val MAX_BODY_BYTES = 65_536
    private const val REQUEST_DEADLINE_MS = 5_000L

    private class SseClient(val out: OutputStream)

    private val clients = CopyOnWriteArrayList<SseClient>()
    private val sseExec = Executors.newSingleThreadExecutor()

    // Højst 16 samtidige forbindelser; resten afvises i stedet for at starte uendeligt mange tråde
    private val pool = ThreadPoolExecutor(2, 16, 30, TimeUnit.SECONDS, SynchronousQueue())

    /** Sat hvis porten ikke kunne åbnes (fx fordi en anden app har taget den). Vises i bjælken. */
    @Volatile var bindError: String? = null
        private set

    fun start() {
        for (addr in listOf("127.0.0.1", "::1")) {
            thread(name = "http-$addr", isDaemon = true) {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(InetAddress.getByName(addr), PORT))
                    LogBuf.add("Server lytter på $addr:$PORT")
                    Hub.statusListener?.invoke()
                    while (true) {
                        val sock = ss.accept()
                        try {
                            pool.execute { handle(sock) }
                        } catch (_: RejectedExecutionException) {
                            try { sock.close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    LogBuf.add("Server på $addr: ${e.message}")
                    // IPv4 er den, Cicero bruger; IPv6 findes ikke på alle telefoner
                    if (addr == "127.0.0.1") {
                        bindError = "Port $PORT er optaget"
                        Hub.statusListener?.invoke()
                    }
                }
            }
        }
        // Holder event-forbindelserne i live
        thread(name = "sse-ping", isDaemon = true) {
            while (true) {
                Thread.sleep(15_000)
                send(": ping\n\n")
            }
        }
    }

    fun clientCount() = clients.size

    fun broadcast(event: String, data: String) {
        send("event: $event\ndata: $data\n\n")
    }

    private fun send(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sseExec.execute {
            for (c in clients) {
                try {
                    synchronized(c) {
                        c.out.write(bytes)
                        c.out.flush()
                    }
                } catch (_: IOException) {
                    clients.remove(c)
                }
            }
        }
    }

    // ---------- HTTP ----------

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    private fun hostOnly(hostHeader: String): String {
        val h = hostHeader.trim().lowercase()
        return if (h.startsWith("[")) h.substringBefore("]") + "]" else h.substringBefore(":")
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = REQUEST_DEADLINE_MS.toInt()
            val inp = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val req = readRequest(inp, SystemClock.elapsedRealtime() + REQUEST_DEADLINE_MS) ?: return sock.close()
            val origin = req.headers["origin"]
            val host = req.headers["host"]?.let { hostOnly(it) }

            // Kun Cicero Mobile, og kun rettet til localhost (beskytter mod andre sider og DNS-tricks)
            if (origin != ALLOWED_ORIGIN || host !in Hub.LOCAL_HOSTS) {
                LogBuf.add("Afvist forespørgsel (${origin ?: "uden afsender"} → ${req.path})")
                respond(out, 403, "text/plain", "Forbidden", null)
                sock.close()
                return
            }

            if (req.method == "OPTIONS") {
                val extra = mutableListOf(
                    "Access-Control-Allow-Methods: " + (req.headers["access-control-request-method"] ?: "GET, POST, HEAD"),
                    "Access-Control-Max-Age: 600",
                )
                req.headers["access-control-request-headers"]?.let { extra += "Access-Control-Allow-Headers: $it" }
                if (req.headers.containsKey("access-control-request-private-network")) {
                    extra += "Access-Control-Allow-Private-Network: true"
                }
                respond(out, 204, "text/plain", "", origin, extra)
                sock.close()
                return
            }

            when (req.path) {
                "/events", "/events/" -> {
                    serveEvents(sock, inp, out, origin)
                    return
                }
            }

            val r = route(req)
            if (req.path != "/.status") {
                // Kun materialenummer og tag-id vises; ukendte felter logges uden værdi (de kunne indeholde andet)
                val q = if (req.query.isEmpty()) "" else "?" + req.query.entries.joinToString("&") {
                    if (it.key == "barcode" || it.key == "tagid") "${it.key}=${it.value.take(32)}" else "${it.key}=…"
                }
                LogBuf.add("Cicero → ${req.method} ${req.path}$q → ${r.code}")
            } else {
                LogBuf.add("Cicero → /.status (test forbindelse)")
            }
            respond(out, r.code, if (r.json) "application/json" else "text/plain; charset=utf-8", r.body, origin)
            sock.close()
        } catch (e: Exception) {
            LogBuf.add("HTTP-fejl: ${e.message}")
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private val actions = setOf("/alarmOn", "/alarmOff", "/write", "/writetagbarcode")

    private fun route(req: Request): Hub.Result {
        if (Hub.locked && req.path in actions) return Hub.Result(423, "Locked")
        return when (req.path) {
            "/.status" -> Hub.Result(200, Hub.statusJson(), json = true)
            "/scan" -> Hub.Result(200, Hub.inventoryJson(), json = true)
            "/start" -> { Hub.mode = "SCAN"; Hub.Result(200, "") }
            "/stop" -> { Hub.mode = "IDLE"; Hub.Result(200, "") }
            "/alarmOn" -> Hub.alarm(on = true)
            "/alarmOff" -> Hub.alarm(on = false)
            "/write" -> {
                val bc = req.query["barcode"]
                if (bc.isNullOrEmpty()) Hub.Result(400, "Url Param 'barcode' is missing") else Hub.write(bc)
            }
            "/writetagbarcode" -> {
                val id = req.query["tagid"]
                val bc = req.query["barcode"]
                when {
                    id.isNullOrEmpty() -> Hub.Result(400, "Url Param 'tagid' is missing")
                    bc.isNullOrEmpty() -> Hub.Result(400, "Url Param 'barcode' is missing")
                    else -> Hub.writeTagBarcode(id, bc)
                }
            }
            else -> Hub.Result(404, "404 page not found")
        }
    }

    private fun serveEvents(sock: Socket, inp: InputStream, out: OutputStream, origin: String?) {
        if (clients.size >= MAX_SSE_CLIENTS) {
            respond(out, 503, "text/plain", "Too many listeners", origin)
            sock.close()
            return
        }
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: text/event-stream\r\n")
        head.append("Cache-Control: no-cache\r\n")
        head.append("Connection: keep-alive\r\n")
        for (h in corsHeaders(origin)) head.append(h).append("\r\n")
        head.append("\r\n")
        val client = SseClient(out)
        synchronized(client) {
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.flush()
        }
        clients += client
        // Cicero åbner og lukker forbindelsen ved hvert skærmskift – log kun, når den begynder/holder op med at lytte
        if (clients.size == 1) LogBuf.add("Cicero lytter (RFID slået til)")
        Hub.onClientConnected()
        try {
            sock.soTimeout = 0
            while (inp.read() != -1) { /* venter til Cicero lukker */ }
        } catch (_: IOException) {
        } finally {
            clients.remove(client)
            try { sock.close() } catch (_: Exception) {}
            if (clients.isEmpty()) LogBuf.add("Cicero lytter ikke længere (RFID slået fra eller side uden RFID)")
            Hub.onClientsChanged()
        }
    }

    private fun corsHeaders(origin: String?): List<String> =
        if (origin != null) {
            listOf(
                "Access-Control-Allow-Origin: $origin",
                "Access-Control-Allow-Credentials: true",
                "Vary: Origin",
            )
        } else {
            emptyList()
        }

    private fun respond(
        out: OutputStream, code: Int, type: String, body: String,
        origin: String?, extra: List<String> = emptyList(),
    ) {
        val b = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            423 -> "Locked"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Status"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        if (code != 204) {
            sb.append("Content-Type: $type\r\n")
            sb.append("Content-Length: ${b.size}\r\n")
        }
        sb.append("Connection: close\r\n")
        for (h in corsHeaders(origin) + extra) sb.append(h).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (code != 204) out.write(b)
        out.flush()
    }

    private fun readRequest(inp: InputStream, deadline: Long): Request? {
        var budget = MAX_HEADER_BYTES
        val first = readLine(inp, deadline) ?: return null
        budget -= first.length
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        var lines = 0
        while (true) {
            val line = readLine(inp, deadline) ?: break
            if (line.isEmpty()) break
            budget -= line.length
            if (++lines > MAX_HEADER_LINES || budget < 0) throw IOException("for mange headere")
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY_BYTES) throw IOException("for stor forespørgsel")
        var left = len
        while (left > 0) {
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("for langsom forespørgsel")
            if (inp.read() == -1) break
            left--
        }

        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = HashMap<String, String>()
        if (q >= 0) {
            for (pair in target.substring(q + 1).split('&')) {
                if (pair.isEmpty()) continue
                val e = pair.indexOf('=')
                val k = if (e >= 0) pair.substring(0, e) else pair
                val v = if (e >= 0) pair.substring(e + 1) else ""
                query[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            }
        }
        return Request(parts[0].uppercase(), path, query, headers)
    }

    private fun readLine(inp: InputStream, deadline: Long): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("for langsom forespørgsel")
            val c = inp.read()
            if (c == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (c != '\r'.code) buf.write(c)
            if (buf.size() > MAX_HEADER_BYTES) throw IOException("for lang header-linje")
        }
        return buf.toString("ISO-8859-1")
    }
}
