package com.gimy.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.gimy.tv.ui.theme.*

/**
 * 4-digit PIN entry dialog. Designed for TV remotes:
 * - 3×4 numeric keypad with D-pad navigable buttons
 * - Auto-submits when 4 digits entered
 * - Caller controls verification and surfaces `failureMessage` to clear the input on a wrong PIN
 * - Optional lockout state (after N failed attempts) disables the keypad and shows countdown
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PinInputDialog(
    title: String,
    subtitle: String? = null,
    failureMessage: String? = null,
    lockedRemainingSec: Long = 0L,
    onPinComplete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    // Whenever the caller updates the failure message, clear the input so the user can retype.
    LaunchedEffect(failureMessage) {
        if (failureMessage != null) pin = ""
    }
    LaunchedEffect(lockedRemainingSec) {
        if (lockedRemainingSec > 0) pin = ""
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .background(CinemaCard, RoundedCornerShape(14.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = CinemaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, color = CinemaTextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))

            // PIN progress dots
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i ->
                    val filled = i < pin.length
                    Box(
                        Modifier
                            .size(if (filled) 14.dp else 12.dp)
                            .clip(CircleShape)
                            .background(if (filled) CinemaRed else CinemaTextMuted.copy(0.3f)),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            when {
                lockedRemainingSec > 0L -> Text(
                    "輸入錯誤過多，已鎖定 ${formatSec(lockedRemainingSec)}",
                    color = CinemaRed, fontSize = 12.sp,
                )
                failureMessage != null -> Text(failureMessage, color = CinemaRed, fontSize = 12.sp)
                else -> Spacer(Modifier.height(14.dp))
            }
            Spacer(Modifier.height(18.dp))

            // 3×4 keypad: 1-9, blank, 0, backspace
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("", "0", "⌫"),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { label ->
                            if (label.isEmpty()) {
                                Spacer(Modifier.size(56.dp))
                            } else {
                                PinKey(
                                    label = label,
                                    enabled = lockedRemainingSec == 0L,
                                    onClick = {
                                        when (label) {
                                            "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                            else -> if (pin.length < 4) {
                                                val next = pin + label
                                                if (next.length == 4) {
                                                    // Reset internal state BEFORE notifying caller — otherwise
                                                    // a multi-step flow (set PIN → confirm PIN) starts step 2
                                                    // with pin already at 4 chars, blocking new input.
                                                    pin = ""
                                                    onPinComplete(next)
                                                } else {
                                                    pin = next
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            DoplyButton(
                onClick = onDismiss,
                containerColor = CinemaSurface,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text("取消", color = CinemaTextPrimary, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PinKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    val isTV = LocalIsTelevision.current
    if (isTV) {
        // TV: focus-based selection with D-pad
        var f by remember { mutableStateOf(false) }
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(56.dp)
                .onFocusChanged { f = it.isFocused },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
            colors = ButtonDefaults.colors(
                containerColor = if (f) CinemaRed else CinemaSurface,
                focusedContainerColor = CinemaRed,
                disabledContainerColor = CinemaSurface.copy(0.4f),
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                label,
                color = if (f) Color.White else CinemaTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    } else {
        // Phone / Pad: standard touch button — tv.material3.Button doesn't fire onClick on touch
        androidx.compose.material3.Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = CinemaSurface,
                contentColor = CinemaTextPrimary,
                disabledContainerColor = CinemaSurface.copy(0.4f),
                disabledContentColor = CinemaTextMuted,
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                label,
                color = CinemaTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun formatSec(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
