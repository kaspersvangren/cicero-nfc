package dk.kasvan.ciceronfc

import android.graphics.Color

/** Farver som Cicero (lys/mørk) til værktøjets skærme: Værktøj til tags og kameraet. */
class ToolColors(dark: Boolean) {
    val bg = Color.parseColor(if (dark) "#2E2E2E" else "#F2F5F7")
    val card = Color.parseColor(if (dark) "#383838" else "#FFFFFF")
    val fg = Color.parseColor(if (dark) "#F2F2F2" else "#404040")
    val sub = Color.parseColor(if (dark) "#C6C6C6" else "#767676")
    val key = Color.parseColor(if (dark) "#4B4B4B" else "#E0E0E0")
    val blue = Color.parseColor(if (dark) "#3098E8" else "#0078D3")
    val tile = Color.parseColor(if (dark) "#454545" else "#F2F5F7")
    val warnBg = Color.parseColor(if (dark) "#5A2626" else "#FDE8E8")
    val warnFg = Color.parseColor(if (dark) "#FEB2B2" else "#9B2C2C")
    val green = Color.parseColor("#2F855A")
    val orange = Color.parseColor("#DD6B20")
    val red = Color.parseColor("#C53030")
}
