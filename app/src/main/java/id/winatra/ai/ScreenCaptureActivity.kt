package id.winatra.ai

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Activity transparan: hanya untuk meminta izin tangkap layar ke sistem (dialog "Mulai sekarang"),
 * lalu menyerahkan izinnya ke service dan menutup diri.
 */
class ScreenCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (ScreenGrabber.active) {
            // Izin sudah ada: langsung minta service menangkap layar, lalu menghilang
            ContextCompat.startForegroundService(
                this,
                Intent(this, AIForegroundService::class.java).setAction(AIForegroundService.ACTION_CAPTURE)
            )
            finish()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Paksa "seluruh layar" supaya bisa menangkap aplikasi apa pun
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AIForegroundService::class.java)
                        .setAction(AIForegroundService.ACTION_PROJECTION)
                        .putExtra(AIForegroundService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(AIForegroundService.EXTRA_DATA, data)
                )
            } else {
                Toast.makeText(this, "Izin tangkap layar ditolak", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        private const val REQ = 4711
    }
}
