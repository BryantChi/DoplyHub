package com.gimy.tv.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
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

data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val navItems = listOf(
    NavItem(Screen.Home.route, "首頁", Icons.Default.Home),
    NavItem(Screen.Search.route, "搜尋", Icons.Default.Search),
    NavItem(Screen.Categories.route, "分類", Icons.Default.VideoLibrary),
    NavItem(Screen.Favorites.route, "收藏", Icons.Default.Favorite),
    NavItem(Screen.History.route, "紀錄", Icons.Default.History),
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
        Screen.Favorites.route, Screen.History.route
    ) || currentRoute?.startsWith("browse/") == true

    when (widthSizeClass) {
        WindowWidthSizeClass.Compact -> {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { content() }
                if (showNavBar) {
                    NavigationBar(
                        containerColor = CinemaElevated,
                        contentColor = CinemaTextPrimary,
                    ) {
                        navItems.forEach { item ->
                            val selected = isNavItemSelected(currentRoute, item.route)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(navController, item.route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 11.sp) },
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
                    ) {
                        Spacer(Modifier.weight(1f))
                        navItems.forEach { item ->
                            val selected = isNavItemSelected(currentRoute, item.route)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(navController, item.route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 11.sp) },
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
                Box(Modifier.weight(1f)) { content() }
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
