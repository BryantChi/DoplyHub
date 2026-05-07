package com.gimy.tv.ui.update

import androidx.lifecycle.ViewModel
import com.gimy.tv.data.update.UpdateController
import com.gimy.tv.data.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Thin facade over the singleton [UpdateController]. Multiple screens may instantiate this VM,
 *  but they all share the same underlying state. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val controller: UpdateController,
) : ViewModel() {
    val state: StateFlow<UpdateState> = controller.state

    fun check(silent: Boolean = false) = controller.checkForUpdate(silent = silent)
    fun startDownload() = controller.startDownload()
    fun cancelDownload() = controller.cancelDownload()
    fun launchInstaller(): Boolean = controller.launchInstaller()
    fun requestInstallPermission() = controller.requestInstallPermission()
    fun dismiss() = controller.dismiss()
}
