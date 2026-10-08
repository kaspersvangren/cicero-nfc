package dk.kasvan.ciceronfc

import android.util.Base64
import org.json.JSONObject

/**
 * Indholdet af et bibliotekstag i den danske RFID-datamodel (32 bytes = blok 0-7).
 *
 *  [0]      version (4 bit) + type (4 bit), altid 0x11
 *  [1]      i go-feig kaldet "NumItems"
 *  [2]      i go-feig kaldet "SeqNum"
 *  [3:19]   materialenummer, ASCII, nul-udfyldt
 *  [19:21]  CRC
 *  [21:23]  landekode ("DK")
 *  [23:32]  biblioteksnummer (ISIL), ASCII, nul-udfyldt
 *
 * Felterne navngives og placeres PRÆCIS som i Deichmans go-feig, fordi det er
 * den JSON, Ciceros "Deichman"-integration er bygget mod.
 */
class TagContent(
    val seqNum: Int,
    val numItems: Int,
    val barcode: String,
    val crc: ByteArray?,
    val country: String,
    val library: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("SeqNum", seqNum)
        put("NumItems", numItems)
        put("Barcode", barcode)
        put("Crc", crc?.let { b64(it) } ?: JSONObject.NULL)
        put("Country", country)
        put("Library", library)
    }

    companion object {
        fun empty() = TagContent(0, 0, "", null, "", "")

        fun parse(b: ByteArray): TagContent {
            require(b.size >= 32) { "for få bytes (${b.size})" }
            return TagContent(
                seqNum = b[2].toInt() and 0xFF,
                numItems = b[1].toInt() and 0xFF,
                barcode = ascii(b, 3, 19),
                crc = b.copyOfRange(19, 21),
                country = ascii(b, 21, 23),
                library = ascii(b, 23, 32),
            )
        }

        /** Bygger de 32 bytes, der skrives til blok 0-7, inkl. CRC. */
        fun build(barcode: String, numItems: Int, seqNum: Int, country: String, library: String): ByteArray {
            require(barcode.isNotEmpty() && barcode.length <= 16) { "materialenummer skal være 1-16 tegn" }
            require(barcode.all { it.code in 0x20..0x7E }) { "materialenummer må kun indeholde almindelige tegn" }
            val bs = ByteArray(36)
            bs[0] = 0x11
            bs[1] = numItems.toByte()
            bs[2] = seqNum.toByte()
            barcode.toByteArray(Charsets.US_ASCII).copyInto(bs, 3)
            country.take(2).toByteArray(Charsets.US_ASCII).copyInto(bs, 21)
            library.take(9).toByteArray(Charsets.US_ASCII).copyInto(bs, 23)
            // CRC beregnes over bytes 0-18 og 21-33 (32 bytes), som i go-feig.
            // Kontrolleret mod to rigtige tags fra Gladsaxe.
            val cs = ByteArray(32)
            bs.copyInto(cs, 0, 0, 19)
            bs.copyInto(cs, 19, 21, 34)
            crc16(cs).copyInto(bs, 19)
            return bs.copyOf(32)
        }

        fun crc16(data: ByteArray): ByteArray {
            var crc = 0xFFFF
            for (v in data) {
                crc = crc xor ((v.toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                    crc = crc and 0xFFFF
                }
            }
            return byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
        }

        private fun ascii(b: ByteArray, from: Int, to: Int): String =
            String(b, from, to - from, Charsets.ISO_8859_1).trimEnd('\u0000')

        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
