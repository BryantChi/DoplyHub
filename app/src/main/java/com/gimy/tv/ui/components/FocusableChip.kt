package com.gimy.tv.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gimy.tv.ui.theme.CinemaRed
import com.gimy.tv.ui.theme.CinemaSurface
import com.gimy.tv.ui.theme.CinemaTextMuted

/**
 * 密集排列的小按鈕，搜尋、成人進階區的分類列等處都在用。
 *
 * TV／手機的分支交給 [DoplyButton]——原本這裡自己寫了一份一模一樣的 if (isTV)，
 * SearchScreen 裡還有第三份逐字複製。焦點時的顏色變化用 focusedContentColor 表達，
 * 不必自己接 onFocusChanged 記狀態。
 *
 * `primary = true` 不論有沒有焦點都用品牌紅，給一排 chip 裡的主要動作（例如「搜尋」）。
 */
@Composable
fun FocusableChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    DoplyButton(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        containerColor = if (primary) CinemaRed else CinemaSurface,
        contentColor = if (primary) Color.White else CinemaTextMuted,
        focusedContainerColor = CinemaRed,
        focusedContentColor = Color.White,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        focusBorder = false,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
