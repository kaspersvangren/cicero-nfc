package dk.kasvan.ciceronfc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    companion object {
        const val START_URL = "https://cicero.systematic.com/"
    }

    /** Cicero Mobiles egne farver (målt på skærmbilleder af Cicero). */
    private class Palette(
        val bar: Int, val text: Int, val sub: Int, val blue: Int, val offCircle: Int,
        val logBg: Int, val logText: Int, val line: Int, val lightBar: Boolean,
    )

    private val lightPalette = Palette(
        bar = Color.parseColor("#F2F5F7"), text = Color.parseColor("#404040"), sub = Color.parseColor("#767676"),
        blue = Color.parseColor("#0078D3"), offCircle = Color.parseColor("#E0E0E0"),
        logBg = Color.WHITE, logText = Color.parseColor("#404040"), line = Color.parseColor("#EBEBEB"),
        lightBar = true,
    )
    private val darkPalette = Palette(
        bar = Color.parseColor("#2E2E2E"), text = Color.parseColor("#F2F2F2"), sub = Color.parseColor("#C6C6C6"),
        blue = Color.parseColor("#3098E8"), offCircle = Color.parseColor("#4B4B4B"),
        logBg = Color.parseColor("#2E2E2E"), logText = Color.parseColor("#E0E0E0"), line = Color.parseColor("#424242"),
        lightBar = false,
    )
    private val amber = Color.parseColor("#B7791F")
    private val green = Color.parseColor("#2F855A")
    private val red = Color.parseColor("#C53030")

    private lateinit var pal: Palette
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var rfidIcon: ImageView
    private lateinit var rfidCircle: GradientDrawable
    private lateinit var status: TextView
    private lateinit var menuBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var flash: View
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var root: LinearLayout
    private lateinit var divider: View
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var nfc: NfcAdapter? = null
    private var lastSeq = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.start(applicationContext)
        nfc = NfcAdapter.getDefaultAdapter(this)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val saved = getSharedPreferences("ui", MODE_PRIVATE).getString("ciceroTheme", null)
        pal = when (saved) {
            "dark" -> darkPalette
            "light" -> lightPalette
            else -> if (night) darkPalette else lightPalette
        }
        // Skærmen slukker ikke, mens app'en er åben ved skranken
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildLayout()
        setupWeb(savedInstanceState)
        LogBuf.listener = { runOnUiThread { refreshLog() } }
        Hub.statusListener = { runOnUiThread { refreshStatus() } }
        refreshStatus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildLayout() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(4), dp(6))
            minimumHeight = dp(52)
        }

        rfidCircle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        rfidIcon = ImageView(this).apply {
            background = rfidCircle
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = "RFID-status"
        }
        bar.addView(rfidIcon, LinearLayout.LayoutParams(dp(38), dp(38)))

        status = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener { openNfcSettingsIfOff() }
        }
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        menuBtn = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(4), dp(14), dp(4))
            contentDescription = "Menu"
            setOnClickListener { showMenu(it) }
        }
        bar.addView(menuBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        web = WebView(this)
        // Farvet blink hen over Cicero ved færdig/fejl. Ikke klikbar, så tryk går igennem.
        flash = View(this).apply { alpha = 0f }
        val webBox = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(flash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(logView)
        }
        divider = View(this)

        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        root.addView(webBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.4).toInt()),
        )
        setContentView(root)
        applyPalette()
    }

    private fun applyPalette() {
        root.setBackgroundColor(pal.logBg)
        progress.progressTintList = ColorStateList.valueOf(pal.blue)
        progress.progressBackgroundTintList = ColorStateList.valueOf(pal.line)
        logView.setTextColor(pal.logText)
        logScroll.setBackgroundColor(pal.logBg)
        divider.setBackgroundColor(pal.line)
        refreshStatus()
    }

    /**
     * Cicero har sin egen mørke tilstand (Udseende), uafhængig af telefonens.
     * Vi aflæser farven øverst på Cicero-siden og følger den.
     */
    private val themeProbe = """
        (function () {
          function bg(e) {
            while (e) {
              var c = getComputedStyle(e).backgroundColor;
              var m = c && c.match(/[\d.]+/g);
              if (m && !(m.length > 3 && +m[3] === 0)) return 0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2];
              e = e.parentElement;
            }
            return -1;
          }
          var l = bg(document.elementFromPoint(window.innerWidth / 2, 4));
          if (l < 0) l = bg(document.body);
          return l < 0 ? 'unknown' : (l < 128 ? 'dark' : 'light');
        })()
    """.trimIndent()

    private fun detectCiceroTheme() {
        web.evaluateJavascript(themeProbe) { result ->
            val theme = result?.trim('"') ?: return@evaluateJavascript
            val newPal = when (theme) {
                "dark" -> darkPalette
                "light" -> lightPalette
                else -> return@evaluateJavascript
            }
            if (newPal !== pal) {
                pal = newPal
                getSharedPreferences("ui", MODE_PRIVATE).edit().putString("ciceroTheme", theme).apply()
                applyPalette()
            }
        }
    }

    private val themeTicker = object : Runnable {
        override fun run() {
            if (!resumed) return
            detectCiceroTheme()
            handler.postDelayed(this, 3000)
        }
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, if (logScroll.visibility == View.VISIBLE) "Skjul log" else "Vis log")
        m.menu.add(0, 2, 1, "Del log")
        m.menu.add(0, 3, 2, "Genindlæs Cicero")
        m.menu.add(0, 4, 3, "Om Cicero NFC")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> toggleLog()
                2 -> shareLog()
                3 -> web.reload()
                4 -> showAbout()
            }
            true
        }
        m.show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Cicero NFC ${BuildConfigInfo.version(this)}")
            .setMessage(
                "Bruger telefonens NFC som RFID-læser i Cicero Mobile.\n\n" +
                    "Opsætning i Cicero (Enhedsindstillinger → RFID scanner):\n" +
                    "Deichman · localhost · 1667\n" +
                    "Slå \"RFID-scanner til som standard\" til.\n\n" +
                    "Hold bogen mod telefonen, til den bipper og bjælken bliver grøn.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupWeb(saved: Bundle?) {
        WebView.setWebContentsDebuggingEnabled(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            // Ser ud som almindelig Chrome, ikke som en indlejret browser
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

            override fun onPageFinished(view: WebView, url: String) {
                handler.postDelayed({ detectCiceroTheme() }, 500)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val url = request.url.toString()
                // Når Cicero selv lukker /events/ ved skærmskift, melder browseren ERR_FAILED – det er ikke en fejl
                val closedEvents = url.contains("/events") && (error.description?.contains("ERR_FAILED") == true)
                if (!closedEvents && (request.isForMainFrame || url.contains(":${Server.PORT}"))) {
                    LogBuf.add("Browser-fejl: ${error.description} ($url)")
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val msg = m.message() ?: ""
                val important = m.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                    msg.contains("localhost") || msg.contains("${Server.PORT}") ||
                    msg.contains("rfid", ignoreCase = true)
                if (important) LogBuf.add("Cicero-konsol: ${msg.take(400)}")
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        if (saved != null) web.restoreState(saved) else web.loadUrl(START_URL)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        val opts = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) }
        nfc?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            opts,
        )
        refreshStatus()
        resumed = true
        handler.removeCallbacks(themeTicker)
        handler.postDelayed(themeTicker, 1500)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(themeTicker)
        nfc?.disableReaderMode(this)
        CookieManager.getInstance().flush()
    }

    override fun onTagDiscovered(tag: Tag) {
        Hub.onTagDiscovered(tag)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    // ---------- Bjælken ----------

    private fun refreshStatus() {
        val n = nfc
        val listening = Server.clientCount() > 0
        val problem = when {
            n == null -> "Denne telefon har ingen NFC"
            !n.isEnabled -> "NFC er slået fra – tryk her"
            !Server.listening -> "Starter…"
            else -> null
        }
        val fb = Hub.feedback
        val text = when {
            problem != null -> problem
            fb == Hub.Fb.IDLE && listening -> "Klar – hold en bog mod telefonen"
            fb == Hub.Fb.IDLE -> "Cicero lytter ikke – aktiver RFID"
            else -> Hub.statusText
        }
        val bg = when {
            problem != null -> red
            fb == Hub.Fb.READ -> amber
            fb == Hub.Fb.DONE -> green
            fb == Hub.Fb.ERROR -> red
            else -> pal.bar
        }
        val onColor = bg != pal.bar
        status.text = text
        status.setTextColor(if (onColor) Color.WHITE else pal.text)
        menuBtn.setTextColor(if (onColor) Color.WHITE else pal.sub)
        bar.setBackgroundColor(bg)
        setSystemBar(bg, light = !onColor && pal.lightBar)

        // Samme ikon som Ciceros egen RFID-knap: blå når Cicero lytter, grå og overstreget når ikke
        rfidIcon.setImageResource(if (listening) R.drawable.ic_rfid_on else R.drawable.ic_rfid_off)
        rfidCircle.setColor(if (listening) pal.blue else pal.offCircle)
        rfidCircle.setStroke(if (onColor) dp(2) else 0, Color.WHITE)
        rfidIcon.imageTintList = ColorStateList.valueOf(if (listening) Color.WHITE else pal.text)

        val seq = Hub.feedbackSeq
        if (seq != lastSeq) {
            lastSeq = seq
            if (fb == Hub.Fb.DONE || fb == Hub.Fb.ERROR) {
                flash.setBackgroundColor(if (fb == Hub.Fb.DONE) green else red)
                flash.animate().cancel()
                flash.alpha = 0.45f
                flash.animate().alpha(0f).setStartDelay(350).setDuration(700).start()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setSystemBar(color: Int, light: Boolean) {
        window.statusBarColor = color
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        } else {
            val v = window.decorView
            v.systemUiVisibility = if (light) {
                v.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                v.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }
    }

    private fun openNfcSettingsIfOff() {
        val n = nfc ?: return
        if (!n.isEnabled) startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
    }

    // ---------- Log ----------

    private fun toggleLog() {
        logScroll.visibility = if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refreshLog()
    }

    private fun refreshLog() {
        if (logScroll.visibility != View.VISIBLE) return
        logView.text = LogBuf.text()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Cicero NFC log")
            putExtra(Intent.EXTRA_TEXT, LogBuf.text())
        }
        startActivity(Intent.createChooser(i, "Del log"))
    }
}
