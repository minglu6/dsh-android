package dev.dsh.pocket.app

import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.ui.text.font.FontFamily
import java.util.Locale

private val genericFontNames = setOf(
    "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui", "ui-serif", "ui-sans-serif",
    "ui-monospace", "ui-rounded", "emoji", "math", "fangsong", "-apple-system", "blinkmacsystemfont",
)
private val unsafeFontCharacters = Regex("[\"'\\\\<>\\u0000-\\u001f\\u007f]")
private val fontWhitespace = Regex("\\s+")

/** Same persisted comma-separated list as desktop; empty selects the built-in stack. */
internal fun normalizeFontFamily(value: String): String = value.split(',').mapNotNull { item ->
    val name = item.replace(unsafeFontCharacters, "").replace(fontWhitespace, " ").trim()
    when {
        name.isEmpty() -> null
        name.lowercase(Locale.ROOT) in genericFontNames -> name
        else -> "\"$name\""
    }
}.joinToString(", ")

/** Android resolves installed family names only, never downloads fonts or uses Host fonts. */
internal object LocalFonts {
    private val cache = LruCache<String, FontFamily>(24)
    val text: FontFamily get() = resolve(Pocket.prefs.textFontFamily, false)
    val code: FontFamily get() = resolve(Pocket.prefs.codeFontFamily, true)
    val terminal: FontFamily get() = resolve(Pocket.prefs.terminalFontFamily, true)

    fun resolve(value: String, monospaceDefault: Boolean): FontFamily {
        if (value.isBlank()) return if (monospaceDefault) MesloNerdFamily else FontFamily.Default
        val key = "$monospaceDefault:$value"
        cache.get(key)?.let { return it }
        val family = value.split(',').firstNotNullOfOrNull { entry ->
            val name = entry.trim().trim('"', '\'')
            when (name.lowercase(Locale.ROOT)) {
                "serif", "ui-serif", "math", "fangsong" -> FontFamily.Serif
                "sans-serif", "system-ui", "ui-sans-serif", "ui-rounded", "-apple-system", "blinkmacsystemfont", "emoji" -> FontFamily.SansSerif
                "monospace", "ui-monospace" -> FontFamily.Monospace
                "cursive", "fantasy" -> FontFamily.Cursive
                "meslolgl dz", "meslolgl dz nerd font", "meslolgl dz nerd font mono" -> MesloNerdFamily
                else -> Typeface.create(name, Typeface.NORMAL).takeUnless { it == Typeface.DEFAULT }?.let { FontFamily(it) }
            }
        } ?: if (monospaceDefault) MesloNerdFamily else FontFamily.Default
        cache.put(key, family)
        return family
    }
}
