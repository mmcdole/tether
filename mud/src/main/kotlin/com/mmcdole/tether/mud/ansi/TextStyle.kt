package com.mmcdole.tether.mud.ansi

sealed interface AnsiColor {
    /** 0–15 are the theme's ANSI colors, 16–255 the xterm cube and greys. */
    data class Indexed(val index: Int) : AnsiColor

    /** 24-bit color as 0xRRGGBB. */
    data class Rgb(val rgb: Int) : AnsiColor
}

data class TextStyle(
    val foreground: AnsiColor? = null,
    val background: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
) {
    /** Bold with one of the first 8 colors shows as the bright version, like most MUD clients. */
    val effectiveForeground: AnsiColor?
        get() {
            val fg = foreground
            return if (bold && fg is AnsiColor.Indexed && fg.index < 8) AnsiColor.Indexed(fg.index + 8) else fg
        }

    companion object {
        val Default = TextStyle()
    }
}

object Xterm {
    /** The RGB value of an xterm-256 index from 16 to 255. */
    fun rgb(index: Int): Int {
        require(index in 16..255)
        return if (index < 232) {
            val i = index - 16
            val levels = intArrayOf(0, 95, 135, 175, 215, 255)
            (levels[i / 36] shl 16) or (levels[i / 6 % 6] shl 8) or levels[i % 6]
        } else {
            val v = 8 + (index - 232) * 10
            (v shl 16) or (v shl 8) or v
        }
    }
}
