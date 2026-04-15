package com.gimy.tv.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gimy.tv.ui.components.GimyButton
import com.gimy.tv.ui.theme.*

data class CategoryItem(
    val sourceType: String,
    val typeId: Int,
    val name: String,
    val emoji: String,
)

private val categories = listOf(
    CategoryItem("GIMYMAX", 2, "電視劇", "📺"),
    CategoryItem("GIMYMAX", 1, "電影", "🎬"),
    CategoryItem("GIMYMAX", 4, "動漫", "🎌"),
    CategoryItem("GIMYMAX", 29, "綜藝", "🎤"),
    CategoryItem("GIMYMAX", 20, "韓劇", "🇰🇷"),
    CategoryItem("GIMYMAX", 13, "陸劇", "🇨🇳"),
    CategoryItem("GIMYMAX", 16, "美劇", "🇺🇸"),
    CategoryItem("GIMYMAX", 21, "日劇", "🇯🇵"),
    CategoryItem("GIMYMAX", 14, "台劇", "🇹🇼"),
    CategoryItem("GIMYMAX", 15, "港劇", "🇭🇰"),
    CategoryItem("GIMYMAX", 3, "紀錄片", "🎥"),
)

@Composable
fun CategoryScreen(
    onCategoryClick: (sourceType: String, typeId: Int) -> Unit,
) {
    val dims = LocalDimensions.current

    Column(
        Modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding)
    ) {
        Text(
            "分類瀏覽",
            color = CinemaTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(dims.gridMinCellWidth),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
            verticalArrangement = Arrangement.spacedBy(dims.cardSpacing),
            modifier = Modifier.fillMaxSize()
        ) {
            items(categories, key = { "${it.sourceType}_${it.typeId}" }) { cat ->
                GimyButton(
                    onClick = { onCategoryClick(cat.sourceType, cat.typeId) },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = CinemaElevated,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(cat.emoji, fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            cat.name,
                            color = CinemaTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
