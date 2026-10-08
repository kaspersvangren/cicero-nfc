package dk.kasvan.ciceronfc

import android.content.Context
import android.nfc.Tag
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hjertet: holder styr på tagget ved telefonen og udfører det, Cicero beder om.
 * Alt der rører tagget eller lageret sker på én tråd (exec), så intet kolliderer.
 */
object Hub {
    const val DEFAULT_COUNTRY = "DK"
    const val DEFAULT_LIBRARY = "715900"

    class TagState(
        val phone: PhoneTag,
        var dsfid: Int?,
        var afi: Int?,
        var content: TagContent?,
    ) {
        val mac get() = phone.mac

        fun toJson(): JSONObject = JSONObject().apply {
            put("Trtype", 3)
            put("Dfsid", dsfid ?: 0)
            put("Id", TagContent.b64(phone.uidMsb))
            put("Mac", mac)
            put("Content", (content ?: TagContent.empty()).toJson())
        }

        fun label(): String {
            val c = content ?: return "ukendt bog ($mac)"
            val del = if (c.numItems > 1 || c.seqNum > 1) " del ${c.numItems}/${c.seqNum}" else ""
            return c.barcode.ifEmpty { "tomt tag" } + del
        }
    }

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private lateinit var ctx: Context
    private var started = false
    private val startTime = SystemClock.elapsedRealtime()

    // Kun brugt på exec-tråden
    private val inventory = LinkedHashMap<String, TagState>()
    private var current: TagState? = null
    private var pingFails = 0
    private var lastLibrary = DEFAULT_LIBRARY

    @Volatile var mode = "IDLE"
    @Volatile var statusText = "Klar – hold en bog mod telefonen"
        private set
    @Volatile var statusListener: (() -> Unit)? = null

    private val counters = linkedMapOf(
        "ReadInvFail" to 0L, "ReadInvSucc" to 0L,
        "ReadTagFail" to 0L, "ReadTagSucc" to 0L,
        "WriteTagSucc" to 0L, "WriteTagFail" to 0L,
        "WriteAFISucc" to 0L, "WriteAFIFail" to 0L,
    )

    fun start(context: Context) {
        if (started) return
        started = true
        ctx = context.applicationContext
        Server.start()
        exec.scheduleWithFixedDelay({ presenceCheck() }, 300, 300, TimeUnit.MILLISECONDS)
        LogBuf.add("Cicero NFC ${BuildConfigInfo.version(ctx)} startet")
    }

    private fun setStatus(s: String) {
        statusText = s
        statusListener?.invoke()
    }

    private fun count(k: String) {
        counters[k] = (counters[k] ?: 0L) + 1
    }

    private fun <T> onExec(f: () -> T): T = exec.submit(Callable(f)).get(10, TimeUnit.SECONDS)

    // ---------- NFC ----------

    fun onTagDiscovered(tag: Tag) {
        exec.execute { handleNewTag(tag) }
    }

    private fun handleNewTag(tag: Tag) {
        current?.let { drop(it, "erstattet af nyt tag") }
        val phone = try {
            PhoneTag(tag)
        } catch (e: Exception) {
            LogBuf.add("NFC: ${e.message}")
            return
        }
        val state = TagState(phone, null, null, null)
        try {
            val info = phone.systemInfo()
            state.dsfid = info.dsfid
            state.afi = info.afi
            count("ReadInvSucc")
        } catch (e: Exception) {
            count("ReadInvFail")
            LogBuf.add("NFC: kunne ikke læse systeminfo: ${e.message}")
        }
        try {
            state.content = TagContent.parse(phone.readBlocks(0, 8))
            state.content?.library?.takeIf { it.isNotEmpty() }?.let { lastLibrary = it }
            count("ReadTagSucc")
        } catch (e: Exception) {
            count("ReadTagFail")
            LogBuf.add("NFC: kunne ikke læse indhold: ${e.message}")
        }
        inventory[state.mac] = state
        current = state
        pingFails = 0
        Server.broadcast("addTag", state.toJson().toString())
        vibrate(40)
        val msg = "Læst: ${state.label()} · alarm ${afiText(state.afi)}"
        LogBuf.add(msg)
        setStatus(msg)
    }

    private fun presenceCheck() {
        val s = current ?: return
        try {
            s.phone.ping()
            pingFails = 0
        } catch (_: Exception) {
            pingFails++
            if (pingFails >= 2) drop(s, "fjernet fra telefonen")
        }
    }

    private fun drop(s: TagState, why: String) {
        inventory.remove(s.mac)
        if (current === s) current = null
        s.phone.close()
        Server.broadcast("removeTag", s.toJson().toString())
        LogBuf.add("Væk: ${s.label()} ($why)")
        setStatus("Klar – hold en bog mod telefonen")
    }

    // ---------- Det Cicero kalder (via Server) ----------

    fun inventoryJson(): String = onExec { inventoryJsonLocal() }

    private fun inventoryJsonLocal(): String {
        val o = JSONObject()
        for ((k, v) in inventory) o.put(k, v.toJson())
        return o.toString()
    }

