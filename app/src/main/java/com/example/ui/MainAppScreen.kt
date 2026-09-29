package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.browser.BrowserScreen
import com.example.ui.chat.ChatScreen
import com.example.ui.debug.DeveloperDebugScreen
import com.example.ui.gateway.WhatsAppGatewayScreen
import com.example.ui.home.HomeScreen
import com.example.ui.memory.MemoryScreen
import com.example.ui.navigation.MainTab
import com.example.ui.navigation.SettingsSection
import com.example.ui.navigation.SubScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.tasks.TasksScreen
import com.example.ui.terminal.TerminalScreen
import com.example.ui.theme.Motion
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
import com.example.wagateway.WaGatewayViewModel

/**
 * The app shell: five top-level destinations plus four full-screen destinations pushed on
 * top of them.
 *
 * Layout is chosen by window size, not by device: at [Sizes.railBreakpoint] and wider the
 * bottom bar becomes a navigation rail and the content column is capped, so a tablet or a
 * Chromebook window does not show a stretched phone layout. Tab content itself is
 * unchanged between the two — only the way you get to it moves.
 *
 * Navigation state is `rememberSaveable`, so a process death or a rotation returns you to
 * the tab you were on instead of silently resetting to Beranda.
 */
@Composable
fun MainAppScreen(
  viewModel: WaGatewayViewModel,
  modifier: Modifier = Modifier,
) {
  var currentTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
  var subScreen by rememberSaveable { mutableStateOf(SubScreen.NONE) }
  // Set when a screen asks Pengaturan to open on one specific section (Home's approval
  // queue points at Keamanan & Akses). Cleared once the section has been reached, so the
  // next visit starts at the top.
  var settingsSection by rememberSaveable { mutableStateOf(SettingsSection.TOP) }
  val tabStateHolder = rememberSaveableStateHolder()

  // Back means "up one level": first leave a pushed screen, then return to Beranda, then
  // let the system handle it and leave the app.
  BackHandler(enabled = subScreen != SubScreen.NONE || currentTab != MainTab.HOME) {
    if (subScreen != SubScreen.NONE) {
      subScreen = SubScreen.NONE
    } else {
      currentTab = MainTab.HOME
    }
  }

  AnimatedContent(
    targetState = subScreen,
    modifier = modifier.fillMaxSize(),
    transitionSpec = {
      if (targetState != SubScreen.NONE) {
        (slideInHorizontally(tween(Motion.standard)) { it / 6 } + fadeIn(tween(Motion.standard))) togetherWith
          fadeOut(tween(Motion.fast))
      } else {
        (slideInHorizontally(tween(Motion.standard)) { -it / 6 } + fadeIn(tween(Motion.standard))) togetherWith
          fadeOut(tween(Motion.fast))
      }
    },
    label = "sub_screen",
  ) { sub ->
    when (sub) {
      SubScreen.NONE ->
        AppShell(
          viewModel = viewModel,
          currentTab = currentTab,
          onSelectTab = { currentTab = it },
          onOpenSubScreen = { subScreen = it },
          onOpenSettingsSection = {
            settingsSection = it
            currentTab = MainTab.SETTINGS
          },
          settingsSection = settingsSection,
          onSettingsSectionConsumed = { settingsSection = SettingsSection.TOP },
          tabStateHolder = tabStateHolder,
        )

      SubScreen.GATEWAY ->
        WhatsAppGatewayScreen(
          viewModel = viewModel,
          onNavigateBack = { subScreen = SubScreen.NONE },
          modifier = Modifier.fillMaxSize(),
        )

      SubScreen.TERMINAL ->
        TerminalScreen(
          viewModel = viewModel,
          onNavigateBack = { subScreen = SubScreen.NONE },
          modifier = Modifier.fillMaxSize(),
        )

      SubScreen.BROWSER ->
        BrowserScreen(
          viewModel = viewModel,
          onNavigateBack = { subScreen = SubScreen.NONE },
          modifier = Modifier.fillMaxSize(),
        )

      SubScreen.DEBUG ->
        DeveloperDebugScreen(
          viewModel = viewModel,
          onNavigateBack = { subScreen = SubScreen.NONE },
          modifier = Modifier.fillMaxSize(),
        )
    }
  }
}

@Composable
private fun AppShell(
  viewModel: WaGatewayViewModel,
  currentTab: MainTab,
  onSelectTab: (MainTab) -> Unit,
  onOpenSubScreen: (SubScreen) -> Unit,
  onOpenSettingsSection: (SettingsSection) -> Unit,
  settingsSection: SettingsSection,
  onSettingsSectionConsumed: () -> Unit,
  tabStateHolder: SaveableStateHolder,
) {
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val wide = maxWidth >= Sizes.railBreakpoint

    if (wide) {
      Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
          AppNavigationRail(currentTab = currentTab, onSelectTab = onSelectTab)
          Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
          Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            TabHost(currentTab = currentTab, tabStateHolder = tabStateHolder) { tab ->
              TabContent(
                tab = tab,
                viewModel = viewModel,
                onSelectTab = onSelectTab,
                onOpenSubScreen = onOpenSubScreen,
                onOpenSettingsSection = onOpenSettingsSection,
                settingsSection = settingsSection,
                onSettingsSectionConsumed = onSettingsSectionConsumed,
              )
            }
          }
        }
      }
    } else {
      Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { AppNavigationBar(currentTab = currentTab, onSelectTab = onSelectTab) },
      ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
          TabHost(currentTab = currentTab, tabStateHolder = tabStateHolder) { tab ->
            TabContent(
              tab = tab,
              viewModel = viewModel,
              onSelectTab = onSelectTab,
              onOpenSubScreen = onOpenSubScreen,
              onOpenSettingsSection = onOpenSettingsSection,
              settingsSection = settingsSection,
              onSettingsSectionConsumed = onSettingsSectionConsumed,
            )
          }
        }
      }
    }
  }
}

