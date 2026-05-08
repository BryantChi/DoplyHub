package com.gimy.tv.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.ui.browse.BrowseScreen
import com.gimy.tv.ui.components.AdaptiveScaffold
import com.gimy.tv.ui.components.SplashOverlay
import com.gimy.tv.ui.detail.DetailScreen
import com.gimy.tv.ui.favorites.FavoritesScreen
import com.gimy.tv.ui.history.HistoryScreen
import com.gimy.tv.ui.home.HomeScreen
import com.gimy.tv.ui.navigation.Screen
import com.gimy.tv.ui.player.PlayerScreen
import com.gimy.tv.ui.search.SearchScreen
import com.gimy.tv.ui.settings.SettingsScreen
import com.gimy.tv.ui.theme.*
import com.gimy.tv.ui.update.UpdateOverlay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isTelevision = remember {
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            }
            val isPhone = !isTelevision && windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
            val dimensions = remember(isTelevision, windowSizeClass.widthSizeClass) {
                when {
                    isTelevision -> TvDimensions
                    windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact -> PhoneDimensions
                    else -> TabletDimensions
                }
            }

            DoplyTheme(isTelevision = isTelevision) {
                CompositionLocalProvider(LocalDimensions provides dimensions) {
                var showSplash by remember { mutableStateOf(true) }
                // Defer NavHost to second frame so SplashOverlay renders first
                var contentReady by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    contentReady = true
                    delay(1500L)
                    showSplash = false
                }

                Box(Modifier.fillMaxSize()) {
                if (contentReady) {
                val navController = rememberNavController()
                AdaptiveScaffold(
                    widthSizeClass = windowSizeClass.widthSizeClass,
                    isTelevision = isTelevision,
                    navController = navController,
                ) {
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
                            onBrowseClick = { sourceType, typeId ->
                                navController.navigate(Screen.Browse.createRoute(sourceType.name, typeId))
                            },
                            onCategoriesClick = { navController.navigate(Screen.Categories.route) },
                            onFavoritesClick = { navController.navigate(Screen.Favorites.route) },
                            onHistoryClick = { navController.navigate(Screen.History.route) },
                            onSettingsClick = { navController.navigate(Screen.Settings.route) },
                            isPhone = isPhone
                        )
                    }

                    composable(Screen.Categories.route) {
                        com.gimy.tv.ui.category.CategoryScreen(
                            onCategoryClick = { sourceType, typeId ->
                                navController.navigate(
                                    Screen.Browse.createRoute(sourceType, typeId)
                                )
                            },
                            onAdultZoneClick = { navController.navigate(Screen.AdultZone.route) },
                        )
                    }

                    composable(Screen.AdultZone.route) {
                        com.gimy.tv.ui.adult.AdultContentScreen(
                            onVodClick = { sourceType, vodId ->
                                navController.navigate(
                                    Screen.Detail.createRoute(sourceType.name, vodId)
                                )
                            },
                            onBack = { navController.popBackStack() },
                            onMoreClick = { navController.navigate(Screen.AdultPlus.route) },
                        )
                    }

                    composable(Screen.AdultPlus.route) {
                        com.gimy.tv.ui.adultplus.AdultPlusScreen(
                            onVodClick = { sourceType, vodId ->
                                navController.navigate(
                                    Screen.Detail.createRoute(sourceType.name, vodId)
                                )
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(
                        Screen.Browse.route,
                        arguments = listOf(
                            navArgument("sourceType") { type = NavType.StringType },
                            navArgument("typeId") { type = NavType.StringType }
                        )
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
                            onVodClick = { sourceType, vodId ->
                                navController.navigate(
                                    Screen.Detail.createRoute(sourceType.name, vodId)
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

                    composable(Screen.Settings.route) {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                }
                }
                }

                // Splash overlay on top
                AnimatedVisibility(
                    visible = showSplash,
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    SplashOverlay()
                }

                // App-update dialog (cold-start check + flow). Above NavHost, below splash.
                if (contentReady && !showSplash) UpdateOverlay()
                }
                }
            }
        }
    }
}
