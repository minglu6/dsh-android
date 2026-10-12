package dev.dsh.pocket.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** dsh web UI design tokens (see docs/mockups, gen2.py). */
object T {
    val Ink = Color(0xFF0F1115)
    val Accent = Color(0xFF3964FE)
    val Tint = Color(0xFFE4EDFD)
    val SendDisabled = Color(0xFFB4C4F4)
    val Text2 = Color(0xFF61666B)
    val Text3 = Color(0xFF81858C)
    val Caption = Color(0xFFADB2B8)
    val Fill = Color(0xFFF1F3F5)
    val Fill2 = Color(0xFFF5F6F7)
    val Drawer = Color(0xFFF8F9FB)
    val Border = Color(0xFFE5E7EB)
    val WarnBorder = Color(0xFFF7AD31)
    val WarnTint = Color(0xFFFEF5E7)
    val WarnText = Color(0xFFB7791F)
    /** Sheet backdrop: ink at 28%, matches the mockups (not M3's flat 32% black). */
    val Scrim = Color(0x470F1115)
    val Bluish = Color(0xFF0E3074)          // --dsw-alias-label-primary-bluish (blue-900)
    val HoverBg = Color(0x0F263148)         // --dsw-alias-interactive-bg-hover
    val Ok = Color(0xFF22C55E)
    val Err = Color(0xFFEC1313)
    val R4 = RoundedCornerShape(4.dp); val R8 = RoundedCornerShape(8.dp); val R12 = RoundedCornerShape(12.dp)
    val R16 = RoundedCornerShape(16.dp); val R20 = RoundedCornerShape(20.dp); val R28 = RoundedCornerShape(28.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Ink)
}

@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = T.Accent, onPrimary = Color.White, background = Color.White, surface = Color.White,
            onSurface = T.Ink, onBackground = T.Ink, surfaceVariant = T.Fill, onSurfaceVariant = T.Text2,
            outline = T.Border, outlineVariant = T.Border, error = T.Err, surfaceContainerLow = Color.White,
            surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 13.sp),
        ),
        // Typography carries no colors: a color here leaks into LocalTextStyle and beats Button content colors
        // (that made the black primary buttons render black-on-black).
        content = content,
    )
}
