package com.mmcdole.tether.ui.theme

import androidx.compose.ui.graphics.Color
import com.mmcdole.tether.mud.ansi.AnsiColor
import com.mmcdole.tether.mud.ansi.Xterm

/** The 16 ANSI colors tuned for a black background: normal ones softened, bright ones vivid. */
object AnsiColors {
    val palette: List<Color> = listOf(
        Color(0xFF4B4F57), // 30 black
        Color(0xFFE5534B), // 31 red
        Color(0xFF57C46B), // 32 green
        Color(0xFFE5C07B), // 33 yellow
        Color(0xFF61AFEF), // 34 blue
        Color(0xFFC678DD), // 35 magenta
        Color(0xFF56B6C2), // 36 cyan
        Color(0xFFDCDCDC), // 37 white
        Color(0xFF7A7F89), // 90 bright black
        Color(0xFFFF7B72), // 91 bright red
        Color(0xFF7EE787), // 92 bright green
        Color(0xFFFFE08A), // 93 bright yellow
        Color(0xFF8CC8FF), // 94 bright blue
        Color(0xFFE2A6F5), // 95 bright magenta
        Color(0xFF8BE3EE), // 96 bright cyan
        Color(0xFFFFFFFF), // 97 bright white
    )

    fun resolve(color: AnsiColor): Color = when (color) {
        is AnsiColor.Indexed -> if (color.index < 16) palette[color.index] else Color(0xFF000000.toInt() or Xterm.rgb(color.index))
        is AnsiColor.Rgb -> Color(0xFF000000.toInt() or color.rgb)
    }
}
