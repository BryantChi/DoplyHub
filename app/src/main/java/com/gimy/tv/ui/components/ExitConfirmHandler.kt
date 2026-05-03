package com.gimy.tv.ui.components

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext

private const val DOUBLE_BACK_INTERVAL_MS = 2000L

@Composable
fun ExitConfirmHandler(isPhone: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    if (isPhone) {
        var lastBackPressTime by remember { mutableLongStateOf(0L) }
        BackHandler {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < DOUBLE_BACK_INTERVAL_MS) {
                activity.finish()
            } else {
                lastBackPressTime = now
                Toast.makeText(context, "再按一次返回鍵退出", Toast.LENGTH_SHORT).show()
            }
        }
    } else {
        var showDialog by remember { mutableStateOf(false) }
        BackHandler { showDialog = true }

        if (showDialog) {
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }

            AlertDialog(
                onDismissRequest = { showDialog = false },
                text = { Text("確定要退出 Doply Hub？") },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        activity.finish()
                    }) {
                        Text("確定退出")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDialog = false },
                        modifier = Modifier.focusRequester(cancelFocus)
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
}