/**
 * Hosts the tab content: a short directional slide between tabs, keyed saveable state so
 * scroll position survives a tab switch, and a width cap so wide windows keep a readable
 * column instead of stretching cards edge to edge.
 */
@Composable
private fun TabHost(
  currentTab: MainTab,
  tabStateHolder: SaveableStateHolder,
  content: @Composable (MainTab) -> Unit,
) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Box(modifier = Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize()) {
      AnimatedContent(
        targetState = currentTab,
        transitionSpec = {
          // Tabs are ordered, so moving right slides in from the right and moving left from
          // the left: the motion tells you which way you moved in the list.
          val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
          (slideInHorizontally(tween(Motion.standard)) { direction * it / 12 } + fadeIn(tween(Motion.standard))) togetherWith
            fadeOut(tween(Motion.fast))
        },
        contentKey = { it.name },
        label = "main_tab",
      ) { tab ->
        tabStateHolder.SaveableStateProvider(tab.name) {
          Box(modifier = Modifier.fillMaxSize()) { content(tab) }
        }
      }
    }
  }
}

@Composable
private fun TabContent(
  tab: MainTab,
  viewModel: WaGatewayViewModel,
  onSelectTab: (MainTab) -> Unit,
  onOpenSubScreen: (SubScreen) -> Unit,
  onOpenSettingsSection: (SettingsSection) -> Unit,
  settingsSection: SettingsSection,
  onSettingsSectionConsumed: () -> Unit,
) {
  when (tab) {
    MainTab.HOME ->
      HomeScreen(
        viewModel = viewModel,
        onNavigateToChat = { onSelectTab(MainTab.CHAT) },
        onNavigateToGateway = { onOpenSubScreen(SubScreen.GATEWAY) },
        onNavigateToSettings = onOpenSettingsSection,
        onNavigateToTasks = { onSelectTab(MainTab.TASKS) },
      )

    MainTab.CHAT -> ChatScreen(viewModel = viewModel)

    MainTab.TASKS -> TasksScreen(viewModel = viewModel)

    MainTab.MEMORY ->
      MemoryScreen(
        viewModel = viewModel,
        onNavigateToChatSession = { onSelectTab(MainTab.CHAT) },
      )

    MainTab.SETTINGS ->
      SettingsScreen(
        viewModel = viewModel,
        onNavigateToGateway = { onOpenSubScreen(SubScreen.GATEWAY) },
        onNavigateToDebug = { onOpenSubScreen(SubScreen.DEBUG) },
        onNavigateToTerminal = { onOpenSubScreen(SubScreen.TERMINAL) },
        onNavigateToBrowser = { onOpenSubScreen(SubScreen.BROWSER) },
        initialSection = settingsSection,
        onSectionConsumed = onSettingsSectionConsumed,
      )
  }
}

@Composable
private fun AppNavigationBar(currentTab: MainTab, onSelectTab: (MainTab) -> Unit) {
  NavigationBar(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    tonalElevation = 0.dp,
  ) {
    MainTab.entries.forEach { tab ->
      val selected = tab == currentTab
      NavigationBarItem(
        selected = selected,
        onClick = { onSelectTab(tab) },
        icon = {
          Icon(
            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
            // The label next to the icon already names the destination, so the icon is
            // decorative here; giving it the same text made TalkBack read it twice.
            contentDescription = null,
            modifier = Modifier.size(Sizes.iconLg),
          )
        },
        label = { Text(text = tab.title, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        alwaysShowLabel = true,
        colors =
          NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
      )
    }
  }
}

@Composable
private fun AppNavigationRail(currentTab: MainTab, onSelectTab: (MainTab) -> Unit) {
  NavigationRail(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    // The shell already applies the safe-drawing insets to this row.
    windowInsets = WindowInsets(0, 0, 0, 0),
  ) {
    Spacer(modifier = Modifier.height(Spacing.sm))
    MainTab.entries.forEach { tab ->
      val selected = tab == currentTab
      NavigationRailItem(
        selected = selected,
        onClick = { onSelectTab(tab) },
        icon = {
          Icon(
            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
            contentDescription = null,
            modifier = Modifier.size(Sizes.iconLg),
          )
        },
        label = { Text(text = tab.title, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        alwaysShowLabel = true,
        colors =
          NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
      )
    }
  }
}
