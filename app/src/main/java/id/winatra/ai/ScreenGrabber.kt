package id.winatra.ai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display

/**
 * Menangkap layar langsung ke memori (MediaProjection + ImageReader).
 * Tidak ada file / galeri yang dibuat. Satu kali izin berlaku untuk banyak tangkapan.
 * Frame terbaru disimpan, jadi layar yang diam pun tetap bisa ditangkap.
 */
object ScreenGrabber {
    private const val MAX_SIDE = 2400

    private val lock = Any()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var latest: Image? = null

    val active: Boolean get() = synchronized(lock) { projection != null }

    /** Harus dipanggil saat service sudah foreground bertipe mediaProjection. */
    fun start(context: Context, resultCode: Int, data: Intent, onStopped: () -> Unit): Boolean {
        release()
        val app = context.applicationContext
        val mpm = app.getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data) ?: return false

        val t = HandlerThread("wislafa-grab").also { it.start() }
        try {
            val handler = Handler(t.looper)

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            app.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
            var w = metrics.widthPixels
            var h = metrics.heightPixels
            val longest = maxOf(w, h)
            if (longest > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / longest
                w = (w * s).toInt()
                h = (h * s).toInt()
            }

            // Wajib didaftarkan sebelum createVirtualDisplay (Android 14+)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    val mine = synchronized(lock) { projection === mp }
                    if (!mine) return          // kita sendiri yang menghentikan
                    release()
                    onStopped()
                }
            }, handler)

            val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            ir.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: Exception) { null }
                if (img != null) {
                    synchronized(lock) {
                        if (reader === r) {
                            latest?.close()
                            latest = img
                        } else img.close()
                    }
                }
            }, handler)

            val vd = mp.createVirtualDisplay(
                "wislafa-capture", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ir.surface, null, handler
            )
            synchronized(lock) {
                projection = mp
                reader = ir
                display = vd
                thread = t
            }
            return true
        } catch (e: Exception) {
            runCatching { mp.stop() }
            runCatching { t.quitSafely() }
            throw e
        }
    }

    /** Bitmap dari frame terbaru, atau null kalau belum ada frame. Pemanggil wajib recycle(). */
    fun grab(): Bitmap? = synchronized(lock) {
        val img = latest
        if (img == null) null else try { toBitmap(img) } catch (_: Exception) { null }
    }

    fun stop() = release()

    private fun release() {
        var mp: MediaProjection? = null
        var vd: VirtualDisplay? = null
        var ir: ImageReader? = null
        var t: HandlerThread? = null
        synchronized(lock) {
            mp = projection; vd = display; ir = reader; t = thread
            projection = null; display = null; reader = null; thread = null
            latest?.close()
            latest = null
        }
        runCatching { ir?.setOnImageAvailableListener(null, null) }
        runCatching { vd?.release() }
        runCatching { ir?.close() }
        runCatching { mp?.stop() }
        runCatching { t?.quitSafely() }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val w = img.width
        val h = img.height
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * w
        val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return full
        val cropped = Bitmap.createBitmap(full, 0, 0, w, h)
        full.recycle()
        return cropped
    }
}
