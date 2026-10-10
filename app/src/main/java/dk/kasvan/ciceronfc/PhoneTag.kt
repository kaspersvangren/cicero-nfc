package dk.kasvan.ciceronfc

import android.nfc.Tag
import android.nfc.tech.NfcV
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Et ISO 15693-tag (ICODE SLIX/SLIX2 m.fl.) læst med telefonens NFC.
 * Hvert PhoneTag bruges kun fra én tråd ad gangen (Hub's eller værktøjets arbejdstråd).
 */
class PhoneTag(tag: Tag) {
    companion object {
        // 0x20 læs blok, 0x21 skriv blok, 0x23 læs flere blokke, 0x27 skriv AFI, 0x2B systeminfo
        private val ALLOWED = setOf(0x20, 0x21, 0x23, 0x27, 0x2B)
    }

    private val nfcv: NfcV = NfcV.get(tag) ?: throw IOException("Ikke et ISO 15693-tag")

    /** UID som tagget vil have det i kommandoer (mindst betydende byte først). */
    private val uidLsb: ByteArray

    /** UID som TagInfo og go-feig viser det (E0:04:...). */
    val uidMsb: ByteArray

    init {
        val id = tag.id
        if ((id.last().toInt() and 0xFF) == 0xE0) {
            uidLsb = id
            uidMsb = id.reversedArray()
        } else {
            uidMsb = id
            uidLsb = id.reversedArray()
        }
    }

    val mac: String = uidMsb.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    fun close() {
        try { nfcv.close() } catch (_: Exception) {}
    }

    private fun cmd(flags: Int, code: Int, vararg extra: Byte): ByteArray {
        // Sikkerhedsnet: kun læse- og skrivekommandoer. Lås-kommandoerne (0x22 lås blok, 0x28 lås AFI,
        // 0x2A lås DSFID m.fl.) kan ikke fortrydes og må aldrig sendes – heller ikke ved en fejl i koden.
        require(code in ALLOWED) { "kommando 0x%02X er ikke tilladt".format(code) }
        if (!nfcv.isConnected) nfcv.connect()
        val b = ByteArray(10 + extra.size)
        b[0] = flags.toByte()
        b[1] = code.toByte()
        uidLsb.copyInto(b, 2)
        extra.copyInto(b, 10)
        val r = nfcv.transceive(b)
        if (r.isEmpty()) throw IOException("tomt svar fra tag")
        if (r[0].toInt() and 0x01 != 0) {
            val err = if (r.size > 1) r[1].toInt() and 0xFF else 0
            throw IOException("tag svarede med fejl 0x%02X".format(err))
        }
        return r
    }

    class SystemInfo(val dsfid: Int?, val afi: Int?)

    fun systemInfo(): SystemInfo {
        val r = cmd(0x22, 0x2B)
        val info = r[1].toInt()
        var i = 10
        var dsfid: Int? = null
        var afi: Int? = null
        if (info and 0x01 != 0 && r.size > i) dsfid = r[i++].toInt() and 0xFF
        if (info and 0x02 != 0 && r.size > i) afi = r[i].toInt() and 0xFF
        return SystemInfo(dsfid, afi)
    }

    fun readBlocks(first: Int, count: Int): ByteArray {
        try {
            val r = cmd(0x22, 0x23, first.toByte(), (count - 1).toByte())
            if (r.size >= 1 + count * 4) return r.copyOfRange(1, 1 + count * 4)
        } catch (_: IOException) {
            // Faldt tilbage til at læse én blok ad gangen
        }
        val out = ByteArrayOutputStream()
        for (i in 0 until count) {
            val r = cmd(0x22, 0x20, (first + i).toByte())
            if (r.size < 5) throw IOException("kort svar ved læsning af blok ${first + i}")
            out.write(r, 1, 4)
        }
        return out.toByteArray()
    }

    fun writeBlocks(data: ByteArray) {
        for (i in 0 until data.size / 4) {
            val blk = data.copyOfRange(i * 4, i * 4 + 4)
            withOptionFallback { flags -> cmd(flags, 0x21, i.toByte(), *blk) }
        }
    }

    fun writeAfi(value: Int) {
        withOptionFallback { flags -> cmd(flags, 0x27, value.toByte()) }
        val check = systemInfo().afi
        if (check != null && check != value) {
            throw IOException("alarm blev ikke ændret (læst 0x%02X)".format(check))
        }
    }

    /** Til tilstedeværelseskontrol: læs blok 0. Kaster IOException hvis tagget er væk. */
    fun ping() {
        cmd(0x22, 0x20, 0)
    }

    // NXP-tags skriver uden "option flag"; enkelte andre fabrikater kræver det.
    private fun withOptionFallback(f: (Int) -> Unit) {
        try {
            f(0x22)
        } catch (e: IOException) {
            f(0x62)
        }
    }
}
