package id.winatra.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Unduh / pasang file model ke penyimpanan privat app (filesDir/models). */
object ModelManager {
    // Qwen2.5-0.5B-Instruct (Apache-2.0, tidak perlu login), sekitar 547 MB
    const val DEFAULT_URL =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/" +
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

    // Qwen2.5-1.5B-Instruct (Apache-2.0, tanpa login), sekitar 1,6 GB. Lebih pintar & bahasa Indonesia lebih baik.
    const val QWEN_1_5B_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

    private fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    /** Return nama file model yang terpasang. [onProgress] (selesai, total; total -1 jika tidak diketahui). */
    suspend fun download(context: Context, url: String, onProgress: (Long, Long) -> Unit): String =
        withContext(Dispatchers.IO) {
            val name = URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8")
                .ifBlank { "model.task" }
            val tmp = File(dir(context), "$name.part").also { it.delete() }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    error("Model ini butuh login/lisensi (HTTP $code). Unduh manual di browser, lalu pakai 'Pasang dari file'.")
                }
                if (code !in 200..299) error("Server menjawab HTTP $code")
                conn.inputStream.use { copy(it, tmp, conn.contentLengthLong, onProgress) }
                finish(context, tmp, name)
            } catch (e: Exception) {
                tmp.delete(); throw e
            } finally {
                conn.disconnect()
            }
        }

    suspend fun importFromUri(context: Context, uri: Uri, onProgress: (Long, Long) -> Unit): String =
        withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            } ?: "model.task"
            val tmp = File(dir(context), "$name.part").also { it.delete() }
            try {
                val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                context.contentResolver.openInputStream(uri)!!.use { copy(it, tmp, total, onProgress) }
                finish(context, tmp, name)
            } catch (e: Exception) {
                tmp.delete(); throw e
            }
        }

    private suspend fun copy(input: InputStream, out: File, total: Long, onProgress: (Long, Long) -> Unit) {
        val ctx = currentCoroutineContext()
        out.outputStream().use { o ->
            val buf = ByteArray(1 shl 20)
            var done = 0L
            var n: Int
            while (input.read(buf).also { n = it } >= 0) {
                ctx.ensureActive()               // bisa dibatalkan
                o.write(buf, 0, n)
                done += n
                onProgress(done, total)
            }
        }
    }

    private fun finish(context: Context, tmp: File, name: String): String {
        if (tmp.length() < 1_000_000) error("File terlalu kecil — bukan file model yang valid")
        LlmEngine.close()
        dir(context).listFiles()?.filter { it.name != tmp.name }?.forEach { it.delete() }  // simpan 1 model saja
        val final = File(dir(context), name)
        if (!tmp.renameTo(final)) error("Gagal menyimpan file model")
        Settings(context).modelName = name
        return name
    }
}
