package id.winatra.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** OCR on-device (ML Kit, model Latin ikut di dalam APK, tanpa internet). */
object Ocr {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Hasil: paragraf (blok teks) dipisah baris kosong, urut dari atas ke bawah layar.
     * Baris-baris dalam satu paragraf sudah digabung dengan spasi.
     */
    suspend fun read(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks
                    .sortedBy { it.boundingBox?.top ?: 0 }
                    .map { b -> b.lines.joinToString(" ") { it.text }.trim() }
                    .filter { it.isNotEmpty() }
                cont.resume(blocks.joinToString("\n\n"))
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