    fun statusJson(): String = onExec {
        val reader = JSONObject().apply {
            put("StrHandle", JSONObject.NULL)
            put("PortHandle", 0)
            put("ReaderHandle", 0)
            put("Serial", "PHONE")
            put("IntSerial", 0)
            put("Name", "Telefon-NFC (Cicero NFC)")
            put("Family", "Android NFC")
            for ((k, v) in counters) put(k, v)
        }
        val last = JSONObject()
        for ((k, v) in inventory) last.put(k, v.toJson())
        JSONObject().apply {
            put("Uptime", uptime())
            put("Reader", reader)
            put("LastInventory", last)
            put("Client", "127.0.0.1")
            put("Mode", mode)
        }.toString()
    }

    class Result(val code: Int, val body: String, val json: Boolean = false)

    fun alarm(on: Boolean): Result = onExec {
        val value = if (on) 0x07 else 0xC2
        val word = if (on) "TIL (sikret)" else "FRA (udlånt)"
        if (inventory.isEmpty()) {
            LogBuf.add("Alarm $word: ingen bog ved telefonen")
            setStatus("⚠ Hold bogen mod telefonen til den summer")
            return@onExec Result(400, "Inventory empty")
        }
        for (s in inventory.values) {
            try {
                s.phone.writeAfi(value)
                s.afi = value
                count("WriteAFISucc")
            } catch (e: Exception) {
                count("WriteAFIFail")
                LogBuf.add("Alarm $word FEJLEDE på ${s.label()}: ${e.message}")
                setStatus("⚠ Alarm kunne ikke skrives – prøv igen")
                return@onExec Result(500, "Failed activating alarm on id ${s.mac}, err: ${e.message} ")
            }
        }
        vibrate(250)
        val names = inventory.values.joinToString { it.label() }
        LogBuf.add("Alarm $word: $names")
        setStatus("✓ Alarm $word: $names")
        Result(200, "OK")
    }

    fun write(barcode: String): Result = onExec {
        if (inventory.isEmpty()) return@onExec Result(400, "Inventory empty")
        val n = inventory.size
        var i = 0
        for (s in inventory.values) {
            i++
            try {
                writeContent(s, barcode, numItems = n, seqNum = i)
            } catch (e: Exception) {
                LogBuf.add("Skriv $barcode FEJLEDE: ${e.message}")
                return@onExec Result(400, "Error writing inventory: ${e.message}")
            }
        }
        vibrate(250)
        LogBuf.add("Skrevet: $barcode til $n tag(s)")
        setStatus("✓ Skrevet: $barcode")
        Result(200, inventoryJsonLocal(), json = true)
    }

    fun writeTagBarcode(tagId: String, barcode: String): Result = onExec {
        if (inventory.isEmpty()) return@onExec Result(400, "Inventory empty")
        val s = inventory[tagId.uppercase()] ?: return@onExec Result(400, "Tag not in range: $tagId")
        val old = s.content
        try {
            writeContent(
                s, barcode,
                numItems = old?.numItems?.takeIf { it > 0 } ?: 1,
                seqNum = old?.seqNum?.takeIf { it > 0 } ?: 1,
            )
        } catch (e: Exception) {
            LogBuf.add("Skriv $barcode FEJLEDE: ${e.message}")
            return@onExec Result(400, "Error writing tag: ${e.message}")
        }
        vibrate(250)
        LogBuf.add("Skrevet: $barcode til ${s.mac}")
        setStatus("✓ Skrevet: $barcode")
        Result(200, s.toJson().toString(), json = true)
    }

    private fun writeContent(s: TagState, barcode: String, numItems: Int, seqNum: Int) {
        val library = s.content?.library?.takeIf { it.isNotEmpty() } ?: lastLibrary
        val country = s.content?.country?.takeIf { it.isNotEmpty() } ?: DEFAULT_COUNTRY
        val bytes = TagContent.build(barcode, numItems, seqNum, country, library)
        try {
            s.phone.writeBlocks(bytes)
            val check = TagContent.parse(s.phone.readBlocks(0, 8))
            if (check.barcode != barcode) throw IllegalStateException("kontrol-læsning viste '${check.barcode}'")
            s.content = check
            count("WriteTagSucc")
        } catch (e: Exception) {
            count("WriteTagFail")
            throw e
        }
    }

    // ---------- Hjælpere ----------

    fun afiText(afi: Int?): String = when (afi) {
        0x07 -> "sikret"
        0xC2 -> "udlånt"
        null -> "ukendt"
        else -> "0x%02X".format(afi)
    }

    private fun uptime(): String {
        val ms = SystemClock.elapsedRealtime() - startTime
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms % 60_000) / 1000.0
        val sb = StringBuilder()
        if (h > 0) sb.append(h).append('h')
        if (h > 0 || m > 0) sb.append(m).append('m')
        sb.append(String.format(java.util.Locale.ROOT, "%.3fs", s))
        return sb.toString()
    }

    private fun vibrate(ms: Long) {
        try {
            @Suppress("DEPRECATION")
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }
}

object BuildConfigInfo {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
