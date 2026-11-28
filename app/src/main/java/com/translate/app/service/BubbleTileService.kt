package com.translate.app.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.translate.app.data.settings.BubblePrefs

/**
 * 下拉通知栏里的「划词翻译」快捷开关：一键显示 / 隐藏悬浮球。
 *
 * 即使 App 被系统「强行停止」（这时无障碍服务也会被系统关掉），
 * 点一下快捷开关也能把 App 拉起来：有 ADB 授权时自动重新开启无障碍服务，并恢复悬浮球。
 */
class BubbleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        AccessibilityEnabler.ensureEnabled(this, restartIfStuck = true)
        BubbleManager.setEnabled(this, !BubblePrefs.isEnabled(this))
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = BubblePrefs.isEnabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "划词翻译"
        tile.updateTile()
    }
}
