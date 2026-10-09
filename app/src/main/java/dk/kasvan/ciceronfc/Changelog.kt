package dk.kasvan.ciceronfc

import android.content.Context

/**
 * Ændringslisten bygges ind i app'en (CHANGELOG.md fra repo'et, lagt i assets under bygningen),
 * så den kan vises uden net. Én linje pr. version: "- **0.1.27** – tekst".
 */
object Changelog {
    class Entry(val name: String, val code: Long, val text: String)

    private val LINE = Regex("""^- \*\*(\d+\.\d+\.(\d+))\*\* – (.+)$""")

    fun entries(ctx: Context): List<Entry> = try {
        ctx.assets.open("CHANGELOG.md").bufferedReader().use { r ->
            r.readLines().mapNotNull { line ->
                LINE.find(line.trim())?.let { m ->
                    Entry(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3].trim())
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
