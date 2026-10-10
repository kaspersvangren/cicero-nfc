package dk.kasvan.ciceronfc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.nfc.NfcAdapter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Kamera, der læser materialenummeret – både stregkoder og trykte tal (fx på en etiket i bogen).
 * Viser de numre, den kan se; man trykker på det rigtige. Alt genkendes på telefonen; intet sendes nogen steder hen.
 */
class ScanActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    companion object {
        const val EXTRA_VALUE = "value"
        const val EXTRA_DARK = "dark"
        private val DIGITS = Regex("""(?<![0-9])[0-9](?:[ \-]?[0-9]){7,15}(?![0-9])""")
    }

    private class Candidate(val value: String, val barcode: Boolean, var lastSeen: Long, var hits: Int, var inFrame: Int) {
        var clean = 0      // set uden bindestreg/mellemrum
        var isbnHint = false
    }

    private lateinit var executor: ExecutorService
    // Lukkes skærmen midt i en genkendelse, må det sene svar ikke få app'en til at gå ned
    private val safe = Executor { r -> try { executor.execute(r) } catch (_: Exception) {} }
    private lateinit var preview: PreviewView
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private var camera: Camera? = null
    private var torch = false
    private var dark = true
    private val candidates = LinkedHashMap<String, Candidate>()
    private var lastUiUpdate = 0L
    private var notReadyLogged = false
    private var zoomed = true
    // Den længde jeres materialenumre plejer at have (lært fra læste tags); null = ikke set endnu
    private val itemLen by lazy { Hub.itemLength() }
    private lateinit var camBox: FrameLayout
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    // Sigte-rammens størrelse på skærmen
    private val frameW get() = dp(280)
    private val frameH get() = dp(120)

    private val barcodes by lazy { BarcodeScanning.getClient() }
    private val texts by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { hint.text = "Kameraet er ikke tilladt – giv lov i telefonens indstillinger"; }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = intent.getBooleanExtra(EXTRA_DARK, true)
        val bg = if (dark) Color.parseColor("#2E2E2E") else Color.parseColor("#F2F5F7")
        val fg = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#404040")
        val sub = if (dark) Color.parseColor("#C6C6C6") else Color.parseColor("#767676")
        executor = Executors.newSingleThreadExecutor()

        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        // Sigte-ramme midt i billedet
        val frame = View(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(3), Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
        }
        camBox = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(frame, FrameLayout.LayoutParams(frameW, frameH, Gravity.CENTER))
        }
        // Tryk på billedet for at stille skarpt dér
        preview.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) { focusAt(e.x, e.y); v.performClick() }
            true
        }
        hint = TextView(this).apply {
            text = "Peg på materialenummeret – stregkode eller tal. Tryk på billedet for skarpt billede, og på det rigtige nummer nedenfor."
            textSize = 15f
            setTextColor(sub)
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        fun smallButton(label: String, action: (TextView) -> Unit) = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { action(this) }
        }
        bottom.addView(smallButton("Annuller") { finish() })
        bottom.addView(smallButton("Lys til") { b ->
            torch = !torch
            camera?.cameraControl?.enableTorch(torch)
            b.text = if (torch) "Lys fra" else "Lys til"
        })
        bottom.addView(smallButton("Zoom 1×") { b ->
            zoomed = !zoomed
            applyZoom()
            b.text = if (zoomed) "Zoom 1×" else "Zoom 2×"
        })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
            addView(camBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hint)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)))
            addView(bottom)
        }
        setContentView(root)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                // Samme billedformat til visning og genkendelse, så rammen passer; større billede = skarpere tal
                val sel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val pv = Preview.Builder().setResolutionSelector(sel).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(sel)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { analyze(it) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
                // 2× zoom fra start: telefonen holdes længere væk, hvor den kan stille skarpt (og fri af chippen)
                applyZoom()
                preview.post { focusAt(preview.width / 2f, preview.height / 2f) }
            } catch (e: Exception) {
                LogBuf.add("Kamera kunne ikke starte: ${e.message}")
                hint.text = "Kameraet kunne ikke starte"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyZoom() {
        val c = camera ?: return
        val max = c.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        c.cameraControl.setZoomRatio(if (zoomed) minOf(2f, max) else 1f)
    }

    private fun focusAt(x: Float, y: Float) {
        val c = camera ?: return
        try {
            val point = preview.meteringPointFactory.createPoint(x, y)
            c.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS)
                    .build()
            )
        } catch (_: Exception) {}
    }

    /** Sigte-rammen omregnet til billedets koordinater (lidt større, så små skævheder ikke betyder noget). */
    private fun frameInImage(w: Int, h: Int): Rect? {
        val vw = camBox.width; val vh = camBox.height
        if (vw == 0 || vh == 0 || w == 0 || h == 0) return null
        val scale = maxOf(vw.toFloat() / w, vh.toFloat() / h) // FILL_CENTER
        val hw = frameW * 0.75f / scale; val hh = frameH * 0.9f / scale
        return Rect((w / 2 - hw).toInt(), (h / 2 - hh).toInt(), (w / 2 + hw).toInt(), (h / 2 + hh).toInt())
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(p: ImageProxy) {
        val media = p.image
        if (media == null) { p.close(); return }
        val rot = p.imageInfo.rotationDegrees
        val upW = if (rot % 180 == 0) p.width else p.height
        val upH = if (rot % 180 == 0) p.height else p.width
        val box = frameInImage(upW, upH)
        fun inside(r: Rect?) = box != null && r != null && box.contains(r.centerX(), r.centerY())
        val img = InputImage.fromMediaImage(media, rot)
        val tb = barcodes.process(img)
        val tt = texts.process(img)
        Tasks.whenAllComplete(tb, tt).addOnCompleteListener(safe) {
            val now = SystemClock.elapsedRealtime()
            if (tb.isSuccessful) {
                for (b in tb.result) {
                    val v = b.rawValue?.trim() ?: continue
                    if (v.length in 1..16 && v.all { it.code in 0x21..0x7E }) add(v, true, now, inside(b.boundingBox), true, false)
                }
            }
            if (tt.isSuccessful) {
                for (block in tt.result.textBlocks) for (line in block.lines) {
                    val inF = inside(line.boundingBox)
                    val isbnLine = line.text.contains("ISBN", ignoreCase = true)
                    for (m in DIGITS.findAll(line.text)) {
                        val v = m.value.filter { it.isDigit() }
                        if (v.length in 8..16) add(v, false, now, inF, m.value.all { it.isDigit() }, isbnLine)
                    }
                }
            } else if (!notReadyLogged) {
                // Fx første gang, mens Play-tjenester stadig henter tekstgenkendelsen
                notReadyLogged = true
                LogBuf.add("Tekstgenkendelse ikke klar: ${tt.exception?.message}")
            }
            p.close()
            if (now - lastUiUpdate > 300) {
                lastUiUpdate = now
                runOnUiThread { showCandidates() }
            }
        }
    }

    private fun add(v: String, barcode: Boolean, now: Long, inFrame: Boolean, clean: Boolean, isbnHint: Boolean) {
        synchronized(candidates) {
            val c = candidates.getOrPut(v) { Candidate(v, barcode, now, 0, 0) }
            c.lastSeen = now; c.hits++
            if (inFrame) c.inFrame++
            if (clean) c.clean++
            if (isbnHint) c.isbnHint = true
        }
    }

    private fun isIsbn(c: Candidate) =
        (c.value.length == 13 && (c.value.startsWith("978") || c.value.startsWith("979"))) ||
            (c.isbnHint && c.value.length in setOf(10, 13))

    /**
     * 0 = ligner jeres materialenumre (rigtig længde, kun cifre), 1 = almindeligt nummer,
     * 2 = næppe et materialenummer (ISBN, dato eller tal med bindestreg/mellemrum). Alt vises – kun rækkefølgen ændres.
     */
    private fun tier(c: Candidate): Int = when {
        isIsbn(c) -> 2
        !c.barcode && c.clean == 0 -> 2
        itemLen != null && c.value.length == itemLen && c.value.all { it.isDigit() } -> 0
        else -> 1
    }

    private fun showCandidates() {
        val now = SystemClock.elapsedRealtime()
        val shown = synchronized(candidates) {
            // Glem numre, der ikke er set i 6 sekunder; stregkoder og ofte sete tal øverst
            candidates.values.removeAll { now - it.lastSeen > 6000 }
            // Tal skal ses mindst 2 gange (stregkoder har kontrolciffer), og et tal, der er en del af
            // et længere nummer (fx uden første ciffer), vises ikke
            val sure = candidates.values.filter { it.barcode || it.hits >= 2 }
            sure.filter { c ->
                sure.none { o ->
                    o !== c && o.value.length > c.value.length && o.value.length - c.value.length <= 2 &&
                        o.value.contains(c.value) && tier(o) <= tier(c)
                }
            }
                .sortedWith(compareBy<Candidate> { tier(it) }.thenByDescending { it.barcode }.thenByDescending { it.inFrame }.thenByDescending { it.hits })
                .take(5)
        }
        list.removeAllViews()
        val fg = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#404040")
        val key = if (dark) Color.parseColor("#4B4B4B") else Color.parseColor("#E0E0E0")
        for (c in shown) {
            list.addView(TextView(this).apply {
                val kind = when {
                    isIsbn(c) -> "ISBN"
                    tier(c) == 2 -> "andet tal"
                    c.barcode -> "stregkode"
                    else -> "tal"
                }
                text = c.value + "   · " + kind
                textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(fg)
                background = GradientDrawable().apply { setColor(key); cornerRadius = dp(12).toFloat() }
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, c.value))
                    finish()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(4), 0, dp(4))
            })
        }
    }

    // Holder på NFC, mens kameraet er fremme – ellers melder Android "ingen understøttede apps", når bogen er tæt på
    override fun onTagDiscovered(tag: android.nfc.Tag?) {}

    override fun onResume() {
        super.onResume()
        try {
            nfc?.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { nfc?.disableReaderMode(this) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        try { barcodes.close(); texts.close() } catch (_: Exception) {}
    }
}
