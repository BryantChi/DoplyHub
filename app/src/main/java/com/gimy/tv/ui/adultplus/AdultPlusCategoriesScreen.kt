package com.gimy.tv.ui.adultplus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.ui.components.FocusableChip
import com.gimy.tv.ui.favorites.PageHeader
import com.gimy.tv.ui.theme.*

/**
 * Standalone "all categories" entry surfaced from the AdultPlus header.
 * Shows every category in [AdultPlusCategories] grouped by source — gives
 * touch + remote users a predictable index without inflating the home page
 * with 25+ rows. Click a chip → navigate to AdultPlusBrowse for that
 * category's full grid.
 */
@Composable
fun AdultPlusCategoriesScreen(
    onCategoryClick: (SourceType, String, String) -> Unit,
    onBack: () -> Unit,
) {
    val dims = LocalDimensions.current
    // Group preserving the declaration order in AdultPlusCategories.all.
    val sections: List<Pair<SourceType, List<AdultPlusCategory>>> =
        AdultPlusCategories.all.groupBy { it.sourceType }.toList()

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack))),
    ) {
        PageHeader("全部分類", onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = dims.screenHorizontalPadding,
                end = dims.screenHorizontalPadding,
                top = 8.dp,
                bottom = 24.dp,
            ),
        ) {
            items(sections, key = { it.first.name }) { (source, categories) ->
                Spacer(Modifier.height(8.dp))
                Text(
                    source.displayName,
                    color = CinemaTextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { cat ->
                        FocusableChip(cat.displayName) {
                            onCategoryClick(cat.sourceType, cat.pathKey, cat.displayName)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
