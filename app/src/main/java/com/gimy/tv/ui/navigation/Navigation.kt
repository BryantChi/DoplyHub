package com.gimy.tv.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Browse : Screen("browse/{sourceType}/{typeId}") {
        fun createRoute(sourceType: String, typeId: Int) = "browse/$sourceType/$typeId"
    }
    data object Search : Screen("search")
    data object Detail : Screen("detail/{sourceType}/{vodId}") {
        fun createRoute(sourceType: String, vodId: Long) = "detail/$sourceType/$vodId"
    }
    data object Player : Screen("player/{sourceType}/{vodId}/{sourceId}/{episodeNum}") {
        fun createRoute(sourceType: String, vodId: Long, sourceId: Int, episodeNum: Int) =
            "player/$sourceType/$vodId/$sourceId/$episodeNum"
    }
    data object Favorites : Screen("favorites")
    data object History : Screen("history")
}
