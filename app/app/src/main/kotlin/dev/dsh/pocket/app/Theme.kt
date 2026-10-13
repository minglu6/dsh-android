package dev.dsh.pocket.app

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
    /** `--dsw-alias-settings-card-fill` */
    val SettingsCard get() = c.SettingsCardFill
    /** `--dsw-alias-settings-card-stroke` */
    val SettingsCardStroke get() = c.SettingsCardStroke
    /** `--dsw-specific-login-input` / settings field fill */
    val Input get() = c.SpecificloginInput
    /** `--dsw-specific-input-major` */
    val InputMajor get() = c.SpecificinputMajor
    /** `--dsw-alias-border-l4` (hairlines inside settings cards) */
    val BorderStrong get() = c.BorderL4
    /** `--dsw-alias-bg-layer-3` */
    val Surface3 get() = c.BgLayer3

    // `--dsw-radius-*` / panel from base.css
    val R4 = RoundedCornerShape(4.dp)
    val R8 = RoundedCornerShape(8.dp)
    val R12 = RoundedCornerShape(12.dp)
    val R16 = RoundedCornerShape(16.dp)
    val R20 = RoundedCornerShape(20.dp)
    val R28 = RoundedCornerShape(28.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Meslo Nerd Font — same code face as desktop / terminal. */
    val Mono get() = TextStyle(fontFamily = MesloNerdFamily, fontSize = 13.sp, color = Ink)
}

/** Resolve Appearance pref + system setting into a dark flag. */
fun resolveDarkAppearance(appearance: Appearance, systemDark: Boolean): Boolean = when (appearance) {
    Appearance.Dark -> true
    Appearance.System -> systemDark
    Appearance.Light -> false
}

/**
 * Connection / pairing chrome — aliases of desktop `--dsw-alias-*` via [T] / [ThemeState].
 * Kept as a named surface so Screens/Sheets call sites stay readable; never a separate palette.
 */
object Conn {
    val Bg get() = T.Bg
    val Surface get() = T.Surface
    /** Field / elevated inset — `--dsw-specific-login-input`. */
    val Elevated get() = T.Input
    val Fg get() = T.Ink
    val FgDim get() = T.Text2
    val FgMute get() = T.Text3
    val Accent get() = T.Accent
    val Border get() = T.Border
    val TabIdle get() = T.Fill2
    val Ok get() = T.Ok
    val Err get() = T.Err
    val Warn get() = T.WarnText
    val Scrim get() = T.Scrim
    val Mono get() = T.Mono.copy(color = FgDim)
    val R12 get() = T.R12
    val R16 get() = T.R16
    val Sheet get() = T.Sheet
}

@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    val appearance = runCatching { Pocket.prefs.appearance }.getOrDefault(Appearance.Light)
    val dark = resolveDarkAppearance(appearance, isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect {
        ThemeState.setDark(dark)
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
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
