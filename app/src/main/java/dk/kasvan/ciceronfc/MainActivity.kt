package dk.kasvan.ciceronfc

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    companion object {
        const val START_URL = "https://cicero.systematic.com/"
    }

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView
    private var nfc: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.start(applicationContext)
        nfc = NfcAdapter.getDefaultAdapter(this)
        buildLayout()
        setupWeb(savedInstanceState)
        LogBuf.listener = { runOnUiThread { refreshLog() } }
        Hub.statusListener = { runOnUiThread { refreshStatus() } }
        refreshLog()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E2A38"))
            setPadding(dp(10), 0, 0, 0)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { openNfcSettingsIfOff() }
        }
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barButton("Log") { toggleLog() })
        bar.addView(barButton("Del") { shareLog() })
        bar.addView(barButton("↻") { web.reload() })

        web = WebView(this)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#DDDDDD"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            visibility = View.GONE
            addView(logView)
        }

        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.4).toInt()),
        )
        setContentView(root)
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

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val url = request.url.toString()
                if (request.isForMainFrame || url.contains(":${Server.PORT}")) {
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
    }

    override fun onPause() {
        super.onPause()
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

    private fun refreshStatus() {
        val n = nfc
        status.text = when {
            n == null -> "Denne telefon har ingen NFC"
            !n.isEnabled -> "NFC er slået fra – tryk her for at slå det til"
            !Server.listening -> "Starter…"
            else -> Hub.statusText
        }
    }

    private fun openNfcSettingsIfOff() {
        val n = nfc ?: return
        if (!n.isEnabled) startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
    }

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
