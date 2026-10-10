package dk.kasvan.ciceronfc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Små bip med præcis tonehøjde, så "alarm fra" (to stigende) og "alarm til" (to faldende, "ka-pling")
 * kan skelnes på lyden alene. Afspilles som medie, så de høres i lydløs tilstand.
 */
object Beeper {
    private const val RATE = 44_100

    /** Slås fra i Indstillinger (fx når telefonen spiller over bluetooth); vibrationen påvirkes ikke */
    @Volatile var enabled = true

    class Note(val atMs: Int, val freq: Double, val ms: Int)

    /**
     * Klokkeagtigt "ka-pling": hver tone har et hurtigt anslag og klinger ud,
     * med svage overtoner, så det lyder som en lille klokke frem for et bip.
     * Toner må overlappe, så den første stadig klinger, når den næste slår an.
     */
    fun chime(vararg notes: Note) {
        if (!enabled) return
        thread(isDaemon = true) {
            try {
                val totalMs = notes.maxOf { it.atMs + it.ms }
                val mix = DoubleArray(totalMs * RATE / 1000)
                for (n in notes) {
                    val start = n.atMs * RATE / 1000
                    val len = n.ms * RATE / 1000
                    val attack = RATE / 250 // 4 ms
                    val tau = len / 4.0
                    for (i in 0 until len) {
                        if (start + i >= mix.size) break
                        val t = i.toDouble() / RATE
                        val env = (if (i < attack) i.toDouble() / attack else 1.0) * kotlin.math.exp(-i / tau)
                        val w = 2 * PI * n.freq * t
                        val v = sin(w) + 0.35 * sin(2 * w) + 0.12 * sin(3 * w) + 0.05 * sin(4.2 * w)
                        mix[start + i] += v * env
                    }
                }
                val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
                val pcm = ShortArray(mix.size) { (mix[it] / peak * 0.7 * Short.MAX_VALUE).toInt().toShort() }
                playPcm(pcm, totalMs)
            } catch (e: Exception) {
                LogBuf.add("Lyd fejlede: ${e.message}")
            }
        }
    }

    private fun playPcm(pcm: ShortArray, totalMs: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(totalMs.toLong() + 150)
        track.release()
    }

    /** Par af (frekvens i Hz, varighed i ms). Frekvens 0 = pause. */
    fun play(vararg tones: Pair<Int, Int>) {
        if (!enabled) return
        thread(isDaemon = true) {
            try {
                val total = tones.sumOf { it.second } * RATE / 1000
                val pcm = ShortArray(total)
                var pos = 0
                for ((freq, ms) in tones) {
                    val n = ms * RATE / 1000
                    val fade = min(n / 4, RATE / 200) // 5 ms ind/ud, så det ikke klikker
                    for (i in 0 until n) {
                        if (freq > 0) {
                            val env = when {
                                i < fade -> i.toDouble() / fade
                                i > n - fade -> (n - i).toDouble() / fade
                                else -> 1.0
                            }
                            pcm[pos + i] = (sin(2 * PI * freq * i / RATE) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
                        }
                    }
                    pos += n
                }
                playPcm(pcm, tones.sumOf { it.second })
            } catch (e: Exception) {
                LogBuf.add("Bip fejlede: ${e.message}")
            }
        }
    }
}
