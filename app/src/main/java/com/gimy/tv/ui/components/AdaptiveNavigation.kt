package com.gimy.tv.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.gimy.tv.ui.navigation.Screen
import com.gimy.tv.ui.theme.*
import com.gimy.tv.R
import androidx.compose.ui.res.stringResource

data class NavItem(
    val route: String,
    /** 標籤放 res id 而不是字串：這份清單是 top-level val，取不到 Composable 的 context。 */
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

val navItems = listOf(
    NavItem(Screen.Home.route, R.string.nav_home, Icons.Default.Home),
    NavItem(Screen.Search.route, R.string.nav_search, Icons.Default.Search),
    NavItem(Screen.Categories.route, R.string.nav_category, Icons.Default.VideoLibrary),
    NavItem(Screen.Favorites.route, R.string.nav_favorites, Icons.Default.Favorite),
    NavItem(Screen.History.route, R.string.nav_history, Icons.Default.History),
    NavItem(Screen.Settings.route, R.string.nav_settings, Icons.Default.Settings),
)

@Composable
fun AdaptiveScaffold(
    widthSizeClass: WindowWidthSizeClass,
    isTelevision: Boolean,
    navController: NavController,
    content: @Composable () -> Unit
) {
    if (isTelevision) {
        content()
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Hide nav bar on detail/player screens
    val showNavBar = currentRoute in listOf(
        Screen.Home.route, Screen.Search.route, Screen.Categories.route,
        Screen.Favorites.route, Screen.History.route, Screen.Settings.route
    ) || currentRoute?.startsWith("browse/") == true

    when (widthSizeClass) {
        WindowWidthSizeClass.Compact -> {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).statusBarsPadding()) { content() }
                if (showNavBar) {
                    NavigationBar(
                        containerColor = CinemaElevated,
                        contentColor = CinemaTextPrimary,
                        modifier = Modifier.navigationBarsPadding(),
                    ) {
                        navItems.forEach { item ->
                            val selected = isNavItemSelected(currentRoute, item.route)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(navController, item.route) },
                                icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                                label = { Text(stringResource(item.labelRes), fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CinemaRed,
                                    selectedTextColor = CinemaRed,
                                    unselectedIconColor = CinemaTextMuted,
                                    unselectedTextColor = CinemaTextMuted,
                                    indicatorColor = CinemaRed.copy(alpha = 0.12f),
                                ),
                            )
                        }
                    }
                }
            }
        }
        else -> {
            Row(Modifier.fillMaxSize()) {
                if (showNavBar) {
                    NavigationRail(
                        containerColor = CinemaElevated,
                        contentColor = CinemaTextPrimary,
                        modifier = Modifier.statusBarsPadding(),
                    ) {
                        Spacer(Modifier.weight(1f))
                        navItems.forEach { item ->
                            val selected = isNavItemSelected(currentRoute, item.route)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(navController, item.route) },
                                icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                                label = { Text(stringResource(item.labelRes), fontSize = 11.sp) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CinemaRed,
                                    selectedTextColor = CinemaRed,
                                    unselectedIconColor = CinemaTextMuted,
                                    unselectedTextColor = CinemaTextMuted,
                                    indicatorColor = CinemaRed.copy(alpha = 0.12f),
                                ),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                Box(Modifier.weight(1f).statusBarsPadding()) { content() }
            }
        }
    }
}

private fun isNavItemSelected(currentRoute: String?, itemRoute: String): Boolean {
    if (currentRoute == itemRoute) return true
    // Highlight 分類 tab when viewing a specific browse category
    if (itemRoute == Screen.Categories.route && currentRoute?.startsWith("browse/") == true) return true
    return false
}

private fun navigateToTab(navController: NavController, route: String) {
    navController.navigate(route) {
        popUpTo(Screen.Home.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
