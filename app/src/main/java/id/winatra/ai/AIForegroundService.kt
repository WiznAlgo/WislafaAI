package id.winatra.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wislafa AI di latar belakang. Dua notifikasi:
 *
 *  1. KONTROL (foreground, senyap)  : tombol TANGKAP / JAWAB (n) / MATIKAN
 *       - TANGKAP : tangkap layar -> OCR -> simpan teksnya (bisa 2-3 kali untuk 1 soal)
 *       - JAWAB   : gabungkan semua tangkapan jadi 1 pertanyaan (deteksi soal + bacaan) lalu dijawab AI
 *  2. CHAT (gaya WhatsApp)          : kolom BALAS untuk bertanya; jawaban AI muncul sebagai pesan baru
 */
class AIForegroundService : Service() {

    private class Line(val fromUser: Boolean, var text: String, val time: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var capturing = false
    private var projectionPending = false
    private var status = "Siap."
    private val chat = ArrayList<Line>()
    @Volatile private var lastPush = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHAT_CHANNEL, "Jawaban Wislafa AI", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CONTROL_CHANNEL, "Kontrol Wislafa AI", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {           // dihidupkan ulang oleh sistem
            goForeground()
            if (chat.isEmpty()) greet()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_PROJECTION -> startProjection(intent)
            ACTION_CAPTURE -> { goForeground(); capture() }
            ACTION_ANSWER_CAPTURES -> { goForeground(); answerCaptures() }
            ACTION_RESET -> { goForeground(); reset() }
            ACTION_ASK -> {
                goForeground()
                val q = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
                ask(q)
            }
            else -> {
                goForeground()
                if (chat.isEmpty()) greet()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ScreenGrabber.stop()
        LlmEngine.releaseIfIdle()   // bebaskan RAM model
        super.onDestroy()
    }

    private fun stopEverything() {
        job?.cancel()
        ScreenGrabber.stop()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_CHAT_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        refreshTile()
        stopSelf()
    }

    // ================================================================ tangkap layar

    private fun startProjection(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

        // Android 10+: service HARUS sudah foreground bertipe mediaProjection sebelum getMediaProjection
        projectionPending = true
        goForeground()

        val ok = try {
            data != null && ScreenGrabber.start(this, code, data) {
                // Dihentikan dari sistem (mis. user menekan "Berhenti berbagi")
                scope.launch {
                    status = "Tangkap layar berhenti. Tekan AKTIFKAN untuk memulai lagi."
                    goForeground()
                }
            }
        } catch (e: Exception) {
            status = "Gagal mengaktifkan tangkap layar: ${e.message}"
            false
        }
        projectionPending = false
        if (ok) status = "Tangkap layar aktif. Buka soal di layar, lalu tekan TANGKAP."
        else if (!status.startsWith("Gagal")) status = "Gagal mengaktifkan tangkap layar."
        goForeground()
        if (chat.isEmpty()) greet()
    }

    private fun capture() {
        if (!ScreenGrabber.active) {
            setStatus("Tangkap layar belum aktif. Tekan AKTIFKAN dulu.")
            return
        }
        if (capturing) return
        if (CaptureStore.count(this) >= CaptureStore.MAX) {
            setStatus("Tangkapan penuh (${CaptureStore.MAX}). Tekan JAWAB atau RESET.")
            return
        }
        capturing = true
        scope.launch {
            try {
                setStatus("Menangkap layar…")
                delay(CAPTURE_DELAY_MS)     // beri waktu panel notifikasi menutup
                val bmp = withContext(Dispatchers.Default) { ScreenGrabber.grab() }
                if (bmp == null) {
                    setStatus("Belum ada gambar layar. Coba tekan TANGKAP lagi.")
                    return@launch
                }
                val text = try { Ocr.read(bmp) } finally { bmp.recycle() }
                if (text.isBlank()) {
                    setStatus("Tidak ada teks terbaca. Layar kosong atau aplikasinya memblokir tangkap layar.")
                    return@launch
                }
                val n = CaptureStore.add(this@AIForegroundService, text)
                val words = text.split(Regex("\\s+")).size
                setStatus("Tangkapan $n tersimpan ($words kata). Tangkap lagi atau tekan JAWAB.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("OCR gagal: ${e.message}")
            } finally {
                capturing = false
            }
        }
    }

    private fun answerCaptures() {
        if (job?.isActive == true) {
            setStatus("Masih memproses jawaban sebelumnya…")
            return
        }
        val caps = CaptureStore.all(this)
        if (caps.isEmpty()) {
            setStatus("Belum ada tangkapan. Tekan TANGKAP dulu.")
            return
        }
        val parsed = QuestionDetector.parse(caps)
        val shown = "📸 ${caps.size} tangkapan • ${parsed.summary}"
        addLine(true, shown)
        startAnswer(
            question = parsed.prompt,
            searchText = parsed.searchText,
            saveAs = shown,
            compact = true
        ) { CaptureStore.clear(this) }
    }

    // ================================================================ chat gaya WhatsApp

    private fun ask(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {          // tetap perbarui notifikasi supaya indikator "mengirim" hilang
            showChat(false)
            return
        }
        job?.cancel()
        addLine(true, q)
        startAnswer(question = q, searchText = q, saveAs = null, compact = false)
    }

    private fun startAnswer(
        question: String,
        searchText: String,
        saveAs: String?,
        compact: Boolean,
        onSuccess: (() -> Unit)? = null
    ) {
        val ai = addLine(false, "⏳ Berpikir…")
        showChat(false)
        setStatus("Memproses…")
        job = scope.launch {
            try {
                val reply = Responder.answer(
                    applicationContext, question,
                    { partial ->
                        // callback datang dari thread lain; Android membatasi frekuensi update notifikasi
                        val now = System.currentTimeMillis()
                        if (now - lastPush > 700) {
                            lastPush = now
                            scope.launch { ai.text = partial; showChat(false) }
                        }
                    },
                    searchText, saveAs, compact
                )
                ai.text = reply
                showChat(true)          // bunyi / heads-up saat jawaban sudah jadi
                onSuccess?.invoke()
                setStatus("Siap.")
            } catch (e: CancellationException) {
                chat.remove(ai)
                showChat(false)
                throw e
            } catch (e: Exception) {
                ai.text = "Error: ${e.message}"
                showChat(true)
                setStatus("Siap.")
            }
        }
    }

    private fun greet() {
        addLine(false, "Halo! Balas notifikasi ini untuk bertanya.\nUntuk soal di layar: TANGKAP (boleh beberapa kali), lalu JAWAB.")
        showChat(false)
    }

    private fun reset() {
        job?.cancel()
        CaptureStore.clear(this)
        chat.clear()
        status = "Direset. Tangkapan dikosongkan."
        greet()
        updateControl()
    }

    private fun addLine(fromUser: Boolean, text: String): Line {
        val l = Line(fromUser, text, System.currentTimeMillis())
        chat += l
        while (chat.size > 8) chat.removeAt(0)
        return l
    }

    // ================================================================ notifikasi

    private fun foregroundType(): Int {
        var t = 0
        if (Build.VERSION.SDK_INT >= 34) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT >= 29 && (projectionPending || ScreenGrabber.active)) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return t
    }

    private fun goForeground() {
        ServiceCompat.startForeground(this, NOTIF_ID, buildControl(), foregroundType())
        refreshTile()
    }

    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(this, ComponentName(this, CaptureTileService::class.java))
        }
    }

