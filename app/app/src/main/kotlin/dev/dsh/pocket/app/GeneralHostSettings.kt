package dev.dsh.pocket.app

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.dsh.pocket.protocol.bool
import kotlinx.serialization.json.JsonPrimitive

/** Uses the same live, revisioned Host mirror as every other durable general setting. */
@Composable
internal fun SessionLogUploadPreference() {
    val shared = Pocket.prefs.hostSettings
    if (shared.namespace(SharedSetting.SessionLog) == null) return
    GeneralPreferenceRow(
        st("在使用官方模型 API 时上传 Session Log", "Upload Session Log when using the official model API"),
        st("帮助改进 DeepSeek 模型与产品", "Help improve DeepSeek models and products."),
        divider = true,
        trailing = {
            Switch(shared.value(SharedSetting.SessionLog).bool() == true, { enabled ->
                shared.set(SharedSetting.SessionLog, JsonPrimitive(enabled))
            }, enabled = shared.writable(SharedSetting.SessionLog), modifier = Modifier.testTag("sessionLogUploadSwitch"),
                colors = SwitchDefaults.colors(checkedTrackColor = T.PrimaryButton, checkedThumbColor = T.OnPrimary))
        },
    )
}
