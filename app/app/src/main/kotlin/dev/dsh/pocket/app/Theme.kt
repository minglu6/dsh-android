package dev.dsh.pocket.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Live theme mirror of desktop `body` / `body[data-ds-dark-theme]` alias sheets.
 * Updated by [PocketTheme] so non-composable `T.*` getters stay in sync with Appearance.
 */
object ThemeState {
    @Volatile
    var dark: Boolean = false
        private set

    fun setDark(value: Boolean) {
        dark = value
    }

    val colors: DswColors
        get() = if (dark) DswColors.Dark else DswColors.Light
}

/**
 * App chrome tokens mapped from desktop `--dsw-alias-*` / `--dsw-specific-*`
 * (and `--dsw-radius-*` / `--ds-*` radii from base.css). Colors follow [ThemeState].
 */
object T {
    private val c get() = ThemeState.colors

    /** `--dsw-alias-label-primary` */
    val Ink get() = c.LabelPrimary
    /** `--dsw-alias-button-info-fill` / business primary (theme-aware). */
    val Accent get() = c.ButtonInfoFill
    /** Dark-scheme info fill (deepseek-400); kept for callers that need the dark accent explicitly. */
    val AccentDark get() = DswColors.Dark.ButtonInfoFill
    /** `--dsw-alias-state-business-tertiary` */
    val Tint get() = c.StateBusinessTertiary
    /** `--dsw-alias-label-secondary` */
    val Text2 get() = c.LabelSecondary
    /** `--dsw-alias-label-tertiary` */
    val Text3 get() = c.LabelTertiary
    /** `--dsw-alias-label-caption` */
    val Caption get() = c.LabelCaption
    /** `--dsw-alias-interactive-bg-hover-solid` */
    val Fill get() = c.InteractiveBgHoverSolid
    /** `--dsw-alias-bg-module-platform` / selector fill */
    val Fill2 get() = c.BgModulePlatform
    /** `--dsw-specific-sidebar-fill` */
    val Drawer get() = c.SpecificsidebarFill
    /** `--dsw-alias-border-l2` */
    val Border get() = c.BorderL2
    /** `--dsw-alias-state-warn-secondary` */
    val WarnBorder get() = c.StateWarnSecondary
    /** `--dsw-alias-state-warn-tertiary` */
    val WarnTint get() = c.StateWarnTertiary
    /** `--dsw-alias-state-warn-label` */
    val WarnText get() = c.StateWarnLabel
    /** `--dsw-alias-bg-mask-1` */
    val Scrim get() = c.BgMask1
    /** `--dsw-alias-label-primary-bluish` */
    val Bluish get() = c.LabelPrimaryBluish
    /** `--dsw-alias-interactive-bg-hover` */
    val HoverBg get() = c.InteractiveBgHover
    /** `--dsw-alias-state-success-primary` */
    val Ok get() = c.StateSuccessPrimary
    /** `--dsw-alias-state-error-primary` */
    val Err get() = c.StateErrorPrimary
    /** `--dsw-alias-bg-base` */
    val Bg get() = c.BgBase
    /** `--dsw-alias-bg-layer-1` */
    val Surface get() = c.BgLayer1
    /** `--dsw-alias-bg-layer-2` */
    val Surface2 get() = c.BgLayer2
    /** `--dsw-alias-link` */
    val Link get() = c.Link
    /** `--dsw-specific-bubble` */
    val Bubble get() = c.Specificbubble
    /** `--dsw-alias-button-primary-fill` */
    val PrimaryButton get() = c.ButtonPrimaryFill
    /** `--dsw-alias-label-primary-foreground` */
    val OnPrimary get() = c.LabelPrimaryForeground
    /** `--dsw-alias-markdown-code-block` */
    val CodeBlock get() = c.MarkdownCodeBlock
    /** `--dsw-alias-bg-document-preview` */
    val DocumentPreviewBg get() = c.BgDocumentPreview

    // `--dsw-radius-*` / panel from base.css
    val R4 = RoundedCornerShape(4.dp)
    val R8 = RoundedCornerShape(8.dp)
    val R12 = RoundedCornerShape(12.dp)
    val R16 = RoundedCornerShape(16.dp)
    val R20 = RoundedCornerShape(20.dp)
    val R28 = RoundedCornerShape(28.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Meslo Nerd Font — same code face as desktop / terminal (Tokyo Night blocks). */
    val Mono get() = TextStyle(fontFamily = MesloNerdFamily, fontSize = 13.sp, color = Ink)
}

/** Resolve Appearance pref + system setting into a dark flag. */
fun resolveDarkAppearance(appearance: Appearance, systemDark: Boolean): Boolean = when (appearance) {
    Appearance.Dark -> true
    Appearance.System -> systemDark
    Appearance.Light -> false
}

/**
 * Tokyo Night chrome for the phone connection / pairing surface
 * (peer tabs, address sheets). Meslo via [T.Mono] / [Conn.Mono].
 */
object Conn {
    val Bg = TokyoNight.Bg
    val Surface = Color(0xFF24283B)
    val Elevated = Color(0xFF1F2335)
    val Fg = TokyoNight.Fg
    val FgDim = Color(0xFFA9B1D6)
    val FgMute = Color(0xFF565F89)
    val Accent = Color(0xFF7AA2F7)
    val Border = Color(0xFF414868)
    val TabIdle = Color(0xFF1F2335)
    val Ok = Color(0xFF9ECE6A)
    val Err = Color(0xFFF7768E)
    val Warn = Color(0xFFE0AF68)
    val Scrim = Color(0x99000000)
    val Mono = TextStyle(fontFamily = MesloNerdFamily, fontSize = 13.sp, color = FgDim)
    val R12 = RoundedCornerShape(12.dp)
    val R16 = RoundedCornerShape(16.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    val appearance = runCatching { Pocket.prefs.appearance }.getOrDefault(Appearance.Light)
    val dark = resolveDarkAppearance(appearance, isSystemInDarkTheme())
    SideEffect { ThemeState.setDark(dark) }
    // Also set immediately so the first frame's T.* reads are correct (SideEffect runs after).
    ThemeState.setDark(dark)
    val colors = if (dark) DswColors.Dark else DswColors.Light
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = colors.ButtonInfoFill,
            onPrimary = colors.LabelPrimaryForeground,
            background = colors.BgBase,
            surface = colors.BgLayer1,
            onSurface = colors.LabelPrimary,
            onBackground = colors.LabelPrimary,
            surfaceVariant = colors.InteractiveBgHoverSolid,
            onSurfaceVariant = colors.LabelSecondary,
            outline = colors.BorderL2,
            outlineVariant = colors.BorderL3,
            error = colors.StateErrorPrimary,
            surfaceContainerLow = colors.BgLayer1,
            surfaceContainer = colors.BgLayer2,
            surfaceContainerHigh = colors.BgLayer3,
        ) else lightColorScheme(
            primary = colors.ButtonInfoFill,
            onPrimary = colors.LabelPrimaryForeground,
            background = colors.BgBase,
            surface = colors.BgLayer1,
            onSurface = colors.LabelPrimary,
            onBackground = colors.LabelPrimary,
            surfaceVariant = colors.InteractiveBgHoverSolid,
            onSurfaceVariant = colors.LabelSecondary,
            outline = colors.BorderL2,
            outlineVariant = colors.BorderL3,
            error = colors.StateErrorPrimary,
            surfaceContainerLow = colors.BgBase,
            surfaceContainer = colors.BgBase,
            surfaceContainerHigh = colors.BgBase,
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 13.sp),
        ),
        content = content,
    )
}
