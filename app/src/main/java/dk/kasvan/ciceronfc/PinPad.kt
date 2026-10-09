package dk.kasvan.ciceronfc

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Stort tastatur til lånerens pinkode. Dækker hele skærmen, så låneren hverken ser eller
 * rører resten af Cicero. Koden gemmes ikke – den sendes direkte til Ciceros felt.
 *
 * "Stor" giver ekstra store taster med høj kontrast (gult på sort) til svagtseende.
 */
class PinPad(private val ctx: Context, private val dp: (Int) -> Int) {
    companion object {
        const val MIN_DIGITS = 4
        const val MAX_DIGITS = 16 // FBS 2.0 tillader op til 16 cifre
        private val HC_BG = Color.BLACK
        private val HC_FG = Color.parseColor("#FFD400")
    }

    var onOk: ((String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onKeyboard: (() -> Unit)? = null
    var onFlip: (() -> Unit)? = null
    var onBigChanged: ((Boolean) -> Unit)? = null

    private val digits = StringBuilder()
    private val keys = ArrayList<TextView>()
    private val keyBgs = ArrayList<GradientDrawable>()
    private val smallButtons = ArrayList<TextView>()
    private lateinit var okKey: TextView
    private lateinit var okBg: GradientDrawable
    private lateinit var bigButton: TextView

    // Farver fra Cicero (lys/mørk)
    private var cBg = Color.DKGRAY
    private var cText = Color.WHITE
    private var cSub = Color.LTGRAY
    private var cKey = Color.GRAY
    private var cBlue = Color.BLUE

    private var big = false

    private val title = TextView(ctx).apply {
        text = "Indtast pinkode"
        gravity = Gravity.CENTER
    }
    private val dots = TextView(ctx).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        minHeight = dp(64)
    }
    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    val view = FrameLayout(ctx).apply {
        visibility = View.GONE
        isClickable = true // tryk må ikke gå igennem til Cicero
    }

    val isShowing get() = view.visibility == View.VISIBLE

    init {
        box.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        })
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("←", "0", "OK"))
        for (r in rows) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (label in r) {
                val bg = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
                val key = TextView(ctx).apply {
                    text = label
                    gravity = Gravity.CENTER
                    background = bg
                    contentDescription = if (label == "←") "Slet" else label
                    setOnClickListener { v -> press(label, v) }
                }
                row.addView(key, LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
                if (label == "OK") { okKey = key; okBg = bg } else { keys += key; keyBgs += bg }
            }
            box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // Til personalet: små knapper nederst, delt ligeligt i bredden
        val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in listOf<Pair<String, () -> Unit>>(
            "Annuller" to { hide(); onCancel?.invoke() },
            "Tastatur" to { hide(); onKeyboard?.invoke() },
            "Vend" to { onFlip?.invoke() },
            "Stor" to { setBig(!big); onBigChanged?.invoke(big) },
        )) {
            val b = TextView(ctx).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), dp(12), dp(4), dp(12))
                setOnClickListener { action() }
            }
            if (label == "Stor") bigButton = b
            smallButtons += b
            bottom.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        view.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        style()
    }

    fun setColors(bg: Int, text: Int, sub: Int, key: Int, blue: Int) {
        cBg = bg; cText = text; cSub = sub; cKey = key; cBlue = blue
        style()
    }

    fun setBig(on: Boolean) {
        big = on
        style()
    }

    fun show(flipped: Boolean) {
        digits.setLength(0)
        setFlipped(flipped)
        update()
        view.visibility = View.VISIBLE
    }

    fun setFlipped(flipped: Boolean) {
        box.rotation = if (flipped) 180f else 0f
    }

    fun hide() {
        digits.setLength(0)
        update()
        view.visibility = View.GONE
    }

    private fun press(label: String, v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (label) {
            "←" -> if (digits.isNotEmpty()) digits.setLength(digits.length - 1)
            "OK" -> if (digits.length >= MIN_DIGITS) {
                val pin = digits.toString()
                hide()
                onOk?.invoke(pin)
                return
            }
            else -> if (digits.length < MAX_DIGITS) digits.append(label)
        }
        update()
    }

    /** Normal: Ciceros farver. Stor: gult på sort, store fede tal og høje taster. */
    private fun style() {
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // Fylder bredden på små telefoner; i normal størrelse bliver den ikke kæmpestor på store
        val w = if (big) ctx.resources.displayMetrics.widthPixels else minOf(ctx.resources.displayMetrics.widthPixels, dp(420))
        box.layoutParams = (box.layoutParams as FrameLayout.LayoutParams).apply { width = w }

        // Store taster, men aldrig højere end at alt kan være på skærmen
        val dm = ctx.resources.displayMetrics
        val keyH = if (big) ((dm.heightPixels / dm.density - 280) / 4 - 12).toInt().coerceIn(64, 100) else 76
        view.setBackgroundColor(if (big) HC_BG else cBg)
        title.setTextColor(if (big) Color.WHITE else cText)
        title.textSize = if (big) 30f else 24f
        title.typeface = if (big) bold else medium
        dots.setTextColor(if (big) HC_FG else cText)

        for ((i, k) in keys.withIndex()) {
            k.setTextColor(if (big) HC_FG else cText)
            k.textSize = if (big) 50f else 32f
            k.typeface = if (big) bold else medium
            keyBgs[i].setColor(if (big) HC_BG else cKey)
            keyBgs[i].setStroke(if (big) dp(3) else 0, HC_FG)
            (k.layoutParams as LinearLayout.LayoutParams).height = dp(keyH)
        }
        okKey.textSize = if (big) 36f else 26f
        okKey.typeface = if (big) bold else medium
        (okKey.layoutParams as LinearLayout.LayoutParams).height = dp(keyH)
        okBg.setStroke(if (big) dp(3) else 0, HC_FG)

        smallButtons.forEach {
            it.setTextColor(if (big) Color.WHITE else cSub)
            it.textSize = if (big) 17f else 15f
        }
        bigButton.text = if (big) "Normal" else "Stor"
        box.requestLayout()
        update()
    }

    private fun update() {
        dots.text = "●".repeat(digits.length)
        // Mindre prikker ved lange koder, så de altid kan stå på én linje
        val n = digits.length
        dots.textSize = when {
            n <= 8 -> if (big) 46f else 36f
            n <= 12 -> if (big) 34f else 26f
            else -> if (big) 26f else 20f
        }
        dots.letterSpacing = if (n <= 8) 0.3f else 0.12f
        if (!::okBg.isInitialized) return
        val ready = digits.length >= MIN_DIGITS
        if (big) {
            okBg.setColor(if (ready) HC_FG else HC_BG)
            okKey.setTextColor(if (ready) Color.BLACK else HC_FG)
        } else {
            okBg.setColor(if (ready) cBlue else cKey)
            okKey.setTextColor(Color.WHITE)
        }
        okKey.alpha = if (ready) 1f else 0.45f
    }
}
