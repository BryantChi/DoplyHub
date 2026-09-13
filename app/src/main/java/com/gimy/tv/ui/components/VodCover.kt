package com.gimy.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.gimy.tv.ui.theme.CinemaCard
import com.gimy.tv.ui.theme.CinemaTextMuted

/**
 * 影片封面。抓不到圖時不能留一片黑——在 TV 的深色介面上，黑方塊跟「還在載入」長得一樣，
 * 使用者會一直等一個永遠不會出現的東西。
 *
 * 作法是底下先鋪一層卡片底色加淡淡的影片圖示，AsyncImage 疊在上面；圖載好後 Crop 會整面蓋掉，
 * 所以「載入中」與「載不到」呈現一致，都明確表示「這裡是張封面，只是圖不在」。
 *
 * 刻意不用 SubcomposeAsyncImage：格線一次就是幾十張卡，為了兩個狀態多一層 subcomposition 不划算。
 */
@Composable
fun VodCover(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier.background(CinemaCard), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Default.Movie,
            contentDescription = null,
            tint = CinemaTextMuted.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxSize(0.28f),
        )
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
