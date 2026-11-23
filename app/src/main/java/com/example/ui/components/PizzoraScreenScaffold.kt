package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPizzoraTokens

private val NavigationRailBreakpoint = 600.dp

/** One tab in [PizzoraScreenScaffold], rendered in a bottom bar or a rail depending on width. */
data class PizzoraNavigationItem(
  val label: String,
  val icon: ImageVector,
  val selected: Boolean = false,
  val onClick: () -> Unit,
)

/**
 * Standard screen shell for customer, owner, and KDS surfaces.
 *
 * The caller supplies the title, optional top-bar chrome, navigation items, FAB, and content;
 * the scaffold owns insets, token spacing/elevation, snackbar hosting, and the responsive choice
 * between a bottom navigation bar and a navigation rail. Child tags derive from [testTag] as
 * `<testTag>_top_bar`, `<testTag>_content`, `<testTag>_snackbar_host`, `<testTag>_fab`,
 * `<testTag>_bottom_bar`, `<testTag>_nav_rail`, and `<testTag>_tab_<label>`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PizzoraScreenScaffold(
  title: String,
  modifier: Modifier = Modifier,
  navigationIcon: @Composable (() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {},
  snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
  navigationItems: List<PizzoraNavigationItem> = emptyList(),
  floatingActionButton: @Composable (() -> Unit)? = null,
  testTag: String = "pizzora_screen_scaffold",
  content: @Composable () -> Unit,
) {
  val tokens = LocalPizzoraTokens.current

  BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(testTag)) {
    val useRail = maxWidth >= NavigationRailBreakpoint
    Scaffold(
      contentWindowInsets = WindowInsets.safeDrawing,
      containerColor = MaterialTheme.colorScheme.background,
      snackbarHost = {
        SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.testTag("${testTag}_snackbar_host"),
        )
      },
      topBar = {
        PizzoraSurface(
          tone = PizzoraSurfaceTone.Surface,
          elevation = tokens.elevation.level1,
          shape = RectangleShape,
          testTag = "${testTag}_top_bar",
        ) {
          TopAppBar(
            title = {
              Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
              )
            },
            navigationIcon = { navigationIcon?.invoke() },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surface,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
              navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
              actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
          )
        }
      },
      bottomBar = {
        if (!useRail && navigationItems.isNotEmpty()) {
          NavigationBar(
            modifier = Modifier.testTag("${testTag}_bottom_bar"),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = tokens.elevation.level2,
          ) {
            navigationItems.forEach { item ->
              NavigationBarItem(
                selected = item.selected,
                onClick = item.onClick,
                icon = { PizzoraNavigationIcon(item) },
                label = { PizzoraNavigationLabel(item) },
                modifier = Modifier.testTag(item.resolvedTestTag(testTag)),
              )
            }
          }
        }
      },
      floatingActionButton = {
        if (floatingActionButton != null) {
          Box(modifier = Modifier.testTag("${testTag}_fab")) {
            floatingActionButton()
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.space8),
      ) {
        if (useRail && navigationItems.isNotEmpty()) {
          NavigationRail(
            modifier = Modifier
              .fillMaxHeight()
              .testTag("${testTag}_nav_rail"),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
          ) {
            navigationItems.forEach { item ->
              NavigationRailItem(
                selected = item.selected,
                onClick = item.onClick,
                icon = { PizzoraNavigationIcon(item) },
                label = { PizzoraNavigationLabel(item) },
                modifier = Modifier.testTag(item.resolvedTestTag(testTag)),
              )
            }
          }
        }
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .testTag("${testTag}_content"),
        ) {
          content()
        }
      }
    }
  }
}

private fun PizzoraNavigationItem.resolvedTestTag(scaffoldTestTag: String): String =
  "${scaffoldTestTag}_tab_${label.lowercase()}"

@Composable
private fun PizzoraNavigationIcon(item: PizzoraNavigationItem) {
  Icon(imageVector = item.icon, contentDescription = item.label)
}

@Composable
private fun PizzoraNavigationLabel(item: PizzoraNavigationItem) {
  Text(text = item.label)
}
