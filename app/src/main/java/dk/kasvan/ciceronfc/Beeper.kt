package dk.kasvan.ciceronfc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Små bip med præcis tonehøjde, så "alarm fra" (to stigende) og "alarm til" (ét dybt)
 * kan skelnes på lyden alene. Afspilles som medie, så de høres i lydløs tilstand.
 */
object Beeper {
    private const val RATE = 44_100

    /** Par af (frekvens i Hz, varighed i ms). Frekvens 0 = pause. */
    fun play(vararg tones: Pair<Int, Int>) {
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
                Thread.sleep(tones.sumOf { it.second }.toLong() + 150)
                track.release()
            } catch (e: Exception) {
                LogBuf.add("Bip fejlede: ${e.message}")
            }
        }
    }
}
