package com.gimy.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.gimy.tv.ui.theme.LocalIsTelevision
import com.gimy.tv.ui.theme.CinemaRed
import com.gimy.tv.ui.theme.CinemaCard
import com.gimy.tv.ui.theme.CinemaTextPrimary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DoplyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(6.dp),
    containerColor: Color = CinemaRed,
    contentColor: Color = Color.White,
    focusedContainerColor: Color = CinemaRed,
    focusedContentColor: Color = Color.White,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
    content: @Composable RowScope.() -> Unit
) {
    if (LocalIsTelevision.current) {
        // tv.material3.Button defaults focusedContainerColor to white — combined with
        // white text from contentColor it becomes invisible on focus. Pin focused state
        // to brand red + white text to match the rest of the App's TV controls.
        androidx.tv.material3.Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = androidx.tv.material3.ButtonDefaults.shape(shape = shape),
            colors = androidx.tv.material3.ButtonDefaults.colors(
                containerColor = containerColor,
                contentColor = contentColor,
                focusedContainerColor = focusedContainerColor,
                focusedContentColor = focusedContentColor,
            ),
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DoplyOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
    content: @Composable RowScope.() -> Unit
) {
    if (LocalIsTelevision.current) {
        androidx.tv.material3.OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = androidx.tv.material3.ButtonDefaults.shape(shape = shape),
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            border = BorderStroke(1.dp, CinemaRed),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DoplySurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    color: Color = CinemaCard,
    contentColor: Color = CinemaTextPrimary,
    content: @Composable () -> Unit
) {
    if (LocalIsTelevision.current) {
        androidx.tv.material3.Surface(
            modifier = modifier,
            shape = shape,
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = color,
                contentColor = contentColor,
            ),
            content = { content() },
        )
    } else {
        androidx.compose.material3.Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            content = content,
        )
    }
}
