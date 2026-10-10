package dk.kasvan.ciceronfc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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

/**
 * Kamera, der læser materialenummeret – både stregkoder og trykte tal (fx på en etiket i bogen).
 * Viser de numre, den kan se; man trykker på det rigtige. Alt genkendes på telefonen; intet sendes nogen steder hen.
 */
class ScanActivity : ComponentActivity() {
    companion object {
        const val EXTRA_VALUE = "value"
        const val EXTRA_DARK = "dark"
        private val DIGITS = Regex("""(?<![0-9])[0-9](?:[ \-]?[0-9]){7,15}(?![0-9])""")
    }

    private class Candidate(val value: String, val barcode: Boolean, var lastSeen: Long, var hits: Int)

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
        val camBox = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(frame, FrameLayout.LayoutParams(dp(280), dp(120), Gravity.CENTER))
        }
        hint = TextView(this).apply {
            text = "Peg på materialenummeret – stregkode eller tal. Tryk på det rigtige nummer nedenfor."
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
                val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { analyze(it) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
            } catch (e: Exception) {
                LogBuf.add("Kamera kunne ikke starte: ${e.message}")
                hint.text = "Kameraet kunne ikke starte"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(p: ImageProxy) {
        val media = p.image
        if (media == null) { p.close(); return }
        val img = InputImage.fromMediaImage(media, p.imageInfo.rotationDegrees)
        val tb = barcodes.process(img)
        val tt = texts.process(img)
        Tasks.whenAllComplete(tb, tt).addOnCompleteListener(safe) {
            val now = SystemClock.elapsedRealtime()
            if (tb.isSuccessful) {
                for (b in tb.result) {
                    val v = b.rawValue?.trim() ?: continue
                    if (v.length in 1..16 && v.all { it.code in 0x21..0x7E }) add(v, true, now)
                }
            }
            if (tt.isSuccessful) {
                for (m in DIGITS.findAll(tt.result.text)) {
                    val v = m.value.filter { it.isDigit() }
                    if (v.length in 8..16) add(v, false, now)
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

    private fun add(v: String, barcode: Boolean, now: Long) {
        synchronized(candidates) {
            val c = candidates[v]
            if (c == null) candidates[v] = Candidate(v, barcode, now, 1)
            else { c.lastSeen = now; c.hits++ }
        }
    }

    private fun showCandidates() {
        val now = SystemClock.elapsedRealtime()
        val shown = synchronized(candidates) {
            // Glem numre, der ikke er set i 6 sekunder; stregkoder og ofte sete tal øverst
            candidates.values.removeAll { now - it.lastSeen > 6000 }
            candidates.values.sortedWith(compareByDescending<Candidate> { it.barcode }.thenByDescending { it.hits }).take(5)
        }
        list.removeAllViews()
        val fg = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#404040")
        val key = if (dark) Color.parseColor("#4B4B4B") else Color.parseColor("#E0E0E0")
        for (c in shown) {
            list.addView(TextView(this).apply {
                text = c.value + if (c.barcode) "   · stregkode" else "   · tal"
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

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        try { barcodes.close(); texts.close() } catch (_: Exception) {}
    }
}
