package dk.kasvan.ciceronfc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Tjekker GitHub for en nyere version og installerer den, når brugeren beder om det.
 * Der sendes ingen data – app'en spørger kun "hvad er seneste version?".
 * Android installerer kun en opdatering med samme segl som den installerede app,
 * så en manipuleret fil kan ikke komme ind ad denne vej.
 */
object Updater {
    private const val API = "https://api.github.com/repos/kaspersvangren/cicero-nfc/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/kaspersvangren/cicero-nfc/releases/download/"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    class Info(val code: Long, val name: String, val url: String, val notes: String)

    @Volatile var available: Info? = null
        private set

    /** -1 = intet i gang, 0..100 = henter */
    @Volatile var progress: Int = -1
        private set

    @Volatile var listener: (() -> Unit)? = null

    @Volatile private var lastCheck = 0L
    @Volatile private var checking = false

    fun currentCode(ctx: Context): Long {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }

    /** Tjek højst hver 6. time – eller med det samme, hvis [force]. [onDone] kaldes på en baggrundstråd. */
    fun check(ctx: Context, force: Boolean = false, onDone: ((Info?, String?) -> Unit)? = null) {
        val now = SystemClock.elapsedRealtime()
        if (checking) return
        if (!force && lastCheck != 0L && now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        checking = true
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "update-check") {
            var error: String? = null
            try {
                val c = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "CiceroNFC")
                }
                if (c.responseCode != 200) throw IOException("GitHub svarede ${c.responseCode}")
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = j.getString("tag_name")
                val code = Regex("""^v\d+\.\d+\.(\d+)$""").find(tag)?.groupValues?.get(1)?.toLongOrNull()
                    ?: throw IOException("ukendt versionsnummer $tag")
                var url: String? = null
                val assets = j.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name") == "CiceroNFC.apk") url = a.getString("browser_download_url")
                }
                if (url == null || !url.startsWith(DOWNLOAD_PREFIX)) throw IOException("ingen app-fil i udgivelsen")
                // Kort ændringsliste fra udgivelsen (uden byggelinjen), højst 600 tegn
                val notes = j.optString("body", "").lines()
                    .filterNot { it.startsWith("Bygget fra") }
                    .joinToString("\n").trim().take(600)
                available = if (code > currentCode(app)) Info(code, tag.removePrefix("v"), url, notes) else null
                available?.let { LogBuf.add("Ny version fundet: ${it.name}") }
            } catch (e: Exception) {
                error = e.message ?: "ukendt fejl"
                LogBuf.add("Opdateringstjek fejlede: $error")
            } finally {
                checking = false
            }
            listener?.invoke()
            onDone?.invoke(available, error)
        }
    }

    /** Hent den nye version og bed Android installere den. Android spørger brugeren først. */
    fun downloadAndInstall(ctx: Context, info: Info) {
        if (progress >= 0) return
        val app = ctx.applicationContext
        progress = 0
        listener?.invoke()
        thread(isDaemon = true, name = "update-download") {
            val f = File(app.cacheDir, "update.apk")
            try {
                val c = (URL(info.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "CiceroNFC")
                }
                if (c.responseCode != 200) throw IOException("GitHub svarede ${c.responseCode}")
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt().coerceIn(0, 99)
                                if (p != progress) {
                                    progress = p
                                    listener?.invoke()
                                }
                            }
                        }
                    }
                }
                LogBuf.add("Opdatering ${info.name} hentet – beder Android installere")
                install(app, f)
            } catch (e: Exception) {
                LogBuf.add("Opdatering fejlede: ${e.message}")
                f.delete()
            } finally {
                progress = -1
                listener?.invoke()
            }
        }
    }

    private fun install(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("CiceroNFC.apk", 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val p = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(p.intentSender)
        }
    }

    /** Kaldes ved opstart: ryd en gammel hentet fil op. */
    fun cleanup(ctx: Context) {
        File(ctx.cacheDir, "update.apk").delete()
    }
}

/** Android melder tilbage om installationen. Første besked er "spørg brugeren". */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> LogBuf.add("Opdatering installeret")
            PackageInstaller.STATUS_FAILURE_ABORTED -> LogBuf.add("Opdatering annulleret")
            else -> LogBuf.add("Opdatering fejlede: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
