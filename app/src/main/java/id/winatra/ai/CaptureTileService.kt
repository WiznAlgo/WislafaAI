package id.winatra.ai

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Tombol "Tangkap Soal" di panel cepat (tempat Wi-Fi / Bluetooth).
 * Ketuk -> panel menutup sendiri -> layar ditangkap + di-OCR -> teks disimpan.
 * Kalau tangkap layar belum aktif, ketukan pertama meminta izinnya.
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ScreenCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 21, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val active = ScreenGrabber.active
        val n = CaptureStore.count(this)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Tangkap Soal"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (active) "Tersimpan: $n" else "Ketuk untuk aktifkan"
        }
        tile.updateTile()
    }
}
