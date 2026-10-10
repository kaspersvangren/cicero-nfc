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
import android.net.Uri
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
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
        /** Ciceros login-side (Keycloak). Står app'en her, er man ikke logget ind. */
        const val LOGIN_HOST = "auth.cicero.systematic.com"
        const val MAX_PAGE_WAIT_MS = 5000L
        const val QUIET_AFTER_LOAD_MS = 1500L
        const val CICERO_HOST = "cicero.systematic.com"
        const val PREF_PIN_PAD = "pinPad"
        const val PREF_PIN_FLIP = "pinPadFlip"
        const val PREF_PIN_BIG = "pinPadBig"
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
    private lateinit var updateBtn: TextView
    private lateinit var updateBg: GradientDrawable
    private var updateAfterPermission = false
    private val blockedHosts = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Fejl i Ciceros RFID-opsætning (Hostname/Port), opdaget når Cicero prøver at forbinde. */
    private data class SetupError(val text: String, val details: List<String>)
    @Volatile private var setupError: SetupError? = null
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
    private lateinit var pinPad: PinPad
    private lateinit var lockIcon: ImageView
    private lateinit var lockTitle: TextView
    private lateinit var lockHint: TextView
    private var authInProgress = false
    private var lastAuthEnded = 0L
    private var authCancel: CancellationSignal? = null

    // Hvilken side Cicero står på – login-siden betyder "ikke logget ind", og så låser app'en ikke
    private var pageHost: String? = null
    private var pageLoading = false
    private var waitingForPage = false
    /** Låst op, fordi login-siden stod fremme – tidspunktet, så et login uden tryk kan opdages */
    private var loginUnlockAt = -1L
    private val delayedAuth = Runnable {
        waitingForPage = false
        if (Hub.locked && resumed) authenticate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.start(applicationContext)
        Hub.configuredLibrary = getSharedPreferences("ui", MODE_PRIVATE).getString("library", null)
        nfc = NfcAdapter.getDefaultAdapter(this)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val saved = prefs().getString("ciceroTheme", null)
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
        Updater.listener = { runOnUiThread { refreshStatus() } }
        if (savedInstanceState == null) Updater.cleanup(this)
        refreshStatus()
        if (!isDeviceSecure()) {
            Hub.locked = false
            if (savedInstanceState == null) warnNoScreenLock()
        }
        refreshLockUi()
        logStartup()
    }

    /** Første linjer i loggen: hvad der kører – gør det nemt at hjælpe ud fra en delt log */
    private fun logStartup() {
        val date = java.text.SimpleDateFormat("d. MMM yyyy", java.util.Locale("da")).format(java.util.Date())
        LogBuf.add(
            "Cicero NFC ${BuildConfigInfo.version(this)} startet $date · Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
        )
        val wv = try {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
        } catch (_: Exception) { null } ?: "ukendt"
        val n = nfc
        val nfcState = when { n == null -> "mangler"; n.isEnabled -> "til"; else -> "fra" }
        LogBuf.add(
            "Browser: $wv · NFC: $nfcState · skærmlås: ${if (isDeviceSecure()) "ja" else "nej"} · " +
                "pinkode-tastatur: ${if (prefs().getBoolean(PREF_PIN_PAD, true)) "til" else "fra"}" +
                (if (prefs().getBoolean(PREF_PIN_FLIP, false)) ", vendt" else "") +
                (if (prefs().getBoolean(PREF_PIN_BIG, false)) ", stor" else ""),
        )
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
            setOnClickListener { onProblemClick() }
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
            setOnClickListener { onProblemClick() }
        }
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        detail = TextView(this).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(4), 0, dp(2), 0)
        }
        bar.addView(detail)

        // Vises kun, når der findes en nyere version
        updateBg = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
        updateBtn = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = updateBg
            setPadding(dp(10), dp(3), dp(10), dp(3))
            visibility = View.GONE
            setOnClickListener { startUpdate() }
        }
        bar.addView(updateBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })

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
        pinPad = PinPad(this, ::dp).apply {
            onOk = { pin -> fillPin(pin) }
            onCancel = { LogBuf.add("Pinkode annulleret") }
            onKeyboard = { useNormalKeyboardForPin() }
            onFlip = { toggleFlip() }
            onBigChanged = { big ->
                prefs().edit().putBoolean(PREF_PIN_BIG, big).apply()
                LogBuf.add(if (big) "Pinkode-tastatur: stor" else "Pinkode-tastatur: normal")
            }
            setBig(prefs().getBoolean(PREF_PIN_BIG, false))
        }
        val container = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // Pinkode-tastaturet ligger over Cicero, men under låsen
            addView(pinPad.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
            // Versionen står her, så den er nem at finde, når man skal have hjælp
            text = "Tryk for at låse op\n\nCicero NFC ${BuildConfigInfo.version(this@MainActivity)}"
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
            .track()
    }

    // Menu og dialoger ligger i egne vinduer over låseskærmen – de lukkes, når app'en låser
    private var openMenu: PopupMenu? = null
    private val dialogs = ArrayList<AlertDialog>()

    private fun AlertDialog.Builder.track(): AlertDialog {
        dialogs.removeAll { !it.isShowing }
        return show().also { dialogs += it }
    }

    private fun closePopups() {
        openMenu?.dismiss()
        openMenu = null
        dialogs.forEach { try { if (it.isShowing) it.dismiss() } catch (_: Exception) {} }
        dialogs.clear()
    }

    private fun lock() {
        if (!isDeviceSecure()) return
        if (!Hub.locked) LogBuf.add("Låst")
        Hub.locked = true
        Hub.onLocked()
        pinPad.hide()
        closePopups()
        refreshLockUi()
    }

    private fun unlock(reason: String = "Låst op", byLoginPage: Boolean = false) {
        Hub.locked = false
        Hub.lastActivity = SystemClock.elapsedRealtime()
        loginUnlockAt = if (byLoginPage) Hub.lastActivity else -1L
        LogBuf.add(reason)
        refreshLockUi()
    }

    private fun onLoginPage() = pageHost == LOGIN_HOST

    /**
     * Låst og app'en kommer frem: står Cicero på login-siden, er der intet at beskytte.
     * Kender vi ikke siden endnu (opstart), ventes der kort på, at Cicero er indlæst.
     */
    private fun startUnlockFlow() {
        if (!Hub.locked || authInProgress) return
        when {
            onLoginPage() -> unlock("Ikke logget ind i Cicero – ingen lås", byLoginPage = true)
            pageHost != null && !pageLoading -> authenticate()
            else -> {
                waitingForPage = true
                handler.removeCallbacks(delayedAuth)
                handler.postDelayed(delayedAuth, MAX_PAGE_WAIT_MS)
            }
        }
    }

    /** Kaldes, når Cicero skifter side (også når login-siden kommer frem efter udløbet login). */
    private var lastSection: String? = null

    /** Log hvilken del af Cicero der vises – kun første del af adressen, uden tal (adresser kan indeholde lånernumre) */
    private fun logSection(u: Uri) {
        val section = when (u.host?.lowercase()) {
            LOGIN_HOST -> "login-siden"
            CICERO_HOST -> {
                val seg = u.pathSegments.firstOrNull()
                    ?: u.fragment?.takeIf { it.startsWith("/") }?.trimStart('/')?.substringBefore('/')
                "Cicero /" + (seg ?: "").substringBefore('?').filter { it.isLetter() || it == '-' }
            }
            else -> return
        }
        if (section != lastSection) {
            lastSection = section
            LogBuf.add("Side: $section")
        }
    }

    private fun onPageChanged(url: String, loading: Boolean?) {
        val u = Uri.parse(url)
        pageHost = u.host?.lowercase()
        logSection(u)
        if (loading != null) pageLoading = loading
        if (!Hub.locked) {
            // Forbi login-siden og ind i Cicero uden at nogen har rørt telefonen (fx automatisk login):
            // så har ingen bevist, hvem de er – lås igen
            if (loginUnlockAt >= 0 && !onLoginPage()) {
                val touched = Hub.lastActivity > loginUnlockAt
                loginUnlockAt = -1L
                if (!touched && isDeviceSecure()) {
                    LogBuf.add("Logget ind uden tryk – låser igen")
                    lock()
                    if (resumed) startUnlockFlow()
                }
            }
            return
        }
        if (onLoginPage()) {
            handler.removeCallbacks(delayedAuth)
            waitingForPage = false
            authCancel?.cancel()
            unlock("Ikke logget ind i Cicero – ingen lås", byLoginPage = true)
        } else if (waitingForPage && !pageLoading) {
            // Giv Cicero et øjeblik til evt. at sende videre til login, før der spørges
            handler.removeCallbacks(delayedAuth)
            handler.postDelayed(delayedAuth, QUIET_AFTER_LOAD_MS)
        }
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
            maybeShowWhatsNew()
        }
    }

    /** Første gang efter en opdatering: vis kort, hvad der er nyt (vises når app'en er låst op). */
    private fun maybeShowWhatsNew() {
        val prefs = prefs()
        val current = Updater.currentCode(this)
        val seen = prefs.getLong("seenVersion", -1L)
        if (seen >= current) return
        prefs.edit().putLong("seenVersion", current).apply()
        val all = Changelog.entries(this)
        // Har man sprunget versioner over, vises alle siden sidst; ellers kun den nyeste
        val news = all.filter { it.code <= current && (if (seen < 0) it.code == current else it.code > seen) }
        if (news.isEmpty()) return
        val msg = if (news.size == 1) news[0].text else news.joinToString("\n\n") { "${it.name}: ${it.text}" }
        AlertDialog.Builder(this)
            .setTitle("Nyt i Cicero NFC ${BuildConfigInfo.version(this)}")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Alle ændringer") { _, _ -> showChangelog() }
            .track()
    }

    private fun showChangelog() {
        val all = Changelog.entries(this)
        val msg = if (all.isEmpty()) {
            "Ændringslisten følger ikke med i denne version."
        } else {
            all.joinToString("\n\n") { "${it.name}\n${it.text}" }
        }
        AlertDialog.Builder(this)
            .setTitle("Ændringer")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .track()
    }

    /** Telefonens egen skærmlås: fingeraftryk, ansigt eller pinkode. App'en ser aldrig selve koden. */
    private fun authenticate() {
        if (authInProgress) return
        handler.removeCallbacks(delayedAuth)
        waitingForPage = false
        if (!isDeviceSecure()) { unlock(); return }
        authInProgress = true
        if (Build.VERSION.SDK_INT >= 30) {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Lås Cicero NFC op")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            val cancel = CancellationSignal()
            authCancel = cancel
            prompt.authenticate(
                cancel, mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        authEnded()
                        unlock()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        authEnded()
                        // Fx "for mange forsøg" eller "intet fingeraftryk registreret" – hjælper når låsen driller
                        if (Hub.locked) LogBuf.add("Oplåsning afbrudt: $errString ($errorCode)")
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
        authCancel = null
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
            if (!Hub.locked && !onLoginPage() &&
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
        pinPad.setColors(bg = pal.bar, text = pal.text, sub = pal.sub, key = pal.offCircle, blue = pal.blue)
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
          // Cicero markerer ikke selv lys/mørk, så baggrundsfarven aflæses flere steder i den nederste
          // del af skærmen (ikke farvede overskrifter). Halvgennemsigtige lag, fx skyggen bag en dialog, springes over.
          function lum(e) {
            while (e) {
              var c = getComputedStyle(e).backgroundColor;
              var m = c && c.match(/[\d.]+/g);
              if (m && (m.length < 4 || +m[3] >= 0.9)) return 0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2];
              e = e.parentElement;
            }
            return -1;
          }
          var w = window.innerWidth, h = window.innerHeight, dark = 0, light = 0;
          [0.1, 0.5, 0.9].forEach(function (x) {
            [0.5, 0.7, 0.9].forEach(function (y) {
              var l = lum(document.elementFromPoint(w * x, h * y));
              if (l >= 0) { if (l < 128) dark++; else light++; }
            });
          });
          if (dark + light === 0) return { t: 'unknown' };
          return { t: dark > light ? 'dark' : 'light', d: dark, l: light };
        })()
    """.trimIndent()

    private var lastNfcOn: Boolean? = null
    private var pendingTheme: String? = null
    private var themeLogged = false

    private fun detectCiceroTheme() {
        web.evaluateJavascript(themeProbe) { result ->
            val j = try { org.json.JSONObject(result ?: "") } catch (_: Exception) { return@evaluateJavascript }
            val theme = j.optString("t")
            val newPal = when (theme) {
                "dark" -> darkPalette
                "light" -> lightPalette
                else -> return@evaluateJavascript
            }
            val how = "${maxOf(j.optInt("d"), j.optInt("l"))} af ${j.optInt("d") + j.optInt("l")} punkter"
            val name = if (theme == "dark") "mørk" else "lys"
            if (!themeLogged) { themeLogged = true; LogBuf.add("Tema: $name ($how)") }
            // Skift først, når to målinger i træk er enige – så blinker bjælken ikke ved sideskift
            if (newPal === pal) { pendingTheme = null; return@evaluateJavascript }
            if (pendingTheme != theme) { pendingTheme = theme; return@evaluateJavascript }
            pendingTheme = null
            pal = newPal
            prefs().edit().putString("ciceroTheme", theme).apply()
            LogBuf.add("Tema skiftet til $name ($how)")
            applyPalette()
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
        openMenu = m
        m.setOnDismissListener { if (openMenu === m) openMenu = null }
        m.menu.add(0, 1, 0, if (logScroll.visibility == View.VISIBLE) "Skjul log" else "Vis log")
        m.menu.add(0, 2, 1, "Del log")
        m.menu.add(0, 3, 2, "Genindlæs Cicero")
        if (isDeviceSecure()) m.menu.add(0, 6, 3, "Lås nu")
        m.menu.add(0, 9, 4, "Pinkode-tastatur").apply {
            isCheckable = true
            isChecked = prefs().getBoolean(PREF_PIN_PAD, true)
        }
        m.menu.add(0, 10, 5, "Vend pinkode-tastatur").apply {
            isCheckable = true
            isChecked = prefs().getBoolean(PREF_PIN_FLIP, false)
        }
        m.menu.add(0, 11, 6, "Værktøj til tags")
        m.menu.add(0, 7, 6, "Søg efter opdatering")
        m.menu.add(0, 8, 7, "Ændringer")
        m.menu.add(0, 4, 8, "Om Cicero NFC")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> toggleLog()
                2 -> shareLog()
                3 -> web.reload()
                4 -> showAbout()
                6 -> lock()
                7 -> checkForUpdateNow()
                8 -> showChangelog()
                9 -> {
                    val on = !prefs().getBoolean(PREF_PIN_PAD, true)
                    prefs().edit().putBoolean(PREF_PIN_PAD, on).apply()
                    LogBuf.add(if (on) "Pinkode-tastatur slået til" else "Pinkode-tastatur slået fra")
                    injectPinWatcher()
                }
                10 -> toggleFlip()
                11 -> startActivity(Intent(this, TagToolActivity::class.java).putExtra(TagToolActivity.EXTRA_DARK, pal === darkPalette))
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
                    "Designet og testet af Kasper Svangren, Gladsaxe Bibliotekerne. Kodet med AI-assistance (Claude).\n\n" +
                    "Tak til Deichman bibliotek i Oslo for go-feig (MIT-licens), hvis protokol Cicero taler med RFID-læseren. " +
                    "Der er ikke kopieret kode.",
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Licenser") { _, _ -> showLicenses() }
            .track()
    }

    private fun showLicenses() {
        AlertDialog.Builder(this)
            .setTitle("Open source-licenser")
            .setMessage(
                "Cicero NFC indeholder:\n\n" +
                    "Kotlin Standard Library\n" +
                    "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors\n" +
                    "Apache License 2.0\n\n" +
                    "AndroidX (Activity, CameraX)\n" +
                    "Copyright The Android Open Source Project\n" +
                    "Apache License 2.0\n\n" +
                    "Google ML Kit (tekst- og stregkodegenkendelse via Google Play-tjenester)\n" +
                    "ML Kit Terms of Service: https://developers.google.com/ml-kit/terms\n\n" +
                    "Licensed under the Apache License, Version 2.0. You may obtain a copy of the License at " +
                    "https://www.apache.org/licenses/LICENSE-2.0\n\n" +
                    "Unless required by applicable law or agreed to in writing, software distributed under the " +
                    "License is distributed on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.",
            )
            .setPositiveButton("OK", null)
            .track()
    }

    /**
     * Cicero kalder RFID-læseren på den adresse, der står under Enhedsindstillinger → RFID scanner.
     * Er Hostname eller Port forkert (mellemrum, stavefejl, forkert tal), siges det tydeligt i bjælken.
     */
    private fun checkRfidSetup(u: Uri, scheme: String?, host: String) {
        val path = u.path ?: return
        if (path !in RFID_PATHS) return
        if (isSystematicHost(host)) return
        val port = if (u.port != -1) u.port else if (scheme == "https") 443 else 80
        // Tjek både Hostname og Port – begge kan være forkerte på én gang
        val details = ArrayList<String>()
        val hostBad = host !in LOCAL_HOSTS
        val portBad = port != Server.PORT
        if (hostBad) {
            details += if (host.trim() in LOCAL_HOSTS) {
                "Hostname har et mellemrum: '$host'"
            } else {
                "Hostname er '$host' – skal være localhost"
            }
        }
        if (portBad) details += "Port er $port – skal være ${Server.PORT}"
        val text = when {
            hostBad && portBad -> "Fejl i Hostname/Port"
            hostBad -> "Fejl i Hostname"
            portBad -> "Fejl i Port"
            else -> null
        }
        val err = text?.let { SetupError(it, details) }
        if (err == setupError) return
        setupError = err
        if (err != null) {
            LogBuf.add("RFID-opsætning i Cicero er forkert: ${details.joinToString("; ")}")
        } else {
            LogBuf.add("RFID-opsætning i Cicero er rettet")
        }
        runOnUiThread { refreshStatus() }
    }

    private fun onProblemClick() {
        val n = nfc
        if (n != null && !n.isEnabled) {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            return
        }
        val err = setupError ?: return
        AlertDialog.Builder(this)
            .setTitle("RFID-opsætningen i Cicero er forkert")
            .setMessage(
                err.details.joinToString("\n") + "\n\n" +
                    "Ret det i Cicero under Enhedsindstillinger → RFID scanner:\n" +
                    "Scanner: Deichman\n" +
                    "Hostname: localhost (uden mellemrum)\n" +
                    "Port: ${Server.PORT}\n\n" +
                    "Tryk Test forbindelse og gem. Beskeden forsvinder, når Cicero bruger den rigtige adresse.",
            )
            .setPositiveButton("OK", null)
            .track()
    }

    // ---------- Pinkode-tastatur ----------

    private fun prefs() = getSharedPreferences("ui", MODE_PRIVATE)

    /** Fjern det, der kan være personoplysninger: forespørgsler i adresser, e-mails og lange tal */
    private fun scrub(msg: String) = msg
        .replace(Regex("\\?[^\\s\"']*"), "?…")
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "…@…")
        .replace(Regex("\\d{6,}"), "…")
        .take(250)

    private fun isSystematicHost(h: String) = h == "systematic.com" || h.endsWith(".systematic.com")

    private fun toggleFlip() {
        val flip = !prefs().getBoolean(PREF_PIN_FLIP, false)
        prefs().edit().putBoolean(PREF_PIN_FLIP, flip).apply()
        pinPad.setFlipped(flip)
    }

    /** Cicero kalder denne, når pinkode-feltet får fokus. Den modtager ingen data fra siden. */
    private inner class PinBridge {
        @JavascriptInterface
        fun pinFocus() {
            runOnUiThread { onPinFieldFocused() }
        }

        /** Pinkode-kontakten i udlånsbilledet melder, om skiftet lykkedes, og hvilken vej */
        @JavascriptInterface
        fun pinSetting(msg: String) {
            LogBuf.add("Pinkode-krav: ${scrub(msg)}")
        }
    }

    /** Holder øje med, om Ciceros pinkode-felt vælges. Slået fra = Cicero opfører sig som før. */
    private fun injectPinWatcher() {
        val on = prefs().getBoolean(PREF_PIN_PAD, true)
        web.evaluateJavascript(PIN_WATCHER_JS.replace("%ON%", on.toString()), null)
    }

    private fun onPinFieldFocused() {
        // Cicero sætter nogle gange fokus på feltet igen – må ikke nulstille de cifre, der allerede er tastet
        if (pinPad.isShowing) return
        val host = Uri.parse(web.url ?: "").host?.lowercase()
        if (Hub.locked || host != CICERO_HOST || !prefs().getBoolean(PREF_PIN_PAD, true)) {
            useNormalKeyboardForPin()
            return
        }
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(web.windowToken, 0)
        LogBuf.add("Pinkode-tastatur vist")
        pinPad.show(prefs().getBoolean(PREF_PIN_FLIP, false))
    }

    /** Sæt koden i Ciceros felt. Kun cifre, så intet andet kan sendes ind på siden. */
    private fun fillPin(pin: String) {
        if (pin.isEmpty() || !pin.all { it in '0'..'9' }) return
        web.evaluateJavascript(PIN_FILL_JS.replace("%PIN%", pin)) { r ->
            LogBuf.add(if (r?.contains("ok") == true) "Pinkode udfyldt" else "Pinkode-feltet var forsvundet")
        }
    }

    /** "Tastatur": brug telefonens almindelige tastatur til feltet denne gang */
    private fun useNormalKeyboardForPin() {
        web.evaluateJavascript(PIN_KEYBOARD_JS, null)
        handler.postDelayed({
            web.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun setupWeb(saved: Bundle?) {
        // Fjernfejlsøgning (chrome://inspect) kun i fejlsøgnings-builds, aldrig i den rigtige app
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Browserens egen regel for usikkert indhold varierer mellem telefoner (localhost blev blokeret
            // på nogle). Derfor tillader browseren det, og app'en blokerer selv alt usikkert undtagen
            // vores egen RFID-server – se shouldInterceptRequest.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
            // Samme tekststørrelse som i Chrome (ellers følger den telefonens skriftstørrelse, og Ciceros layout skrider)
            textZoom = 100
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            // Almindelige web-adresser (Cicero, login) bliver i app'en; andet (mailto:, tel:, apps) åbnes udenfor
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: return true
                if (scheme == "http" || scheme == "https") return false
                openExternal(request.url.toString())
                return true
            }

            // Usikre (http) forbindelser er kun tilladt til app'ens egen server på localhost
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                val scheme = u.scheme?.lowercase()
                // Afkod, så fx et mellemrum (%20) i Ciceros Hostname-felt kan ses
                val h = Uri.decode(u.host ?: "").lowercase()
                checkRfidSetup(u, scheme, h)
                if (scheme != "http") return null
                if (h in LOCAL_HOSTS) return null
                if (blockedHosts.add(h)) LogBuf.add("Blokeret usikker forbindelse til '$h'")
                return WebResourceResponse(
                    "text/plain", "utf-8", 403, "Forbidden",
                    emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)),
                )
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                onPageChanged(url, loading = true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                onPageChanged(url, loading = false)
                injectPinWatcher()
                web.evaluateJavascript(SWIPE_TABS_JS, null)
                web.evaluateJavascript(PIN_TOGGLE_JS, null)
                handler.postDelayed({ detectCiceroTheme() }, 500)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                onPageChanged(url, loading = null)
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
                if (important) LogBuf.add("Cicero-konsol: ${scrub(msg)}")
                return true
            }

            // Cicero beder om kameraet (scan lånernr.). Kun Ciceros egen side får lov.
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val host = request.origin?.host ?: ""
                val https = request.origin?.scheme == "https"
                if (!wantsCamera || !https || !isSystematicHost(host)) {
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
                        if (u.scheme == "https" && isSystematicHost(h)) {
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

        web.addJavascriptInterface(PinBridge(), "CiceroNFC")
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
        LogBuf.add("App'en i forgrunden")
        // Lås, hvis app'en ikke har været brugt i 5 minutter (også mens den var i baggrunden)
        if (isDeviceSecure()) {
            if (!Hub.locked && !onLoginPage() && SystemClock.elapsedRealtime() - Hub.lastActivity >= LOCK_AFTER_MS) lock()
            refreshLockUi()
            if (Hub.locked && !authInProgress && SystemClock.elapsedRealtime() - lastAuthEnded > 1500) startUnlockFlow()
        } else if (Hub.locked) {
            Hub.locked = false
            refreshLockUi()
        }
        handler.removeCallbacks(lockTicker)
        handler.postDelayed(lockTicker, 10_000)
        Updater.check(this)
        // Kommer tilbage fra "Tillad installation af apps" – fortsæt opdateringen
        if (updateAfterPermission && packageManager.canRequestPackageInstalls()) {
            updateAfterPermission = false
            confirmUpdate()
        }
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
        LogBuf.add("App'en i baggrunden")
        resumed = false
        handler.removeCallbacks(themeTicker)
        handler.removeCallbacks(lockTicker)
        handler.removeCallbacks(delayedAuth)
        waitingForPage = false
        // Pinkoden må ikke blive stående på skærmen, hvis app'en forlades
        pinPad.hide()
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
        if (pinPad.isShowing) {
            pinPad.hide()
            LogBuf.add("Pinkode annulleret")
            return
        }
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    // ---------- Bjælken ----------

    private fun refreshStatus() {
        val n = nfc
        val nfcOn = n?.isEnabled == true
        if (lastNfcOn != null && lastNfcOn != nfcOn) LogBuf.add(if (nfcOn) "NFC slået til" else "NFC slået fra")
        lastNfcOn = nfcOn
        val listening = Server.clientCount() > 0
        val problem = when {
            n == null -> "Ingen NFC"
            !n.isEnabled -> "NFC slået fra – tryk her"
            Server.bindError != null -> Server.bindError
            else -> null
        }
        val setup = if (problem == null) setupError else null
        val fb = Hub.feedback

        // Bjælken forbliver neutral; en lille farvet pille bærer budskabet
        var pillColor: Int? = null
        var pillIconRes = 0
        var pillWords = ""
        var text = ""
        if (problem != null) {
            pillColor = red; pillIconRes = R.drawable.ic_warn; pillWords = problem
        } else if (setup != null) {
            // Kort pille, selve fejlen i teksten ved siden af; tryk for vejledning
            pillColor = red; pillIconRes = R.drawable.ic_warn; pillWords = "RFID-fejl"
            text = setup.text
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

        val upd = Updater.available
        val prog = Updater.progress
        when {
            prog >= 0 -> {
                updateBtn.text = "Henter $prog %"
                updateBtn.visibility = View.VISIBLE
            }
            upd != null -> {
                updateBtn.text = "Ny version"
                updateBtn.visibility = View.VISIBLE
            }
            else -> updateBtn.visibility = View.GONE
        }
        updateBg.setColor(pal.blue)

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
        detail.text = when {
            setup != null -> ""
            problem == null && fb != Hub.Fb.IDLE -> Hub.statusDetail
            else -> ""
        }
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

    // ---------- Opdatering ----------

    private fun checkForUpdateNow() {
        Updater.check(this, force = true) { info, error ->
            runOnUiThread {
                when {
                    info != null -> startUpdate()
                    error != null -> AlertDialog.Builder(this)
                        .setTitle("Kunne ikke tjekke for opdatering")
                        .setMessage(error)
                        .setPositiveButton("OK", null).track()
                    else -> AlertDialog.Builder(this)
                        .setTitle("Ingen ny version")
                        .setMessage("Du har den nyeste version (${BuildConfigInfo.version(this)}).")
                        .setPositiveButton("OK", null).track()
                }
            }
        }
    }

    private fun startUpdate() {
        if (Updater.available == null || Updater.progress >= 0) return
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Tillad opdateringer")
                .setMessage(
                    "For at kunne opdatere sig selv skal Cicero NFC have lov til at installere apps. " +
                        "Det skal kun gøres én gang.\n\nSlå \"Tillad fra denne kilde\" til og tryk tilbage.",
                )
                .setPositiveButton("Giv lov") { _, _ ->
                    updateAfterPermission = true
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Ikke nu", null)
                .track()
            return
        }
        confirmUpdate()
    }

    private fun confirmUpdate() {
        val info = Updater.available ?: return
        AlertDialog.Builder(this)
            .setTitle("Opdater til ${info.name}?")
            .setMessage(
                (if (info.notes.isNotEmpty()) "Nyt:\n${info.notes}\n\n" else "") +
                    "App'en henter den nye version fra GitHub. Android spørger derefter, om du vil opdatere. Login og opsætning bevares.",
            )
            .setPositiveButton("Opdater") { _, _ -> Updater.downloadAndInstall(this, info) }
            .setNegativeButton("Ikke nu", null)
            .track()
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
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            LogBuf.add("Kunne ikke åbne link udenfor app'en")
        } catch (e: Exception) {
            // Kun typen af link – hele adressen kan indeholde personoplysninger
            LogBuf.add("Ugyldigt link (${url.substringBefore(':').take(20)})")
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

private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

/** De adresser, Cicero kalder på en Deichman-RFID-læser */
private val RFID_PATHS = setOf(
    "/.status", "/events", "/events/", "/start", "/stop", "/scan",
    "/alarmOn", "/alarmOff", "/write", "/writetagbarcode",
)

/** Lægges ind i Cicero: opdager når feltet "Pinkode" vælges og beder app'en vise sit store tastatur */
private val PIN_WATCHER_JS = """
(function () {
  window.__cnfcPinOn = %ON%;
  if (window.__cnfcPinInstalled) return;
  window.__cnfcPinInstalled = true;
  function textOf(el) {
    var t = [el.getAttribute('aria-label'), el.getAttribute('placeholder'), el.getAttribute('name'),
             el.id, el.getAttribute('formcontrolname')].join(' ');
    if (el.labels) for (var i = 0; i < el.labels.length; i++) t += ' ' + el.labels[i].textContent;
    var lb = el.getAttribute('aria-labelledby');
    if (lb) lb.split(' ').forEach(function (id) { var x = document.getElementById(id); if (x) t += ' ' + x.textContent; });
    var ff = el.closest ? el.closest('mat-form-field, .mat-mdc-form-field, .mat-form-field') : null;
    if (ff) { var l = ff.querySelector('mat-label, label'); if (l) t += ' ' + l.textContent; }
    return t.toLowerCase();
  }
  document.addEventListener('focusin', function (e) {
    var el = e.target;
    if (!window.__cnfcPinOn || !el || el.tagName !== 'INPUT') return;
    if (location.hostname !== 'cicero.systematic.com') return;
    if (el.getAttribute('data-cnfc-skip') === '1') return;
    if (el.type === 'checkbox' || el.type === 'radio' || (el.closest && el.closest('[data-cnfc]'))) return;
    if (!/pin.?kode|pincode/.test(textOf(el))) return;
    window.__cnfcPinEl = el;
    el.blur();
    try { CiceroNFC.pinFocus(); } catch (x) {}
  }, true);
})();
"""

/** Sætter koden i feltet, så Cicero opdager den som om den var tastet */
private val PIN_FILL_JS = """
(function (v) {
  var el = window.__cnfcPinEl;
  if (!el || !document.contains(el)) return 'none';
  var set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  set.call(el, v);
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  el.dispatchEvent(new FocusEvent('blur'));
  el.dispatchEvent(new FocusEvent('focusout', { bubbles: true }));
  return 'ok';
})('%PIN%')
"""

/** Giv feltet fokus igen uden at det store tastatur kommer frem */
private val PIN_KEYBOARD_JS = """
(function () {
  var el = window.__cnfcPinEl;
  if (!el || !document.contains(el)) return;
  el.setAttribute('data-cnfc-skip', '1');
  el.focus();
  setTimeout(function () { el.removeAttribute('data-cnfc-skip'); }, 300);
})()
"""

/**
 * Ciceros fanerække (fx Reservationer · Bookinger · Fjernlån) kan kun bladres med pile.
 * Her får den almindelig rulning, så den følger fingeren; pilene skjules.
 * Valgt fane rulles frem, når man trykker på den. Rammes navnene ikke, er alt som før.
 */
private val SWIPE_TABS_JS = """
(function () {
  if (document.getElementById('cnfc-swipe')) return;
  var st = document.createElement('style');
  st.id = 'cnfc-swipe';
  st.textContent =
    '.mat-mdc-tab-header-pagination, .mat-tab-header-pagination { display: none !important; }' +
    '.mat-mdc-tab-label-container, .mat-tab-label-container, .mat-mdc-tab-link-container, .mat-tab-link-container {' +
    '  overflow-x: auto !important; scrollbar-width: none; }' +
    '.mat-mdc-tab-label-container::-webkit-scrollbar, .mat-tab-label-container::-webkit-scrollbar,' +
    '.mat-mdc-tab-link-container::-webkit-scrollbar, .mat-tab-link-container::-webkit-scrollbar { display: none; }' +
    '.mat-mdc-tab-list, .mat-tab-list, .mat-mdc-tab-links, .mat-tab-links { transform: none !important; }';
  (document.head || document.documentElement).appendChild(st);
  document.addEventListener('click', function (e) {
    var t = e.target && e.target.closest &&
      e.target.closest('.mat-mdc-tab, .mat-mdc-tab-link, .mat-tab-label, .mat-tab-link');
    if (t) setTimeout(function () {
      try { t.scrollIntoView({ inline: 'nearest', block: 'nearest', behavior: 'smooth' }); } catch (x) {}
    }, 50);
  }, true);
})();
"""

/**
 * Kontakten "Pinkode" ved "Send kvittering" i udlånsbilledet. Skifter Ciceros egen indstilling
 * "Pinkode påkrævet ved udlån" (gemt som DK-<bibliotek>_REQUIRE_PINCODE_ON_CHECKOUT).
 * Cicero læser kun indstillingen, når man trykker Gem, så app'en åbner Enhedsindstillinger skjult, skifter
 * kontakten og trykker Gem – de samme klik som i hånden. Rammes Ciceros navne ikke, vises kontakten bare ikke.
 */
private val PIN_TOGGLE_JS = """
(function () {
  if (window.__cnfcPinToggle) return;
  window.__cnfcPinToggle = true;
  var SUFFIX = '_REQUIRE_PINCODE_ON_CHECKOUT';
  var busy = false;
  function report(m) { try { CiceroNFC.pinSetting(m); } catch (e) {} }
  function key() {
    var i, k, m;
    for (i = 0; i < localStorage.length; i++) { k = localStorage.key(i) || ''; if (k.slice(-SUFFIX.length) === SUFFIX) return k; }
    for (i = 0; i < localStorage.length; i++) { m = /^(DK-\d+)_/.exec(localStorage.key(i) || ''); if (m) return m[1] + SUFFIX; }
    return null;
  }
  function stored() { var k = key(); return !!k && localStorage.getItem(k) === 'true'; }
  function visible(el) { return !!(el && el.offsetParent !== null); }
  function textOf(el) { return ((el && (el.innerText || el.textContent)) || '').trim(); }
  function findToggle(label) {
    var list = document.querySelectorAll('mat-slide-toggle, .mat-mdc-slide-toggle');
    for (var i = 0; i < list.length; i++) {
      if (!list[i].hasAttribute('data-cnfc') && textOf(list[i]).indexOf(label) >= 0) return list[i];
    }
    return null;
  }
  function switchOf(t) { return t.querySelector('button[role=switch], input[type=checkbox]') || t; }
  function isOn(t) {
    var b = switchOf(t);
    if (b.getAttribute && b.getAttribute('aria-checked') != null) return b.getAttribute('aria-checked') === 'true';
    if (b.type === 'checkbox') return b.checked;
    return t.classList.contains('mat-mdc-slide-toggle-checked') || t.classList.contains('mat-checked');
  }
  function waitFor(fn, ms) {
    return new Promise(function (res) {
      var t0 = Date.now();
      (function poll() {
        var v = null;
        try { v = fn(); } catch (e) {}
        if (v) return res(v);
        if (Date.now() - t0 > ms) return res(null);
        setTimeout(poll, 100);
      })();
    });
  }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function setLook(t, on) {
    t.classList.toggle('mat-mdc-slide-toggle-checked', on);
    t.classList.toggle('mat-checked', on);
    var b = t.querySelector('button[role=switch]');
    if (b) {
      b.setAttribute('aria-checked', on ? 'true' : 'false');
      b.classList.toggle('mdc-switch--selected', on);
      b.classList.toggle('mdc-switch--checked', on);
      b.classList.toggle('mdc-switch--unselected', !on);
    }
    var cb = t.querySelector('input[type=checkbox]');
    if (cb) cb.checked = on;
  }

  // Enhedsindstillinger: åbn, skift, Gem – Ciceros egne klik
  var panelLogged = false;
  function viaSettings(want) {
    var hide = document.createElement('style');
    hide.textContent = '.cdk-overlay-container { opacity: 0 !important; }';
    document.head.appendChild(hide);
    var user = sessionStorage.getItem('username') || '';
    var page = findToggle('Send kvittering');
    var hidden = [];
    // Det øverste element, der ikke også rummer selve udlånsbilledet = panelet
    function panelRoot(el) {
      var r = null;
      while (el && el !== document.body && el !== document.documentElement && !(page && el.contains(page))) { r = el; el = el.parentElement; }
      return r;
    }
    function hideNode(n) {
      var root = panelRoot(n.nodeType === 1 ? n : n.parentElement);
      if (!root || hidden.indexOf(root) >= 0) return;
      root.style.setProperty('visibility', 'hidden', 'important');
      hidden.push(root);
      if (!panelLogged) {
        panelLogged = true;
        report('panel skjult: ' + root.tagName.toLowerCase() + ' ' + String(root.className || '').slice(0, 60));
      }
    }
    // Kører, før browseren tegner de nye elementer – så panelet aldrig ses
    var mo = new MutationObserver(function (list) {
      list.forEach(function (m) {
        Array.prototype.forEach.call(m.addedNodes, function (n) {
          var t = n.textContent || '';
          if (t.indexOf('Enhedsindstillinger') >= 0 || t.indexOf('Pinkode påkrævet') >= 0) hideNode(n);
        });
      });
    });
    mo.observe(document.body, { childList: true, subtree: true });
    function done(r) {
      mo.disconnect();
      hide.remove();
      // Lukkede panelet ikke (fejl), skal det kunne ses igen
      setTimeout(function () { hidden.forEach(function (h) { if (h.isConnected) h.style.removeProperty('visibility'); }); }, 600);
      return r;
    }
    var chip = Array.prototype.slice.call(document.querySelectorAll('button, [role=button], a')).filter(function (b) {
      var t = textOf(b);
      return user && t.indexOf(user) >= 0 && t.length < 80 && visible(b);
    })[0];
    if (!chip) return Promise.resolve(done('fandt ikke brugermenuen'));
    chip.click();
    return waitFor(function () {
      var tg = findToggle('Pinkode påkrævet');
      if (tg) return { tg: tg };
      var items = document.querySelectorAll('[role=menuitem], .mat-mdc-menu-item, button, a');
      for (var i = 0; i < items.length; i++) if (textOf(items[i]) === 'Enhedsindstillinger' && visible(items[i])) return { item: items[i] };
      return null;
    }, 3000).then(function (r) {
      if (!r) return null;
      if (r.tg) return r.tg;
      r.item.click();
      return waitFor(function () { return findToggle('Pinkode påkrævet'); }, 3000);
    }).then(function (tg) {
      if (!tg) return done('fandt ikke Enhedsindstillinger');
      // Står den allerede rigtigt, skiftes der frem og tilbage, så Gem kan trykkes
      var clicks = isOn(tg) === want ? 2 : 1;
      var p = Promise.resolve();
      for (var c = 0; c < clicks; c++) p = p.then(function () { switchOf(tg).click(); return sleep(150); });
      return p.then(function () {
        return waitFor(function () {
          var bs = document.querySelectorAll('button');
          for (var i = 0; i < bs.length; i++) if (textOf(bs[i]) === 'Gem' && visible(bs[i]) && !bs[i].disabled) return bs[i];
          return null;
        }, 2000);
      }).then(function (gem) {
        if (!gem) return done('fandt ikke Gem');
        gem.click();
        return sleep(400).then(function () { return done('ok'); });
      });
    }).catch(function (e) { return done('fejl: ' + e); });
  }

  function change(want) {
    if (busy) return;
    busy = true;
    sync();
    viaSettings(want).then(function (r) {
      return r === 'ok' ? 'ok' : 'kunne ikke skifte – ' + r;
    }).then(function (how) {
      busy = false;
      sync();
      report((want ? 'til' : 'fra') + ' (' + how + ')');
    });
  }

  // Sæt kontakten ind ved "Send kvittering" og hold den opdateret, også når Cicero tegner siden om
  function onLoanTab() {
    var tabs = document.querySelectorAll('[role=tab]');
    for (var i = 0; i < tabs.length; i++) {
      var t = tabs[i];
      if (t.getAttribute('aria-selected') === 'true' || t.classList.contains('mdc-tab--active')) return /udl[åa]n/i.test(textOf(t));
    }
    return false;
  }
  function place(host, mine) {
    var parent = host.parentNode;
    if (getComputedStyle(parent).position === 'static') parent.style.position = 'relative';
    var lab = host.querySelector('label, .mdc-label') || host;
    var pr = parent.getBoundingClientRect(), lr = lab.getBoundingClientRect(), hr = host.getBoundingClientRect();
    mine.style.position = 'absolute';
    mine.style.margin = '0';
    var left = lr.right - pr.left + 24;
    if (pr.left + left + mine.offsetWidth > pr.right) {
      // For smal skærm: under "Send kvittering", på linje med den
      mine.style.left = (hr.left - pr.left) + 'px';
      mine.style.top = (hr.bottom - pr.top + 4) + 'px';
    } else {
      mine.style.left = left + 'px';
      mine.style.top = (hr.top - pr.top) + 'px';
    }
  }
  function sync() {
    var host = findToggle('Send kvittering');
    var mine = document.querySelector('[data-cnfc="pin"]');
    if (!host || !onLoanTab()) { if (mine) mine.remove(); return; }
    if (!mine || mine.previousElementSibling !== host) {
      if (mine) mine.remove();
      mine = host.cloneNode(true);
      mine.setAttribute('data-cnfc', 'pin');
      mine.removeAttribute('id');
      Array.prototype.forEach.call(mine.querySelectorAll('[id], [for]'), function (e) { e.removeAttribute('id'); e.removeAttribute('for'); });
      // Ingen fokus-ring/ripple kopieret med fra Ciceros kontakt
      Array.prototype.forEach.call(mine.querySelectorAll('.mat-mdc-focus-indicator, .mdc-switch__ripple, .mat-ripple, .mat-mdc-slide-toggle-ripple'), function (e) { e.remove(); });
      ['cdk-focused', 'cdk-keyboard-focused', 'cdk-program-focused', 'cdk-mouse-focused', 'mat-mdc-slide-toggle-focused'].forEach(function (c) { mine.classList.remove(c); });
      var w = document.createTreeWalker(mine, NodeFilter.SHOW_TEXT, null);
      var n;
      while ((n = w.nextNode())) if (n.nodeValue.indexOf('Send kvittering') >= 0) n.nodeValue = n.nodeValue.replace('Send kvittering', 'Pinkode');
      mine.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        change(!stored());
      }, true);
      host.parentNode.insertBefore(mine, host.nextSibling);
    }
    setLook(mine, stored());
    mine.style.opacity = busy ? '0.5' : '';
    place(host, mine);
  }
  setInterval(function () { if (location.hostname === 'cicero.systematic.com') sync(); }, 800);
})();
"""
