package dev.dsh.pocket.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

/** Icons converted 1:1 from deepseek-harness ui-primitives (16px grid, 1px stroke "Regular" weight). */
object D {
    private val v: @Composable (Int) -> ImageVector = { ImageVector.vectorResource(it) }
    val ellipsis @Composable get() = v(R.drawable.dsh_ellipsis)
    val panelRight @Composable get() = v(R.drawable.dsh_panel_right)
    val users @Composable get() = v(R.drawable.dsh_users)
    val agentPreset @Composable get() = v(R.drawable.dsh_agent_preset)
    val folderOpen @Composable get() = v(R.drawable.dsh_folder_open)
    val shield @Composable get() = v(R.drawable.dsh_shield)
    val warning @Composable get() = v(R.drawable.dsh_warning)
    val plus @Composable get() = v(R.drawable.dsh_plus)
    val chevronDown @Composable get() = v(R.drawable.dsh_chevron_down)
    val chevronRight @Composable get() = v(R.drawable.dsh_chevron_right)
    val chevronLeft @Composable get() = v(R.drawable.dsh_chevron_left)
    val newChat @Composable get() = v(R.drawable.dsh_new_chat)
    val plugin @Composable get() = v(R.drawable.dsh_cordis_plugin)
    val clock @Composable get() = v(R.drawable.dsh_clock)
    val settings @Composable get() = v(R.drawable.dsh_settings)
    val search @Composable get() = v(R.drawable.dsh_search)
    val database @Composable get() = v(R.drawable.dsh_database)
    val check @Composable get() = v(R.drawable.dsh_check)
    val paperclip @Composable get() = v(R.drawable.dsh_paperclip)
    val goal @Composable get() = v(R.drawable.dsh_goal)
    val plan @Composable get() = v(R.drawable.dsh_plan)
    val send @Composable get() = v(R.drawable.dsh_send)
    val compact @Composable get() = v(R.drawable.dsh_compact)
    val download @Composable get() = v(R.drawable.dsh_download)
    val edit @Composable get() = v(R.drawable.dsh_edit)
    val trash @Composable get() = v(R.drawable.dsh_trash)
    val user @Composable get() = v(R.drawable.dsh_user)
    val link @Composable get() = v(R.drawable.dsh_link)
    val globe @Composable get() = v(R.drawable.dsh_globe)
    val think @Composable get() = v(R.drawable.dsh_think)
    val rightUp @Composable get() = v(R.drawable.dsh_right_up)
    val code @Composable get() = v(R.drawable.dsh_code)
    val chat @Composable get() = v(R.drawable.dsh_chat_lines)
    val inspect @Composable get() = v(R.drawable.dsh_inspect)
    val fish @Composable get() = v(R.drawable.dsh_fish)
    val wordmark @Composable get() = v(R.drawable.dsh_wordmark)
}
