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
    /** AdultPlus per-row「查看更多」整頁。path & title 經 URL-encode 以容納 / 與空白。
     *
     *  Compose Navigation 2.8 在 segment 比對前會把 %2F decode 回 /，導致原本要傳的
     *  「categories/uniform」變成「categories」「uniform」兩段，{path} placeholder 只吞到第
     *  一段，URL 就被截斷成「/categories/」這種沒有影片的索引頁，user 看到「暫無內容」。
     *  解法：先把 / 換成 ~~ sentinel（URL-safe，encode 不變），讀回時 ViewModel 端再還原。 */
    data object AdultPlusBrowse : Screen("adult_plus_browse/{sourceType}/{path}/{title}") {
        fun createRoute(sourceType: String, path: String, title: String): String {
            val safePath = path.replace("/", "~~")
            val encPath = java.net.URLEncoder.encode(safePath, "UTF-8")
            val encTitle = java.net.URLEncoder.encode(title, "UTF-8")
            return "adult_plus_browse/$sourceType/$encPath/$encTitle"
        }
    }
    /** AdultPlus「全部分類」入口頁 — 顯示所有 jable / xnxx / 5278 分類，點擊後導向 AdultPlusBrowse。 */
    data object AdultPlusCategories : Screen("adult_plus_categories")
}
