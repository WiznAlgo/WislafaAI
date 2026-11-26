package id.winatra.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Knowledge(val id: Long, val title: String, val content: String)
data class Hit(val item: Knowledge, val score: Double)
data class MemoryItem(val role: String, val text: String)

/**
 * SQLite tunggal untuk:
 *  - tabel `knowledge` : pengetahuan yang diajarkan user
 *  - tabel `memory`    : riwayat tanya-jawab (memori percakapan)
 */
class KnowledgeDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "winatra.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE knowledge(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE memory(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---------- Knowledge ----------

    fun addKnowledge(title: String, content: String): Long {
        val cv = ContentValues().apply {
            put("title", title.trim())
            put("content", content.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("knowledge", null, cv)
    }

    fun countKnowledge(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM knowledge", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun listKnowledge(): List<Knowledge> {
        val out = ArrayList<Knowledge>()
        readableDatabase.rawQuery("SELECT id, title, content FROM knowledge ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out += Knowledge(c.getLong(0), c.getString(1), c.getString(2))
        }
        return out
    }

    fun deleteKnowledge(id: Long) {
        writableDatabase.delete("knowledge", "id = ?", arrayOf(id.toString()))
    }

    fun clearKnowledge() {
        writableDatabase.delete("knowledge", null, null)
    }

    /** Pencarian sederhana berbasis kecocokan kata (judul bobot lebih besar dari isi). */
    fun search(query: String, limit: Int = 3, minScore: Double = 2.0): List<Hit> {
        val qt = Text.tokens(query)
        if (qt.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        readableDatabase.rawQuery("SELECT id, title, content FROM knowledge", null).use { c ->
            while (c.moveToNext()) {
                val k = Knowledge(c.getLong(0), c.getString(1), c.getString(2))
                val titleTokens = Text.tokens(k.title).toSet()
                val contentTokens = Text.tokens(k.content).toSet()
                var score = 0.0
                for (t in qt) {
                    score += when {
                        t in titleTokens -> 3.0
                        titleTokens.any { it.startsWith(t) || t.startsWith(it) } -> 1.5
                        else -> 0.0
                    }
                    if (t in contentTokens) score += 1.0
                }
                if (score >= minScore) hits += Hit(k, score)
            }
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    // ---------- Memory ----------

    fun addMemory(role: String, text: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("role", role)
            put("text", text)
            put("created_at", System.currentTimeMillis())
        }
        db.insert("memory", null, cv)
        // simpan maksimal 200 baris terakhir
        db.execSQL("DELETE FROM memory WHERE id NOT IN (SELECT id FROM memory ORDER BY id DESC LIMIT 200)")
    }

    /** Urutan lama -> baru. */
    fun recentMemory(n: Int): List<MemoryItem> {
        val out = ArrayList<MemoryItem>()
        readableDatabase.rawQuery(
            "SELECT role, text FROM memory ORDER BY id DESC LIMIT ?", arrayOf(n.toString())
        ).use { c -> while (c.moveToNext()) out += MemoryItem(c.getString(0), c.getString(1)) }
        return out.reversed()
    }

    fun clearMemory() {
        writableDatabase.delete("memory", null, null)
    }

    companion object {
        @Volatile private var instance: KnowledgeDb? = null
        fun get(context: Context): KnowledgeDb =
            instance ?: synchronized(this) {
                instance ?: KnowledgeDb(context.applicationContext).also { instance = it }
            }
    }
}
