package id.winatra.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Satu pintu untuk menjawab; dipakai oleh chat di app dan oleh notifikasi. */
object Responder {
    // Ajar cepat lewat chat / balasan notifikasi:  ingat: judul | isi
    private val TEACH = Regex(
        "^\\s*ingat\\s*:\\s*(.+?)\\s*\\|\\s*(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private enum class Style { GEMMA, LLAMA3, CHATML }

    private const val INSTRUCTION =
        "Kamu adalah Wislafa AI, asisten pribadi yang berjalan offline di HP. " +
            "Jawab singkat, jelas, dan dalam bahasa yang dipakai user. " +
            "Jika ada 'Pengetahuan user', utamakan itu dan jangan mengarang di luar data tersebut."

    /**
     * @param searchText teks untuk mencari di Knowledge (default: pertanyaannya sendiri)
     * @param saveAs     teks ringkas yang disimpan ke riwayat chat (default: pertanyaan lengkap)
     * @param compact    true untuk input panjang (hasil OCR): tanpa riwayat, knowledge dibatasi,
     *                   supaya prompt + jawaban muat di konteks model kecil
     */
    suspend fun answer(
        context: Context,
        question: String,
        onPartial: ((String) -> Unit)? = null,
        searchText: String = question,
        saveAs: String? = null,
        compact: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val db = KnowledgeDb.get(context)

        if (!compact) {
            TEACH.find(question)?.let { m ->
                db.addKnowledge(m.groupValues[1], m.groupValues[2])
                return@withContext "✅ Tersimpan: ${m.groupValues[1]}"
            }
        }

        val hits = db.search(searchText, limit = if (compact) 1 else 2)
        val modelFile = LlmEngine.modelFile(context)
        if (modelFile == null) {
            return@withContext "⚠️ Model AI belum dipasang (buka tab Model).\n\n" + LocalEngine.reply(question, hits)
        }

        val n = modelFile.name.lowercase()
        val style = when {
            "gemma" in n -> Style.GEMMA
            "llama" in n -> Style.LLAMA3
            else -> Style.CHATML          // Qwen dan sejenisnya
        }
        val prompt = buildPrompt(style, question, hits, if (compact) emptyList() else db.recentMemory(8), compact)
        val reply = LlmEngine.generate(context, prompt) { partial -> onPartial?.invoke(clean(partial)) }
            .let(::clean)
            .ifBlank { "(model tidak menghasilkan jawaban)" }

        db.addMemory("user", saveAs ?: question)
        db.addMemory("ai", reply)
        reply
    }

    private fun clean(s: String) =
        s.replace("<end_of_turn>", "").replace("<|eot_id|>", "").replace("<|im_end|>", "").replace("<|endoftext|>", "").trim()

    /** Ambil maksimal 2 pasang tanya-jawab terakhir (batas konteks model kecil). */
    private fun historyPairs(memory: List<MemoryItem>): List<Pair<String, String>> {
        val hist = memory.dropWhile { it.role != "user" }
        val pairs = ArrayList<Pair<String, String>>()
        var i = 0
        while (i + 1 < hist.size) {
            if (hist[i].role == "user" && hist[i + 1].role == "ai") {
                pairs += hist[i].text.take(200) to hist[i + 1].text.take(300)
                i += 2
            } else i++
        }
        return pairs.takeLast(2)
    }

    private fun buildPrompt(style: Style, question: String, hits: List<Hit>, memory: List<MemoryItem>, compact: Boolean): String {
        val knowledge = if (hits.isEmpty()) "" else
            "\n\nPengetahuan user:" + hits.joinToString("") { "\n- ${it.item.title}: ${it.item.content.take(if (compact) 250 else 400)}" }
        val pairs = historyPairs(memory)
        val sb = StringBuilder()

        if (style == Style.GEMMA) {
            pairs.forEach { (u, a) ->
                sb.append("<start_of_turn>user\n$u<end_of_turn>\n<start_of_turn>model\n$a<end_of_turn>\n")
            }
            sb.append("<start_of_turn>user\n$INSTRUCTION$knowledge\n\nPertanyaan: $question<end_of_turn>\n")
            sb.append("<start_of_turn>model\n")
        } else if (style == Style.LLAMA3) {
            // Tanpa <|begin_of_text|>: tokenizer biasanya menambah BOS sendiri
            fun h(role: String, text: String) = "<|start_header_id|>$role<|end_header_id|>\n\n$text<|eot_id|>"
            sb.append(h("system", INSTRUCTION + knowledge))
            pairs.forEach { (u, a) -> sb.append(h("user", u)).append(h("assistant", a)) }
            sb.append(h("user", question))
            sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        } else {   // ChatML (Qwen dan sejenisnya)
            sb.append("<|im_start|>system\n$INSTRUCTION$knowledge<|im_end|>\n")
            pairs.forEach { (u, a) ->
                sb.append("<|im_start|>user\n$u<|im_end|>\n<|im_start|>assistant\n$a<|im_end|>\n")
            }
            sb.append("<|im_start|>user\n$question<|im_end|>\n<|im_start|>assistant\n")
        }
        return sb.toString()
    }
}
