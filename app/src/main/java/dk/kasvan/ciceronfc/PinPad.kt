package dk.kasvan.ciceronfc

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Stort tastatur til lånerens pinkode. Dækker hele skærmen, så låneren hverken ser eller
 * rører resten af Cicero. Koden gemmes ikke – den sendes direkte til Ciceros felt.
 */
class PinPad(private val ctx: Context, private val dp: (Int) -> Int) {
    companion object {
        const val MIN_DIGITS = 4
        const val MAX_DIGITS = 8
    }

    var onOk: ((String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onKeyboard: (() -> Unit)? = null
    var onFlip: (() -> Unit)? = null

    private val digits = StringBuilder()
    private val keys = ArrayList<TextView>()
    private val keyBgs = ArrayList<GradientDrawable>()
    private val smallButtons = ArrayList<TextView>()
    private lateinit var okKey: TextView
    private lateinit var okBg: GradientDrawable
    private var blue = Color.BLUE
    private var keyBg = Color.GRAY

    private val title = TextView(ctx).apply {
        text = "Indtast din pinkode"
        textSize = 24f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }
    private val dots = TextView(ctx).apply {
        textSize = 36f
        gravity = Gravity.CENTER
        letterSpacing = 0.3f
        minHeight = dp(64)
    }
    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(16), dp(24), dp(16))
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
                    textSize = if (label == "OK") 26f else 32f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        // Til personalet: små knapper nederst
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((label, action) in listOf<Pair<String, () -> Unit>>(
            "Annuller" to { hide(); onCancel?.invoke() },
            "Tastatur" to { hide(); onKeyboard?.invoke() },
            "Vend" to { onFlip?.invoke() },
        )) {
            val b = TextView(ctx).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { action() }
            }
            smallButtons += b
            bottom.addView(b)
        }
        box.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        // Fylder bredden på små telefoner, men bliver ikke kæmpestor på store
        val w = minOf(ctx.resources.displayMetrics.widthPixels, dp(420))
        view.addView(box, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        update()
    }

    fun setColors(bg: Int, text: Int, sub: Int, key: Int, blue: Int) {
        this.blue = blue
        this.keyBg = key
        view.setBackgroundColor(bg)
        title.setTextColor(text)
        dots.setTextColor(text)
        keys.forEach { it.setTextColor(text) }
        keyBgs.forEach { it.setColor(key) }
        smallButtons.forEach { it.setTextColor(sub) }
        update()
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

    private fun update() {
        dots.text = "●".repeat(digits.length)
        val ready = digits.length >= MIN_DIGITS
        if (::okBg.isInitialized) {
            okBg.setColor(if (ready) blue else keyBg)
            okKey.setTextColor(Color.WHITE)
            okKey.alpha = if (ready) 1f else 0.45f
        }
    }
}
