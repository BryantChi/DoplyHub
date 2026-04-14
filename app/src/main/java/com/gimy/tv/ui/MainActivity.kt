package com.gimy.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.ui.browse.BrowseScreen
import com.gimy.tv.ui.components.SplashOverlay
import com.gimy.tv.ui.detail.DetailScreen
import com.gimy.tv.ui.favorites.FavoritesScreen
import com.gimy.tv.ui.history.HistoryScreen
import com.gimy.tv.ui.home.HomeScreen
import com.gimy.tv.ui.navigation.Screen
import com.gimy.tv.ui.player.PlayerScreen
import com.gimy.tv.ui.search.SearchScreen
import com.gimy.tv.ui.theme.GimyTVTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        setContent {
            GimyTVTheme {
                val navController = rememberNavController()
                var showSplash by remember { mutableStateOf(true) }

                // Minimum 1.5s display, then dismiss
                LaunchedEffect(Unit) {
                    delay(1500L)
                    showSplash = false
                }

                Box(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(
                            onVodClick = { sourceType, vodId ->
                                navController.navigate(
                                    Screen.Detail.createRoute(sourceType.name, vodId)
                                )
                            },
                            onSearchClick = { navController.navigate(Screen.Search.route) },
                            onBrowseClick = { typeId ->
                                navController.navigate(Screen.Browse.createRoute(typeId))
                            },
                            onFavoritesClick = { navController.navigate(Screen.Favorites.route) },
                            onHistoryClick = { navController.navigate(Screen.History.route) }
                        )
                    }

                    composable(
                        Screen.Browse.route,
                        arguments = listOf(navArgument("typeId") { type = NavType.StringType })
                    ) {
                        BrowseScreen(
                            onVodClick = { sourceType, vodId ->
                                navController.navigate(
                                    Screen.Detail.createRoute(sourceType.name, vodId)
                                )
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.Search.route) {
                        SearchScreen(
                            onVodClick = { sourceType, vodId ->
                                navController.navigate(
                                    Screen.Detail.createRoute(sourceType.name, vodId)
                                )
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        Screen.Detail.route,
                        arguments = listOf(
                            navArgument("sourceType") { type = NavType.StringType },
                            navArgument("vodId") { type = NavType.StringType }
                        )
                    ) {
                        DetailScreen(
                            onPlayClick = { sourceType, vodId, sourceId, epNum ->
                                navController.navigate(
                                    Screen.Player.createRoute(sourceType, vodId, sourceId, epNum)
                                )
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        Screen.Player.route,
                        arguments = listOf(
                            navArgument("sourceType") { type = NavType.StringType },
                            navArgument("vodId") { type = NavType.StringType },
                            navArgument("sourceId") { type = NavType.StringType },
                            navArgument("episodeNum") { type = NavType.StringType }
                        )
                    ) {
                        PlayerScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.Favorites.route) {
                        FavoritesScreen(
                            onVodClick = { sourceType, vodId ->
                                navController.navigate(
                                    Screen.Detail.createRoute(sourceType.name, vodId)
                                )
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.History.route) {
                        HistoryScreen(
                            onVodClick = { sourceType, vodId ->
                                navController.navigate(
                                    Screen.Detail.createRoute(sourceType.name, vodId)
                                )
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                // Splash overlay on top
                AnimatedVisibility(
                    visible = showSplash,
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    SplashOverlay()
                }
                }
            }
        }
    }
}
