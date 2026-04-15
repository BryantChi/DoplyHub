package com.gimy.tv.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.gimy.tv.R
import com.gimy.tv.ui.theme.CinemaBlack
import com.gimy.tv.ui.theme.CinemaRed
import com.gimy.tv.ui.theme.CinemaTextMuted

@Composable
fun SplashOverlay() {
    val iconAnim = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        iconAnim.animateTo(1f, animationSpec = tween(600, easing = EaseOut))
        textAlpha.animateTo(1f, animationSpec = tween(400, easing = EaseOut))
    }

    Box(
        Modifier.fillMaxSize().background(CinemaBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .scale(iconAnim.value)
                    .alpha(iconAnim.value)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "GIMY TV",
                color = CinemaRed,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                modifier = Modifier.alpha(textAlpha.value)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "你的私人影院",
                color = CinemaTextMuted,
                fontSize = 14.sp,
                modifier = Modifier.alpha(textAlpha.value)
            )
        }
    }
}
