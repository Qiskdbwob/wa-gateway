package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.browser.BrowserScreen
import com.example.ui.chat.ChatScreen
import com.example.ui.debug.DeveloperDebugScreen
import com.example.ui.gateway.WhatsAppGatewayScreen
import com.example.ui.terminal.TerminalScreen
import com.example.ui.home.HomeScreen
import com.example.ui.memory.MemoryScreen
import com.example.ui.navigation.MainTab
import com.example.ui.navigation.SubScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.tasks.TasksScreen
import com.example.wagateway.WaGatewayViewModel

@Composable
fun MainAppScreen(
    viewModel: WaGatewayViewModel,
    modifier: Modifier = Modifier
) {
    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var currentSubScreen by remember { mutableStateOf<SubScreen>(SubScreen.None) }

    // Android System Back Handling
    BackHandler(enabled = currentSubScreen != SubScreen.None || currentTab != MainTab.HOME) {
        if (currentSubScreen != SubScreen.None) {
            currentSubScreen = SubScreen.None
        } else if (currentTab != MainTab.HOME) {
            currentTab = MainTab.HOME
        }
    }

    if (currentSubScreen == SubScreen.Gateway) {
        WhatsAppGatewayScreen(
            viewModel = viewModel,
            onNavigateBack = { currentSubScreen = SubScreen.None },
            modifier = modifier
        )
    } else if (currentSubScreen == SubScreen.DeveloperDebug) {
        DeveloperDebugScreen(
            viewModel = viewModel,
            onNavigateBack = { currentSubScreen = SubScreen.None },
            modifier = modifier
        )
    } else if (currentSubScreen == SubScreen.Terminal) {
        TerminalScreen(
            viewModel = viewModel,
            onNavigateBack = { currentSubScreen = SubScreen.None },
            modifier = modifier
        )
    } else if (currentSubScreen == SubScreen.Browser) {
        BrowserScreen(
            viewModel = viewModel,
            onNavigateBack = { currentSubScreen = SubScreen.None },
            modifier = modifier
        )
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    val tabs = listOf(
                        Triple(MainTab.HOME, "Beranda", Pair(Icons.Filled.Home, Icons.Outlined.Home)),
                        Triple(MainTab.CHAT, "Chat", Pair(Icons.Filled.Chat, Icons.Outlined.Chat)),
                        Triple(MainTab.TASKS, "Tugas", Pair(Icons.Filled.Checklist, Icons.Outlined.Checklist)),
                        Triple(MainTab.MEMORY, "Memori", Pair(Icons.Filled.Memory, Icons.Outlined.Memory)),
                        Triple(MainTab.SETTINGS, "Pengaturan", Pair(Icons.Filled.Settings, Icons.Outlined.Settings))
                    )

                    tabs.forEach { (tab, label, icons) ->
                        val isSelected = currentTab == tab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) icons.first else icons.second,
                                    contentDescription = label,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // The keyboard shrinks the window (adjustResize), but without this the IME
                    // overlaps the content and pushes input rows / save buttons below the fold —
                    // reported on the Memory > Knowledge tab when typing a long fact.
                    .imePadding()
            ) {
                when (currentTab) {
                    MainTab.HOME -> {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToChat = { currentTab = MainTab.CHAT },
                            onNavigateToGateway = { currentSubScreen = SubScreen.Gateway },
                            onNavigateToSettings = { currentTab = MainTab.SETTINGS },
                            onNavigateToTasks = { currentTab = MainTab.TASKS }
                        )
                    }

                    MainTab.CHAT -> {
                        ChatScreen(viewModel = viewModel)
                    }

                    MainTab.TASKS -> {
                        TasksScreen(viewModel = viewModel)
                    }

                    MainTab.MEMORY -> {
                        MemoryScreen(
                            viewModel = viewModel,
                            onNavigateToChatSession = { sessionId ->
                                currentTab = MainTab.CHAT
                            }
                        )
                    }

                    MainTab.SETTINGS -> {
                        SettingsScreen(
                            viewModel = viewModel,
                            onNavigateToGateway = { currentSubScreen = SubScreen.Gateway },
                            onNavigateToDebug = { currentSubScreen = SubScreen.DeveloperDebug },
                            onNavigateToTerminal = { currentSubScreen = SubScreen.Terminal },
                            onNavigateToBrowser = { currentSubScreen = SubScreen.Browser }
                        )
                    }
                }
            }
        }
    }
}
