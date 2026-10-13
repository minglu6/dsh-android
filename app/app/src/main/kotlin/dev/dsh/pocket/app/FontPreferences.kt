package dev.dsh.pocket.app

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