    private fun setStatus(s: String) {
        status = s
        updateControl()
    }

    private fun updateControl() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildControl())
        refreshTile()
    }

    private fun showChat(alert: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_CHAT_ID, buildChat(alert))
    }

    private fun servicePi(code: Int, action: String): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, AIForegroundService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openAppPi(): PendingIntent = PendingIntent.getActivity(
        this, 17, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildControl(): Notification {
        val active = ScreenGrabber.active
        val n = CaptureStore.count(this)

        val captureAction = if (active) {
            NotificationCompat.Action.Builder(0, "TANGKAP", servicePi(11, ACTION_CAPTURE)).build()
        } else {
            val pi = PendingIntent.getActivity(
                this, 12,
                Intent(this, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(0, "AKTIFKAN", pi).build()
        }

        val body = "$status\nTangkapan tersimpan: $n"
        return NotificationCompat.Builder(this, CONTROL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (active) "Wislafa AI • tangkap layar aktif" else "Wislafa AI")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPi())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(captureAction)
            .addAction(0, "JAWAB ($n)", servicePi(13, ACTION_ANSWER_CAPTURES))
            .addAction(0, "MATIKAN", servicePi(14, ACTION_STOP))
            .build()
    }

    private fun buildChat(alert: Boolean): Notification {
        val me = Person.Builder().setName("Kamu").build()
        val ai = Person.Builder().setName("Wislafa AI").build()
        val style = NotificationCompat.MessagingStyle(me)
        chat.forEach { style.addMessage(it.text.take(3000), it.time, if (it.fromUser) me else ai) }

        val replyPi = PendingIntent.getService(
            this, 16,
            Intent(this, AIForegroundService::class.java).setAction(ACTION_ASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE   // wajib utk RemoteInput
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_notif, "Balas", replyPi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Tanya Wislafa…").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(false)
            .build()

        return NotificationCompat.Builder(this, CHAT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!alert)
            .setOngoing(true)
            .setContentIntent(openAppPi())
            .addAction(replyAction)
            .addAction(0, "RESET", servicePi(15, ACTION_RESET))
            .build()
    }

    companion object {
        const val CHAT_CHANNEL = "winatra_ai"          // id lama dipertahankan
        const val CONTROL_CHANNEL = "wislafa_control"
        const val NOTIF_ID = 1001
        const val NOTIF_CHAT_ID = 1002
        const val CAPTURE_DELAY_MS = 900L

        const val ACTION_ASK = "id.winatra.ai.ACTION_ASK"
        const val ACTION_STOP = "id.winatra.ai.ACTION_STOP"
        const val ACTION_CAPTURE = "id.winatra.ai.ACTION_CAPTURE"
        const val ACTION_ANSWER_CAPTURES = "id.winatra.ai.ACTION_ANSWER_CAPTURES"
        const val ACTION_RESET = "id.winatra.ai.ACTION_RESET"
        const val ACTION_PROJECTION = "id.winatra.ai.ACTION_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val KEY_REPLY = "key_reply"
    }
}
