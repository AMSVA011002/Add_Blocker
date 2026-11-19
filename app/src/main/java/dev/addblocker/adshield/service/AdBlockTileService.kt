package dev.addblocker.adshield.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.addblocker.adshield.MainActivity
import dev.addblocker.adshield.R
import dev.addblocker.adshield.vpn.AdBlockVpnService
import dev.addblocker.adshield.vpn.VpnStatus

/**
 * Quick Settings tile. Turning protection on requires the system VPN consent
 * dialog the first time, so the tile opens the app in that case.
 */
class AdBlockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (VpnStatus.running.value) {
            AdBlockVpnService.stop(this)
        } else if (VpnService.prepare(this) == null) {
            AdBlockVpnService.start(this)
        } else {
            openApp()
        }
        updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    20,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = VpnStatus.running.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "Filtering DNS" else "Off"
        }
        tile.updateTile()
    }
}
