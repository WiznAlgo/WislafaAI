package id.winatra.ai

import android.content.Context
import org.json.JSONArray

/**
 * Tempat penampungan hasil OCR sebelum tombol JAWAB ditekan.
 * Hanya TEKS yang disimpan (gambar tidak pernah ditulis ke penyimpanan / galeri).
 */
object CaptureStore {
    const val MAX = 8
    private const val PREFS = "wislafa_captures"
    private const val KEY = "items"

    private val items = ArrayList<String>()
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) items += arr.getString(i)
        } catch (_: Exception) {
            items.clear()
        }
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** Mengembalikan jumlah tangkapan setelah ditambah. */
    @Synchronized
    fun add(context: Context, text: String): Int {
        load(context)
        items += text
        save(context)
        return items.size
    }

    @Synchronized
    fun all(context: Context): List<String> {
        load(context)
        return ArrayList(items)
    }

    @Synchronized
    fun count(context: Context): Int {
        load(context)
        return items.size
    }

    @Synchronized
    fun clear(context: Context) {
        load(context)
        items.clear()
        save(context)
    }
}
