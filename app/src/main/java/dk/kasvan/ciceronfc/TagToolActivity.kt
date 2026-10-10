package dk.kasvan.ciceronfc

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Værktøj til tags: se hvad der står på et tag, slå alarmen til/fra, nulstil det, eller programmér en ny chip
 * ("konvertér"). Mens værktøjet er åbent, får Cicero ikke besked om tags – intet kan lånes ud eller afleveres ved et uheld.
 * App'en sender aldrig lås-kommandoer til et tag (de kan ikke fortrydes).
 */
class TagToolActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    companion object {
        const val EXTRA_DARK = "dark"
        private const val AFI_ON = 0x07   // sikret
        private const val AFI_OFF = 0xC2  // udlånt
        private const val PREF_LIBRARY = "library"
        private const val FORGET_AFTER_MS = 10_000L // kortet forsvinder 10 sek. efter chippen er fjernet
    }

    private class Info(
        val mac: String,
        val maker: String,
        val afi: Int?,
        val content: TagContent?,
        val blank: Boolean,
        val danish: Boolean,
    )

    private class Pending(val barcode: String, var part: Int, val total: Int)

    // Farver som Cicero (lys/mørk)
    private var dark = true
    private val bg get() = if (dark) Color.parseColor("#2E2E2E") else Color.parseColor("#F2F5F7")
    private val card get() = if (dark) Color.parseColor("#383838") else Color.WHITE
    private val fg get() = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#404040")
    private val sub get() = if (dark) Color.parseColor("#C6C6C6") else Color.parseColor("#767676")
    private val keyBg get() = if (dark) Color.parseColor("#4B4B4B") else Color.parseColor("#E0E0E0")
    private val blue get() = if (dark) Color.parseColor("#3098E8") else Color.parseColor("#0078D3")
    private val green = Color.parseColor("#2F855A")
    private val orange = Color.parseColor("#DD6B20")
    private val red = Color.parseColor("#C53030")
    private val tileBg get() = if (dark) Color.parseColor("#454545") else Color.parseColor("#F2F5F7")
    private val warnBg get() = if (dark) Color.parseColor("#5A2626") else Color.parseColor("#FDE8E8")
    private val warnFg get() = if (dark) Color.parseColor("#FEB2B2") else Color.parseColor("#9B2C2C")

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var nfc: NfcAdapter? = null

    // Kun på exec-tråden
    private var tag: PhoneTag? = null
    private var pingFails = 0
    private var lastWrittenMac: String? = null
    private var confirmedMac: String? = null
    private var askingMac: String? = null

    @Volatile private var info: Info? = null
    @Volatile private var present = false
    @Volatile private var pending: Pending? = null
    @Volatile private var goneAt = 0L

    private lateinit var libraryText: TextView
    private lateinit var cardBox: LinearLayout
    private lateinit var stateText: TextView
    private lateinit var numberLabel: TextView
    private lateinit var statusTitle: TextView
    private lateinit var tiles: LinearLayout
    private lateinit var tagButtons: LinearLayout
    private lateinit var feedback: TextView
    private lateinit var input: EditText
    private lateinit var abcBtn: TextView
    private lateinit var partText: TextView
    private lateinit var totalText: TextView
    private lateinit var writeBtn: TextView
    private lateinit var cancelBtn: TextView
    private var part = 1
    private var total = 1

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(ScanActivity.EXTRA_VALUE)?.let {
            input.setText(it)
            input.setSelection(it.length)
            LogBuf.add("Værktøj: nummer læst med kamera")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("ui", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = intent.getBooleanExtra(EXTRA_DARK, true)
        nfc = NfcAdapter.getDefaultAdapter(this)
        Hub.configuredLibrary = prefs().getString(PREF_LIBRARY, null)
        buildUi()
        showInfo()
        exec.scheduleWithFixedDelay({ presenceCheck() }, 400, 400, TimeUnit.MILLISECONDS)
        LogBuf.add("Værktøj til tags åbnet")
    }

    // ---------- Skærmen ----------

    private fun button(label: String, color: Int, textColor: Int = Color.WHITE, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(textColor)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
        setPadding(dp(8), dp(14), dp(8), dp(14))
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun label(t: String, size: Float = 15f, color: Int = sub) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        val back = TextView(this).apply {
            text = "←"
            textSize = 26f
            setTextColor(fg)
            setPadding(dp(4), dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        }
        val title = label("Værktøj til tags", 22f, fg).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(title)
        })

        libraryText = label("", 15f)
        val editLib = TextView(this).apply {
            text = "Ret"
            textSize = 15f
            setTextColor(blue)
            setPadding(dp(16), dp(8), dp(4), dp(8))
            setOnClickListener { askLibrary(null) }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(libraryText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(editLib)
        })
        updateLibraryText()

        // Kortet med tagget: status, materialenummer stort, og et felt for hver oplysning
        stateText = label("", 14f)
        numberLabel = label("Materialenummer", 13f)
        statusTitle = label("", 28f, fg).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.04f
        }
        tiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        feedback = label("", 16f, fg).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(16).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(stateText)
            addView(numberLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(statusTitle)
            addView(tiles, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        col.addView(cardBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(feedback, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); marginStart = dp(4); marginEnd = dp(4)
        })

        // Knapperne til tagget vises kun, mens det ligger ved telefonen
        tagButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(
                button("Alarm til", orange) { alarm(true) },
                button("Alarm fra", green) { alarm(false) },
            ))
            addView(row(button("Nulstil tag", keyBg, red) { confirmReset() }))
        }
        col.addView(tagButtons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // Programmér ny chip
        col.addView(label("Programmér chip", 18f, fg).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), dp(20), 0, dp(4))
        })
        col.addView(label("Materialenummer").apply { setPadding(dp(4), 0, 0, dp(4)) })
        input = EditText(this).apply {
            textSize = 24f
            setTextColor(fg)
            setHintTextColor(sub)
            hint = "fx 5123456789"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            background = GradientDrawable().apply { setColor(card); setStroke(dp(1), sub); cornerRadius = dp(10).toFloat() }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        abcBtn = button("ABC", keyBg, fg) {
            val isNumber = (input.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
            input.inputType = if (isNumber) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else InputType.TYPE_CLASS_NUMBER
            abcBtn.text = if (isNumber) "123" else "ABC"
            input.setSelection(input.text.length)
        }
        val camBtn = button("Kamera", blue) {
            scan.launch(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_DARK, dark))
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(abcBtn, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(camBtn, LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        })

        // Del x af y – sæt programmeres én chip ad gangen
        partText = label("", 22f, fg).apply { gravity = Gravity.CENTER; minWidth = dp(36) }
        totalText = label("", 22f, fg).apply { gravity = Gravity.CENTER; minWidth = dp(36) }
        fun step(label: String, f: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = GradientDrawable().apply { setColor(keyBg); cornerRadius = dp(10).toFloat() }
            setOnClickListener { if (pending == null) { f(); updateParts() } }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
            addView(label("Del ", 18f, fg))
            addView(step("−") { if (part > 1) part-- }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(partText)
            addView(step("+") { if (part < total) part++ }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(label("  af ", 18f, fg))
            addView(step("−") { if (total > 1) { total--; if (part > total) part = total } }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(totalText)
            addView(step("+") { if (total < 9) total++ }, LinearLayout.LayoutParams(dp(44), dp(44)))
        })
        updateParts()

        writeBtn = button("Programmér chip", blue) { arm() }
        cancelBtn = button("Stop", keyBg, fg) { disarm("Stoppet") }.apply { visibility = View.GONE }
        col.addView(writeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        col.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        col.addView(label("Efter programmering er alarmen slået til (sikret). Telefonen skriver én chip ad gangen – sæt programmeres del for del.", 13f).apply {
            setPadding(dp(4), dp(10), dp(4), 0)
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            fitsSystemWindows = true
            addView(col)
        }
        setContentView(scroll)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (!dark && Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }

    private fun updateParts() {
        partText.text = "$part"
        totalText.text = "$total"
    }

    private fun updateLibraryText() {
        val lib = Hub.configuredLibrary
        libraryText.text = if (lib != null) "Bibliotek: DK-$lib" else "Bibliotek: ikke sat"
    }

    private fun say(text: String, color: Int) {
        runOnUiThread {
            feedback.text = text
            feedback.setTextColor(color)
        }
    }

    private enum class Kind { NORMAL, WARN, ALARM_ON, ALARM_OFF }
    private class Field(val label: String, val value: String, val kind: Kind = Kind.NORMAL)

    private fun tile(f: Field) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val warn = f.kind == Kind.WARN
        background = GradientDrawable().apply { setColor(if (warn) warnBg else tileBg); cornerRadius = dp(10).toFloat() }
        setPadding(dp(12), dp(8), dp(12), dp(10))
        addView(label(f.label, 13f, if (warn) warnFg else sub))
        val v = TextView(this@TagToolActivity).apply {
            text = f.value
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            when (f.kind) {
                Kind.ALARM_ON, Kind.ALARM_OFF -> {
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(if (f.kind == Kind.ALARM_ON) orange else green); cornerRadius = dp(20).toFloat()
                    }
                    setPadding(dp(12), dp(2), dp(12), dp(3))
                }
                Kind.WARN -> setTextColor(warnFg)
                else -> setTextColor(fg)
            }
        }
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
    }

    private fun fieldsFor(i: Info): List<Field> {
        val c = i.content
        val out = ArrayList<Field>()
        if (c != null && i.danish && !i.blank) {
            out += if (c.seqNum in 1..c.numItems) Field("Del", "${c.seqNum} af ${c.numItems}")
            else Field("Del", "uklart (${c.seqNum}/${c.numItems})", Kind.WARN)
            val own = Hub.configuredLibrary
            out += when {
                c.library.isEmpty() -> Field("Bibliotek", "ikke angivet", Kind.WARN)
                own != null && c.library != own -> Field("Bibliotek", "${c.country}-${c.library}", Kind.WARN)
                else -> Field("Bibliotek", "${c.country}-${c.library}")
            }
        }
        if (!i.danish && !i.blank) out += Field("Format", "ikke dansk", Kind.WARN)
        out += when (i.afi) {
            AFI_ON -> Field("Alarm", "Til", Kind.ALARM_ON)
            AFI_OFF -> Field("Alarm", "Fra", Kind.ALARM_OFF)
            null -> Field("Alarm", "ukendt", Kind.WARN)
            else -> Field("Alarm", "anden (0x%02X)".format(i.afi), Kind.WARN)
        }
        out += Field("Chip", i.maker)
        return out
    }

    private fun showInfo() {
        runOnUiThread {
            handler.removeCallbacks(forgetTick)
            val i = info
            tiles.removeAllViews()
            if (i == null) {
                cardBox.alpha = 1f
                stateText.text = "Hold et tag mod telefonen"
                numberLabel.visibility = View.GONE
                statusTitle.visibility = View.GONE
                tagButtons.visibility = View.GONE
                return@runOnUiThread
            }
            val c = i.content
            val hasNumber = c != null && i.danish && !i.blank
            numberLabel.visibility = if (hasNumber) View.VISIBLE else View.GONE
            statusTitle.visibility = View.VISIBLE
            statusTitle.text = when {
                i.blank -> "Tom chip"
                hasNumber -> c!!.barcode.ifEmpty { "(intet nummer)" }
                else -> "Ukendt indhold"
            }
            // Felterne to og to
            val fields = fieldsFor(i)
            for (k in fields.indices step 2) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (f in fields.subList(k, minOf(k + 2, fields.size))) {
                    r.addView(tile(f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                if (fields.size - k == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
                tiles.addView(r)
            }
            if (present) {
                cardBox.alpha = 1f
                stateText.text = "Chip ved telefonen"
                tagButtons.visibility = View.VISIBLE
            } else {
                cardBox.alpha = 0.55f
                tagButtons.visibility = View.GONE
                forgetTick.run()
            }
        }
    }

    /** Chippen er fjernet: vis "Sidst læst" med nedtælling, og glem den efter 10 sekunder */
    private val forgetTick = object : Runnable {
        override fun run() {
            if (present || info == null) return
            val left = FORGET_AFTER_MS - (SystemClock.elapsedRealtime() - goneAt)
            if (left <= 0) {
                val at = goneAt
                exec.execute {
                    if (!present && goneAt == at) {
                        info = null
                        tag?.close(); tag = null
                        if (pending == null) say("", fg)
                    }
                    showInfo()
                }
                return
            }
            stateText.text = "Sidst læst · forsvinder om ${(left + 999) / 1000} sek."
            handler.postDelayed(this, 250)
        }
    }

    // ---------- NFC ----------

    override fun onTagDiscovered(t: Tag) {
        exec.execute { handleTag(t) }
    }

    private fun handleTag(t: Tag) {
        val pt = try { PhoneTag(t) } catch (e: Exception) {
            say("Ikke et bibliotekstag", red)
            return
        }
        tag?.close()
        tag = pt
        present = true
        pingFails = 0
        info = readInfo(pt)
        if (pending == null) say("", fg)
        showInfo()
        vibrate(longArrayOf(0, 50))
        maybeProgram()
    }

    private fun readInfo(pt: PhoneTag): Info {
        val afi = try { pt.systemInfo().afi } catch (_: Exception) { null }
        val raw = try { pt.readBlocks(0, 8) } catch (_: Exception) { null }
        val blank = raw != null && raw.all { it == 0.toByte() }
        val danish = raw != null && (raw[0].toInt() and 0xFF) == 0x11
        val content = if (danish) try { TagContent.parse(raw!!) } catch (_: Exception) { null } else null
        content?.let { Hub.noteItemNumber(it.barcode, it.library) }
        content?.library?.takeIf { it.isNotEmpty() }?.let { if (Hub.configuredLibrary == null) suggestLibrary(it) }
        val makerByte = pt.uidMsb.getOrNull(1)?.toInt()?.and(0xFF)
        val maker = when (makerByte) {
            0x04 -> "NXP (ICODE)"
            0x07 -> "Texas Instruments"
            0x02 -> "STMicroelectronics"
            0x05 -> "Infineon"
            0x16 -> "EM Microelectronic"
            null -> "ukendt"
            else -> "producent 0x%02X".format(makerByte)
        }
        return Info(pt.mac, maker, afi, content, blank, danish)
    }

    private fun presenceCheck() {
        val t = tag ?: return
        if (!present) return
        try {
            t.ping()
            pingFails = 0
        } catch (_: Exception) {
            if (++pingFails >= 2) {
                present = false
                goneAt = SystemClock.elapsedRealtime()
                showInfo()
            }
        }
    }

    /** Kør en handling på tagget ved telefonen; læs det igen bagefter, så kortet viser det rigtige */
    private fun withTag(what: String, f: (PhoneTag, Info) -> String) {
        exec.execute {
            val t = tag
            val i = info
            if (t == null || i == null || !present) {
                say("Hold et tag mod telefonen først", orange)
                return@execute
            }
            try {
                val msg = f(t, i)
                info = readInfo(t)
                showInfo()
                say(msg, green)
                vibrate(longArrayOf(0, 110, 80, 110))
            } catch (e: Exception) {
                LogBuf.add("Værktøj: $what fejlede: ${e.message}")
                say("$what fejlede – hold tagget stille og prøv igen", red)
                vibrate(longArrayOf(0, 80, 70, 80, 70, 80))
            }
        }
    }

    private fun alarm(on: Boolean) {
        withTag(if (on) "Alarm til" else "Alarm fra") { t, i ->
            t.writeAfi(if (on) AFI_ON else AFI_OFF)
            LogBuf.add("Værktøj: alarm ${if (on) "til" else "fra"} på ${i.content?.barcode ?: i.mac}")
            if (on) "Alarm slået til ✓" else "Alarm slået fra ✓"
        }
    }

    private fun confirmReset() {
        val i = info
        if (i == null || !present) { say("Hold et tag mod telefonen først", orange); return }
        val name = i.content?.barcode?.takeIf { it.isNotEmpty() } ?: "dette tag"
        val msg = SpannableStringBuilder().apply {
            val b0 = length; append(name); setSpan(StyleSpan(Typeface.BOLD), b0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" bliver helt tomt som en ny chip.\n\n")
            val r0 = length; append("Det kan ikke fortrydes.")
            setSpan(StyleSpan(Typeface.BOLD), r0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(red), r0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("⚠  Nulstil tag?")
            .setMessage(msg)
            .setPositiveButton("Nulstil") { _, _ ->
                withTag("Nulstil") { t, _ ->
                    t.writeBlocks(ByteArray(32))
                    t.writeAfi(0x00)
                    if (!t.readBlocks(0, 8).all { it == 0.toByte() }) throw IllegalStateException("kontrol-læsning viste data")
                    LogBuf.add("Værktøj: nulstillet tag (var $name)")
                    "Nulstillet ✓ – chippen er tom"
                }
            }
            .setNegativeButton("Annuller", null)
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(red)
    }

    // ---------- Programmering ----------

    private fun arm() {
        val barcode = input.text.toString().trim()
        if (barcode.isEmpty() || barcode.length > 16 || !barcode.all { it.code in 0x21..0x7E }) {
            say("Skriv et materialenummer (højst 16 tegn)", orange)
            return
        }
        if (Hub.configuredLibrary == null) {
            askLibrary { arm() }
            return
        }
        val p = Pending(barcode, part, total)
        exec.execute {
            pending = p
            lastWrittenMac = null
            confirmedMac = null
            say(if (total > 1) "Hold del $part af $total mod telefonen" else "Hold chippen mod telefonen", blue)
            runOnUiThread {
                writeBtn.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
            }
            maybeProgram()
        }
    }

    private fun disarm(msg: String?) {
        exec.execute {
            pending = null
            askingMac = null
            runOnUiThread {
                writeBtn.visibility = View.VISIBLE
                cancelBtn.visibility = View.GONE
            }
            if (msg != null) say(msg, sub)
        }
    }

    /** Kører på exec: skriv til tagget ved telefonen, hvis der venter en programmering */
    private fun maybeProgram() {
        val p = pending ?: return
        val t = tag ?: return
        val i = info ?: return
        if (!present) return
        // Samme chip som lige er skrevet: vent på den næste (ellers ville del 2 lande oven i del 1)
        if (i.mac == lastWrittenMac) return
        val c = i.content
        val same = c != null && i.danish && c.barcode == p.barcode && c.seqNum == p.part && c.numItems == p.total
        if (!i.blank && !same && confirmedMac != i.mac) {
            if (askingMac == i.mac) return
            askingMac = i.mac
            val what = if (c != null && i.danish && c.barcode.isNotEmpty()) "materialenummer ${c.barcode}" else "andet indhold"
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Overskriv?")
                    .setMessage("Chippen har allerede $what. Vil du overskrive den med ${p.barcode}?")
                    .setPositiveButton("Overskriv") { _, _ -> exec.execute { confirmedMac = i.mac; askingMac = null; maybeProgram() } }
                    .setNegativeButton("Annuller") { _, _ -> exec.execute { askingMac = null }; disarm("Ikke overskrevet") }
                    .setCancelable(false)
                    .show()
            }
            return
        }
        try {
            val lib = Hub.configuredLibrary ?: throw IllegalStateException("biblioteksnummer er ikke sat")
            // Samme feltrækkefølge som Ciceros egen skrivning (go-feig): antal dele, derefter delnummer
            val bytes = TagContent.build(p.barcode, p.total, p.part, Hub.DEFAULT_COUNTRY, lib)
            t.writeBlocks(bytes)
            val check = TagContent.parse(t.readBlocks(0, 8))
            if (check.barcode != p.barcode) throw IllegalStateException("kontrol-læsning viste '${check.barcode}'")
            t.writeAfi(AFI_ON)
            lastWrittenMac = i.mac
            info = readInfo(t)
            showInfo()
            LogBuf.add("Værktøj: programmeret ${p.barcode} del ${p.part}/${p.total}")
            vibrate(longArrayOf(0, 110, 80, 110))
            if (p.part < p.total) {
                p.part++
                runOnUiThread { part = p.part; updateParts() }
                say("Del ${p.part - 1} af ${p.total} skrevet ✓ – hold del ${p.part} mod telefonen", green)
            } else {
                say("Færdig ✓ ${p.barcode} er skrevet, og alarmen er slået til", green)
                pending = null
                runOnUiThread {
                    writeBtn.visibility = View.VISIBLE
                    cancelBtn.visibility = View.GONE
                    part = 1
                    updateParts()
                }
            }
        } catch (e: Exception) {
            LogBuf.add("Værktøj: programmering fejlede: ${e.message}")
            say("Skrivning fejlede – løft chippen og hold den mod telefonen igen", red)
            vibrate(longArrayOf(0, 80, 70, 80, 70, 80))
        }
    }

    // ---------- Biblioteksnummer ----------

    @Volatile private var suggested: String? = null

    private fun suggestLibrary(lib: String) {
        if (suggested == null) suggested = lib
    }

    private fun askLibrary(then: (() -> Unit)?) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Hub.configuredLibrary ?: suggested ?: Hub.lastLibrary ?: "")
            setSelection(text.length)
            textSize = 22f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Biblioteksnummer")
            .setMessage("Skrives på nye chips sammen med landekoden DK. Forslaget er det nummer, app'en har set på jeres tags.")
            .setView(box)
            .setPositiveButton("Gem") { _, _ ->
                val v = field.text.toString().trim()
                if (v.isEmpty() || v.length > 9 || !v.all { it.isLetterOrDigit() }) {
                    say("Biblioteksnummeret skal være 1-9 tal/bogstaver", orange)
                } else {
                    prefs().edit().putString(PREF_LIBRARY, v).apply()
                    Hub.configuredLibrary = v
                    updateLibraryText()
                    LogBuf.add("Biblioteksnummer sat til DK-$v")
                    then?.invoke()
                }
            }
            .setNegativeButton("Annuller", null)
            .show()
    }

    // ---------- Livscyklus ----------

    private fun vibrate(pattern: LongArray) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    /** Ethvert tryk tæller som brug, så app'en ikke låser midt i arbejdet */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Hub.lastActivity = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            // 5 minutter uden brug: luk værktøjet, så app'en låser som normalt
            if (SystemClock.elapsedRealtime() - Hub.lastActivity >= MainActivity.LOCK_AFTER_MS) { finish(); return }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Hub.locked) { finish(); return }
        val opts = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) }
        nfc?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            opts,
        )
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 10_000)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
        handler.removeCallbacks(idleCheck)
    }

    override fun onDestroy() {
        super.onDestroy()
        LogBuf.add("Værktøj til tags lukket")
        exec.execute { tag?.close() }
        exec.shutdown()
    }
}
