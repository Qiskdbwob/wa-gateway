package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The five top-level destinations. Titles, subtitles and both icon states live here and
 * nowhere else: the shell used to keep a second, slightly different copy of this list
 * (Home vs Dashboard icons), so the bottom bar and the enum could disagree.
 */
enum class MainTab(
  val title: String,
  val subtitle: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
) {
  HOME(
    title = "Beranda",
    subtitle = "Status agent dan aksi cepat",
    selectedIcon = Icons.Filled.Dashboard,
    unselectedIcon = Icons.Outlined.Dashboard,
  ),
  CHAT(
    title = "Chat",
    subtitle = "Bicara langsung dengan agent",
    selectedIcon = Icons.Filled.Chat,
    unselectedIcon = Icons.Outlined.Chat,
  ),
  TASKS(
    title = "Tugas",
    subtitle = "Yang sedang, terjadwal, dan sudah dikerjakan",
    selectedIcon = Icons.Filled.Checklist,
    unselectedIcon = Icons.Outlined.Checklist,
  ),
  MEMORY(
    title = "Memori",
    subtitle = "Sesi, fakta, skill, dan pembelajaran",
    selectedIcon = Icons.Filled.Memory,
    unselectedIcon = Icons.Outlined.Memory,
  ),
  SETTINGS(
    title = "Pengaturan",
    subtitle = "Model, keamanan, dan tool",
    selectedIcon = Icons.Filled.Settings,
    unselectedIcon = Icons.Outlined.Settings,
  );
}

/**
 * Full-screen destinations that are pushed on top of the shell (they have their own top app
 * bar and a back arrow). Modeled as an enum rather than a sealed class: the old version
 * carried a `TaskDetail` case that nothing could ever construct or handle — dead navigation
 * state that made the back behaviour harder to reason about.
 */
enum class SubScreen(val title: String) {
  NONE(""),
  GATEWAY("WhatsApp Gateway"),
  TERMINAL("Terminal"),
  BROWSER("Browser Agent"),
  DEBUG("Developer & Diagnostik"),
  ;
}
