package id.winatra.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

class ModelMissingException : Exception("Model belum dipasang")

/**
 * Pembungkus LLM on-device. Satu instance dipakai bersama oleh UI chat dan service notifikasi
 * (keduanya satu proses). Model dimuat lazy saat pertama dipakai.
 */
object LlmEngine {
    private const val MAX_TOKENS = 1280          // prompt + jawaban (sesuai model "ekv1280")
    private const val TIMEOUT_MS = 240_000L

    private var llm: LlmInference? = null
    private var loadedName: String? = null
    private val mutex = Mutex()                  // satu generasi pada satu waktu

    fun modelFile(context: Context): File? {
        val name = Settings(context).modelName ?: return null
        return File(context.filesDir, "models/$name").takeIf { it.exists() }
    }

    @Synchronized
    private fun ensureLoaded(context: Context): LlmInference {
        val file = modelFile(context) ?: throw ModelMissingException()
        llm?.let { if (loadedName == file.name) return it }
        runCatching { llm?.close() }
        llm = null

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .build()

        return LlmInference.createFromOptions(context.applicationContext, options).also {
            llm = it
            loadedName = file.name
        }
    }

    /** Streaming: [onPartial] dipanggil (thread background) dengan teks yang sudah terkumpul. */
    suspend fun generate(
        context: Context,
        prompt: String,
        onPartial: ((String) -> Unit)? = null
    ): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            val engine = ensureLoaded(context)
            val sb = StringBuilder()
            val finished = CompletableDeferred<Unit>()
            // API baru: listener streaming dipasang per-permintaan, bukan di options.
            val future = engine.generateResponseAsync(prompt) { part, done ->
                sb.append(part)
                onPartial?.invoke(sb.toString())
                if (done) finished.complete(Unit)
            }
            // Kalau generasi gagal, jangan tunggu sampai timeout.
            future.addListener({
                try {
                    future.get()
                    finished.complete(Unit)
                } catch (e: Exception) {
                    finished.completeExceptionally(e.cause ?: e)
                }
            }, { it.run() })
            try {
                withTimeout(TIMEOUT_MS) { finished.await() }
            } catch (e: Exception) {
                runCatching { future.cancel(true) }
                throw e
            }
            sb.toString()
        }
    }

    @Synchronized
    fun close() {
        runCatching { llm?.close() }
        llm = null
        loadedName = null
    }

    /** Bebaskan RAM kalau tidak sedang dipakai. */
    fun releaseIfIdle() {
        if (!mutex.isLocked) close()
    }
}
