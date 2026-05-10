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
    data object Categories : Screen("categories")
    data object Favorites : Screen("favorites")
    data object History : Screen("history")
    data object Settings : Screen("settings")
    data object AdultZone : Screen("adult_zone")
    data object AdultPlus : Screen("adult_plus")
    /** AdultPlus per-row「查看更多」整頁。path & title 經 URL-encode 以容納 / 與空白。 */
    data object AdultPlusBrowse : Screen("adult_plus_browse/{sourceType}/{path}/{title}") {
        fun createRoute(sourceType: String, path: String, title: String): String {
            val encPath = java.net.URLEncoder.encode(path, "UTF-8")
            val encTitle = java.net.URLEncoder.encode(title, "UTF-8")
            return "adult_plus_browse/$sourceType/$encPath/$encTitle"
        }
    }
    /** AdultPlus「全部分類」入口頁 — 顯示所有 jable / xnxx / 5278 分類，點擊後導向 AdultPlusBrowse。 */
    data object AdultPlusCategories : Screen("adult_plus_categories")
}
