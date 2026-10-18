package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Display-only marker matching ui-deepseek-model-label-1017, never part of the model route. */
internal fun modelReleaseBadge(provider: String?, model: String?, name: String?): String? =
    if ((provider == "deepseek-official" || provider == "deepseek-account") &&
        model == "deepseek-flash" && name == "DeepSeek-V4.1-Flash"
    ) "1017" else null

/** Keep the badge next to the name; narrow layouts ellipsize the name, not the release marker. */
@Composable
internal fun ModelLabel(
    name: String,
    badge: String?,
    modifier: Modifier = Modifier,
    color: Color = T.Ink,
    nameModifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            name, fontSize = 14.sp, lineHeight = 20.sp, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).then(nameModifier),
        )
        if (badge != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.testTag("modelReleaseBadge")
                    .background(T.Ok.copy(alpha = 0.1f * color.alpha), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            ) {
                Text(
                    badge, fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
                    color = T.Ok.copy(alpha = color.alpha), maxLines = 1,
                    // Like desktop, accessibility keeps the original catalog name.
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}
