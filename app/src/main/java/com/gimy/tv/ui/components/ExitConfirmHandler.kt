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
import com.gimy.tv.R
import androidx.compose.ui.res.stringResource

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
                Toast.makeText(context, context.getString(R.string.exit_confirm_toast), Toast.LENGTH_SHORT).show()
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
                text = { Text(stringResource(R.string.exit_confirm_title)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        activity.finish()
                    }) {
                        Text(stringResource(R.string.exit_confirm_ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDialog = false },
                        modifier = Modifier.focusRequester(cancelFocus)
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}
