package dev.dsh.pocket.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

/** Official desktop SVG geometry; Regular controls and Medium settings-navigation artwork. */
object D {
    private val v: @Composable (Int) -> ImageVector = { ImageVector.vectorResource(it) }
    val ellipsis @Composable get() = v(R.drawable.dsh_ellipsis)
    val archive @Composable get() = v(R.drawable.dsh_archive)
    val unarchive @Composable get() = v(R.drawable.dsh_unarchive)
    val pin @Composable get() = v(R.drawable.dsh_pin)
    val pinFill @Composable get() = v(R.drawable.dsh_pin_fill)
    val panelRight @Composable get() = v(R.drawable.dsh_panel_right)
    val users @Composable get() = v(R.drawable.dsh_users)
    val agentPreset @Composable get() = v(R.drawable.dsh_agent_preset)
    val folderOpen @Composable get() = v(R.drawable.dsh_folder_open)
    val folderClose @Composable get() = v(R.drawable.dsh_folder_close)
    /** Official ReferenceIconMedium; candidate menus use the Regular glyphs. */
    val referenceFile @Composable get() = v(R.drawable.dsh_reference_file)
    val referenceFolder @Composable get() = v(R.drawable.dsh_reference_folder)
    val referenceSession @Composable get() = v(R.drawable.dsh_reference_session)
    val shield @Composable get() = v(R.drawable.dsh_shield)
    /** Same per-preset artwork as desktop PermissionSelect; custom presets have no glyph. */
    @Composable
    fun permission(preset: String): ImageVector? = when (preset) {
        "read-only" -> v(R.drawable.dsh_permission_read_only)
        "workspace-write" -> v(R.drawable.dsh_permission_workspace_write)
        "danger-full-access" -> v(R.drawable.dsh_permission_full_access)
        "auto" -> v(R.drawable.dsh_permission_auto_review)
        else -> null
    }
    val warning @Composable get() = v(R.drawable.dsh_warning)
    val plus @Composable get() = v(R.drawable.dsh_plus)
    val chevronDown @Composable get() = v(R.drawable.dsh_chevron_down)
    val chevronRight @Composable get() = v(R.drawable.dsh_chevron_right)
    val chevronLeft @Composable get() = v(R.drawable.dsh_chevron_left)
    val close @Composable get() = v(R.drawable.dsh_close)
    val newChat @Composable get() = v(R.drawable.dsh_new_chat)
    /** Sidebar Plugins panel glyph (ui-plugin-manager PluginsPanelIcon). */
    val pluginPinwheel @Composable get() = v(R.drawable.dsh_plugin_pinwheel)
    val clock @Composable get() = v(R.drawable.dsh_clock)
    /** TrajectoryTable and TrajectoryToolbar use their own official glyph geometry. */
    val trajectoryTool @Composable get() = v(R.drawable.dsh_trajectory_tool)
    val trajectoryContext @Composable get() = v(R.drawable.dsh_trajectory_context)
    val trajectoryCompacted @Composable get() = v(R.drawable.dsh_trajectory_compacted)
    val trajectoryClock @Composable get() = v(R.drawable.dsh_trajectory_clock)
    val trajectorySettings @Composable get() = v(R.drawable.dsh_trajectory_settings)
    /** WorkspaceBrowser view-options (IconSlidersTwoOutlineRegular). */
    val sliders @Composable get() = v(R.drawable.dsh_sliders_two)
    /** WorkspaceBrowser add-workspace (IconProjectAddOutlineRegular). */
    val projectAdd @Composable get() = v(R.drawable.dsh_project_add)
    val settings @Composable get() = v(R.drawable.dsh_settings)
    val light @Composable get() = v(R.drawable.dsh_light)
    val dark @Composable get() = v(R.drawable.dsh_dark)
    val followSystem @Composable get() = v(R.drawable.dsh_follow_system)
    val refresh @Composable get() = v(R.drawable.dsh_refresh)
    val search @Composable get() = v(R.drawable.dsh_search)
    val data @Composable get() = v(R.drawable.dsh_data)
    val dataMedium @Composable get() = v(R.drawable.dsh_data_medium)
    val personalization @Composable get() = v(R.drawable.dsh_personalization)
    val paperPlane @Composable get() = v(R.drawable.dsh_paper_plane)
    val terminal @Composable get() = v(R.drawable.dsh_terminal)
    val document @Composable get() = v(R.drawable.dsh_document)
    val check @Composable get() = v(R.drawable.dsh_check)
    val copy @Composable get() = v(R.drawable.dsh_copy)
    val branch @Composable get() = v(R.drawable.dsh_branch)
    val like @Composable get() = v(R.drawable.dsh_like)
    val likeFill @Composable get() = v(R.drawable.dsh_like_fill)
    val dislike @Composable get() = v(R.drawable.dsh_dislike)
    val dislikeFill @Composable get() = v(R.drawable.dsh_dislike_fill)
    val wrap @Composable get() = v(R.drawable.dsh_wrap)
    val nowrap @Composable get() = v(R.drawable.dsh_nowrap)
    val wrapLines @Composable get() = v(R.drawable.dsh_wrap_lines)
    val panelLeft @Composable get() = v(R.drawable.dsh_panel_left)
    val paperclip @Composable get() = v(R.drawable.dsh_paperclip)
    val goal @Composable get() = v(R.drawable.dsh_goal)
    val plan @Composable get() = v(R.drawable.dsh_plan)
    val send @Composable get() = v(R.drawable.dsh_send)
    /** InputBar's filled primary arrow, distinct from QueueDock's regular send icon. */
    val composerSend @Composable get() = v(R.drawable.dsh_composer_send)
    /** QueueDock uses the same ChatLinesOutlineArtwork as session references. */
    val queue @Composable get() = v(R.drawable.dsh_chat_lines)
    val compact @Composable get() = v(R.drawable.dsh_compact)
    val download @Composable get() = v(R.drawable.dsh_download)
    val edit @Composable get() = v(R.drawable.dsh_edit)
    val trash @Composable get() = v(R.drawable.dsh_trash)
    val user @Composable get() = v(R.drawable.dsh_user)
    val link @Composable get() = v(R.drawable.dsh_link)
    val globe @Composable get() = v(R.drawable.dsh_globe)
    val rightUp @Composable get() = v(R.drawable.dsh_right_up)
    val chat @Composable get() = v(R.drawable.dsh_chat_lines)
    val inspect @Composable get() = v(R.drawable.dsh_inspect)
    val think @Composable get() = v(R.drawable.dsh_think)
    val question @Composable get() = v(R.drawable.dsh_question)
    val sparkle @Composable get() = v(R.drawable.dsh_sparkle)
    val checklist @Composable get() = v(R.drawable.dsh_checklist)
    val code @Composable get() = v(R.drawable.dsh_code)
    val api @Composable get() = v(R.drawable.dsh_api)
    val cordis @Composable get() = v(R.drawable.dsh_cordis)
    val browse @Composable get() = v(R.drawable.dsh_browse)
    val fish @Composable get() = v(R.drawable.dsh_fish)
    val wordmark @Composable get() = v(R.drawable.dsh_wordmark)
}
