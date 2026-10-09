package dk.kasvan.ciceronfc

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
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

        /** App'en låser sig efter 5 minutter uden brug. */
        const val LOCK_AFTER_MS = 5 * 60 * 1000L
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
    private val orange = Color.parseColor("#DD6B20")
    private val gray = Color.parseColor("#718096")
    private val green = Color.parseColor("#2F855A")
    private val red = Color.parseColor("#C53030")

    private lateinit var pal: Palette
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var rfidIcon: ImageView
    private lateinit var rfidCircle: GradientDrawable
    private lateinit var status: TextView
    private lateinit var pill: LinearLayout
    private lateinit var pillBg: GradientDrawable
    private lateinit var pillIcon: ImageView
    private lateinit var pillText: TextView
    private lateinit var detail: TextView
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
    private var pendingCamera: PermissionRequest? = null
    private val cameraRequestCode = 42
    private val unlockRequestCode = 43
    private lateinit var lockView: LinearLayout
    private lateinit var lockIcon: ImageView
    private lateinit var lockTitle: TextView
    private lateinit var lockHint: TextView
    private var authInProgress = false
    private var lastAuthEnded = 0L

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
        // Miniaturen i "seneste apps" viser ikke Cicero (almindelige skærmbilleder virker stadig)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        buildLayout()
        setupWeb(savedInstanceState)
        LogBuf.listener = { runOnUiThread { refreshLog() } }
        Hub.statusListener = { runOnUiThread { refreshStatus() } }
        refreshStatus()
        if (!isDeviceSecure()) {
            Hub.locked = false
            if (savedInstanceState == null) warnNoScreenLock()
        }
        refreshLockUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildLayout() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), 0, dp(3))
            minimumHeight = dp(34)
        }

        rfidCircle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        rfidIcon = ImageView(this).apply {
            background = rfidCircle
            setPadding(dp(5), dp(5), dp(5), dp(5))
            contentDescription = "RFID-status"
        }
        bar.addView(rfidIcon, LinearLayout.LayoutParams(dp(26), dp(26)))

        // Lille farvet pille med klokke: grøn = alarm fra, orange = alarm til, rød = fejl
        pillBg = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
            setPadding(dp(8), dp(3), dp(10), dp(3))
            visibility = View.GONE
            setOnClickListener { openNfcSettingsIfOff() }
        }
        pillIcon = ImageView(this).apply { imageTintList = ColorStateList.valueOf(Color.WHITE) }
        pill.addView(pillIcon, LinearLayout.LayoutParams(dp(15), dp(15)))
        pillText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(5), 0, 0, 0)
        }
        pill.addView(pillText)
        bar.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })

        status = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { openNfcSettingsIfOff() }
        }
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        detail = TextView(this).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(4), 0, dp(2), 0)
        }
        bar.addView(detail)

        menuBtn = TextView(this).apply {
            text = "⋮"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(12), 0)
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
        lockView = buildLockView()
        val container = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(lockView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(container)
        applyPalette()
    }

    // ---------- Lås ----------

    private fun buildLockView(): LinearLayout {
        lockIcon = ImageView(this).apply { setImageResource(R.drawable.ic_lock) }
        lockTitle = TextView(this).apply {
            text = "Cicero NFC er låst"
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        }
        lockHint = TextView(this).apply {
            text = "Tryk for at låse op"
            textSize = 14f
            gravity = Gravity.CENTER
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true // tryk må ikke gå igennem til Cicero
            visibility = View.GONE
            setOnClickListener { authenticate() }
            addView(lockIcon, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(lockTitle)
            addView(lockHint)
        }
    }

    private fun isDeviceSecure(): Boolean = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun warnNoScreenLock() {
        AlertDialog.Builder(this)
            .setTitle("Ingen skærmlås")
            .setMessage("Telefonen har ingen skærmlås (fingeraftryk eller pinkode), så Cicero NFC kan ikke låse sig selv. Slå skærmlås til i telefonens indstillinger.")
            .setPositiveButton("Indstillinger") { _, _ -> startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun lock() {
        if (!isDeviceSecure()) return
        if (!Hub.locked) LogBuf.add("Låst")
        Hub.locked = true
        Hub.onLocked()
        refreshLockUi()
    }

    private fun unlock() {
        Hub.locked = false
        Hub.lastActivity = SystemClock.elapsedRealtime()
        LogBuf.add("Låst op")
        refreshLockUi()
    }

    private fun refreshLockUi() {
        val locked = Hub.locked
        lockView.visibility = if (locked) View.VISIBLE else View.GONE
        // Cicero skjules helt bag låsen – også for skærmlæser og tastatur
        root.visibility = if (locked) View.INVISIBLE else View.VISIBLE
        root.importantForAccessibility =
            if (locked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (locked) {
            web.clearFocus()
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        }
        // Skærmen holdes kun tændt, mens app'en er låst op; låst må telefonen slukke som normalt
        if (locked) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Telefonens egen skærmlås: fingeraftryk, ansigt eller pinkode. App'en ser aldrig selve koden. */
    private fun authenticate() {
        if (authInProgress) return
        if (!isDeviceSecure()) { unlock(); return }
        authInProgress = true
        if (Build.VERSION.SDK_INT >= 30) {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Lås Cicero NFC op")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(
                CancellationSignal(), mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        authEnded()
                        unlock()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        authEnded()
                    }
                },
            )
        } else {
            @Suppress("DEPRECATION")
            val i = getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent("Lås Cicero NFC op", null)
            if (i == null) {
                authEnded()
                unlock()
                return
            }
            @Suppress("DEPRECATION")
            startActivityForResult(i, unlockRequestCode)
        }
    }

    private fun authEnded() {
        authInProgress = false
        lastAuthEnded = SystemClock.elapsedRealtime()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != unlockRequestCode) return
        authEnded()
        if (resultCode == RESULT_OK) unlock()
    }

    /** Ethvert tryk tæller som brug */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!Hub.locked) Hub.lastActivity = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    private val lockTicker = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (!Hub.locked && isDeviceSecure() &&
                SystemClock.elapsedRealtime() - Hub.lastActivity >= LOCK_AFTER_MS
            ) {
                lock()
            }
            handler.postDelayed(this, 10_000)
        }
    }

    private fun applyPalette() {
        root.setBackgroundColor(pal.logBg)
        progress.progressTintList = ColorStateList.valueOf(pal.blue)
        progress.progressBackgroundTintList = ColorStateList.valueOf(pal.line)
        logView.setTextColor(pal.logText)
        lockView.setBackgroundColor(pal.bar)
        lockIcon.imageTintList = ColorStateList.valueOf(pal.text)
        lockTitle.setTextColor(pal.text)
        lockHint.setTextColor(pal.sub)
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
        if (isDeviceSecure()) m.menu.add(0, 6, 3, "Lås nu")
        m.menu.add(0, 4, 4, "Om Cicero NFC")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> toggleLog()
                2 -> shareLog()
                3 -> web.reload()
                4 -> showAbout()
                6 -> lock()
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
                    "Hold bogen mod telefonen, til den vibrerer og pillen viser alarm fra/til.\n\n" +
                    "App'en låser sig efter 5 minutter uden brug og låses op med telefonens fingeraftryk eller pinkode.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupWeb(saved: Bundle?) {
        // Fjernfejlsøgning (chrome://inspect) kun i fejlsøgnings-builds, aldrig i den rigtige app
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            // Ciceros sikre side må ikke hente usikre scripts. localhost (vores egen server) er undtaget af browseren.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Links der åbner et nyt vindue (fx Netpunkt, Google) åbnes i Chrome i stedet for inde i app'en
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            // Ciceros kamerascanning skal kunne vise kamerabilledet uden ekstra tryk
            mediaPlaybackRequiresUserGesture = false
            // Pinch-zoom som i Chrome, uden de gamle +/- knapper
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            // Almindelige web-adresser (Cicero, login) bliver i app'en; andet (mailto:, tel:, apps) åbnes udenfor
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: return true
                if (scheme == "http" || scheme == "https") return false
                openExternal(request.url.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                handler.postDelayed({ detectCiceroTheme() }, 500)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val url = request.url.toString()
                // Når Cicero selv lukker /events/ ved skærmskift, melder browseren ERR_FAILED – det er ikke en fejl
                val closedEvents = url.contains("/events") && (error.description?.contains("ERR_FAILED") == true)
                if (!closedEvents && (request.isForMainFrame || url.contains(":${Server.PORT}"))) {
                    // Kun værtsnavnet – fulde adresser kan indeholde lånerdata
                    LogBuf.add("Browser-fejl: ${error.description} (${request.url.host})")
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val msg = m.message() ?: ""
                // Kun beskeder om RFID-forbindelsen – andre fejlbeskeder fra Cicero kan indeholde lånerdata
                val important = msg.contains("localhost") || msg.contains("${Server.PORT}") ||
                    msg.contains("rfid", ignoreCase = true)
                if (important) LogBuf.add("Cicero-konsol: ${msg.take(400)}")
                return true
            }

            // Cicero beder om kameraet (scan lånernr.). Kun Ciceros egen side får lov.
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val host = request.origin?.host ?: ""
                val https = request.origin?.scheme == "https"
                if (!wantsCamera || !https || !(host == "systematic.com" || host.endsWith(".systematic.com"))) {
                    request.deny()
                    return
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pendingCamera?.deny()
                    pendingCamera = request
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraRequestCode)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingCamera === request) pendingCamera = null
            }

            // Android viser ellers en stor grå afspil-knap, mens kameraet starter/skifter.
            // En ensfarvet flade i Ciceros farve kan ikke forvrænges, når den strækkes.
            override fun getDefaultVideoPoster(): Bitmap =
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(pal.bar) }

            // Nyt vindue (target=_blank / window.open): Cicero-sider åbnes her, alt andet i Chrome
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val catcher = WebView(this@MainActivity)
                catcher.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        val u = r.url
                        val h = u.host ?: ""
                        if (u.scheme == "https" && (h == "systematic.com" || h.endsWith(".systematic.com"))) {
                            web.loadUrl(u.toString())
                        } else {
                            openExternal(u.toString())
                        }
                        v.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = catcher
                resultMsg.sendToTarget()
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        if (saved != null) web.restoreState(saved) else web.loadUrl(START_URL)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != cameraRequestCode) return
        val req = pendingCamera ?: return
        pendingCamera = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
            req.deny()
            LogBuf.add("Kamera: tilladelse afvist")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        // Lås, hvis app'en ikke har været brugt i 5 minutter (også mens den var i baggrunden)
        if (isDeviceSecure()) {
            if (!Hub.locked && SystemClock.elapsedRealtime() - Hub.lastActivity >= LOCK_AFTER_MS) lock()
            refreshLockUi()
            if (Hub.locked && !authInProgress && SystemClock.elapsedRealtime() - lastAuthEnded > 1500) authenticate()
        } else if (Hub.locked) {
            Hub.locked = false
            refreshLockUi()
        }
        handler.removeCallbacks(lockTicker)
        handler.postDelayed(lockTicker, 10_000)
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
        handler.removeCallbacks(lockTicker)
        nfc?.disableReaderMode(this)
        CookieManager.getInstance().flush()
    }

    override fun onTagDiscovered(tag: Tag) {
        Hub.onTagDiscovered(tag)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Låst: tilbage-knappen må ikke styre Cicero bag låsen
        if (Hub.locked) {
            moveTaskToBack(true)
            return
        }
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    // ---------- Bjælken ----------

    private fun refreshStatus() {
        val n = nfc
        val listening = Server.clientCount() > 0
        val problem = when {
            n == null -> "Ingen NFC"
            !n.isEnabled -> "NFC slået fra – tryk her"
            Server.bindError != null -> Server.bindError
            else -> null
        }
        val fb = Hub.feedback

        // Bjælken forbliver neutral; en lille farvet pille bærer budskabet
        var pillColor: Int? = null
        var pillIconRes = 0
        var pillWords = ""
        var text = ""
        if (problem != null) {
            pillColor = red; pillIconRes = R.drawable.ic_warn; pillWords = problem
        } else {
            when (fb) {
                Hub.Fb.IDLE -> text = if (listening) "Klar" else ""
                Hub.Fb.READ -> text = Hub.statusText
                Hub.Fb.SEEN -> { pillColor = gray; pillIconRes = R.drawable.ic_check; pillWords = "Læst" }
                Hub.Fb.ALARM_OFF -> { pillColor = green; pillIconRes = R.drawable.ic_bell_off; pillWords = "Alarm fra" }
                Hub.Fb.ALARM_ON -> { pillColor = orange; pillIconRes = R.drawable.ic_bell; pillWords = "Alarm til" }
                Hub.Fb.WRITTEN -> { pillColor = green; pillIconRes = R.drawable.ic_check; pillWords = "Skrevet" }
                Hub.Fb.ERROR -> { pillColor = red; pillIconRes = R.drawable.ic_warn; pillWords = Hub.statusText }
            }
        }

        bar.setBackgroundColor(pal.bar)
        setSystemBar(pal.bar, light = pal.lightBar)
        menuBtn.setTextColor(pal.sub)

        if (pillColor != null) {
            pillBg.setColor(pillColor)
            pillIcon.setImageResource(pillIconRes)
            pillText.text = pillWords
            pill.visibility = View.VISIBLE
        } else {
            pill.visibility = View.GONE
        }
        status.text = text
        status.setTextColor(pal.text)
        detail.text = if (problem == null && fb != Hub.Fb.IDLE) Hub.statusDetail else ""
        detail.setTextColor(pal.sub)

        // Samme ikon som Ciceros egen RFID-knap: blå når Cicero lytter, grå og overstreget når ikke
        rfidIcon.setImageResource(if (listening) R.drawable.ic_rfid_on else R.drawable.ic_rfid_off)
        rfidCircle.setColor(if (listening) pal.blue else pal.offCircle)
        rfidIcon.imageTintList = ColorStateList.valueOf(if (listening) Color.WHITE else pal.text)

        // Kort, svagt farveblink over Cicero ved alarmskift og fejl
        val seq = Hub.feedbackSeq
        if (seq != lastSeq) {
            lastSeq = seq
            val flashColor = when (fb) {
                Hub.Fb.ALARM_OFF, Hub.Fb.WRITTEN -> green
                Hub.Fb.ALARM_ON -> orange
                Hub.Fb.ERROR -> red
                else -> null
            }
            if (flashColor != null) {
                flash.setBackgroundColor(flashColor)
                flash.animate().cancel()
                flash.alpha = 0.3f
                flash.animate().alpha(0f).setStartDelay(250).setDuration(600).start()
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

    /** Åbn uden for app'en (Chrome, mail, telefon …). Web-indhold får aldrig lov at starte en bestemt app direkte. */
    private fun openExternal(url: String) {
        try {
            val i = if (url.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            }
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            LogBuf.add("Kunne ikke åbne link udenfor app'en")
        } catch (e: Exception) {
            LogBuf.add("Ugyldigt link: ${e.message}")
        }
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
