package com.gimy.tv.domain.model

import com.gimy.tv.data.update.UpdateInfo
import java.io.File

/**
 * 更新流程的狀態。畫面直接把它畫出來，所以放在 domain。
 *
 * 原本定義在 UpdateController 檔尾，逼得設定畫面與更新浮層都得 import 資料層。
 * apkFile 帶的是實體檔案——更新這件事本來就與檔案系統綁在一起，這裡不假裝它不是。
 */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo.Available) : UpdateState()
    data class Downloading(val info: UpdateInfo.Available, val downloaded: Long, val total: Long) : UpdateState()
    data class ReadyToInstall(val info: UpdateInfo.Available, val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
