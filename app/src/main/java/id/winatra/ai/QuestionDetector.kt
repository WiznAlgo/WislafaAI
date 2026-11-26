package id.winatra.ai

import kotlin.math.min

data class Parsed(
    val prompt: String,        // teks lengkap yang dikirim ke AI
    val searchText: String,    // dipakai mencari di Knowledge
    val summary: String,       // ringkasan singkat untuk ditampilkan di chat
    val questionCount: Int,
    val hasPassage: Boolean
)

/**
 * Menggabungkan beberapa hasil OCR menjadi SATU pertanyaan:
 *  1. membuang bagian yang tumpang tindih antar tangkapan (saat layar di-scroll),
 *  2. memisahkan bacaan / soal / pilihan jawaban (A. B. C. ...),
 *  3. memadatkan bacaan agar muat di konteks model kecil.
 */
object QuestionDetector {
    /** Batas karakter isi prompt (model "ekv1280": prompt + jawaban hanya 1280 token). */
    const val MAX_CHARS = 2000

    private val NOISE = Regex("^[\\d\\s:.,%+\\-/|]{1,8}$")          // jam, baterai, angka tunggal
    private val SPACES = Regex("\\s+")
    private val PARAGRAPH_SPLIT = Regex("\\n\\s*\\n")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")

    private val NUMBERED = Regex(
        "^(?:(?:soal|nomor|no|pertanyaan|question)\\.?\\s*)?\\(?\\d{1,2}\\s*[.):]\\s+\\S",
        RegexOption.IGNORE_CASE
    )
    private val QWORD = Regex(
        "^(?:\\d{1,2}\\s*[.)]\\s*)?(apa|apakah|siapa|siapakah|kapan|dimana|di mana|mengapa|kenapa|" +
            "bagaimana|berapa|manakah|mana|jelaskan|sebutkan|tentukan|hitunglah|hitung|carilah|simpulkan|" +
            "what|who|when|where|why|how|which|explain|choose|select|find|calculate)\\b",
        RegexOption.IGNORE_CASE
    )
    private val QHINT = Regex(
        "yang (tepat|benar|salah|sesuai)|pernyataan (berikut|di bawah)|dapat disimpulkan|" +
            "adalah\\s*(\\.{2,}|…|_{2,})|(\\.{3,}|…|_{3,})\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val OPT_MARK = Regex("(?:^|\\s)\\(?([A-Ea-e])[.)]\\s+(?=\\S)")
    private val OPT_START = Regex("^\\(?[A-Ea-e][.)]\\s+\\S")

    private class Q(var text: String) {
        val options = ArrayList<String>()
    }

    private class OptSplit(val stem: String, val options: List<String>)

    // ============================================================ API

    fun parse(captures: List<String>): Parsed {
        val blocks = mergeCaptures(captures)

        val passage = ArrayList<String>()
        val qs = ArrayList<Q>()
        var lastWasQ = false

        for (b in blocks) {
            val sp = splitOptions(b)
            if (sp != null) {
                if (sp.stem.isNotBlank()) {
                    qs += Q(sp.stem)
                } else if (qs.isEmpty() ||
                    (qs.last().options.isNotEmpty() && sp.options.first().startsWith("A."))
                ) {
                    qs += Q("")                 // pilihan tanpa teks soal (soalnya terpotong / tak terbaca)
                }
                qs.last().options += sp.options
                lastWasQ = false
                continue
            }
            if (isQuestion(b)) {
                qs += Q(b)
                lastWasQ = true
                continue
            }
            if (lastWasQ && qs.last().options.isEmpty() && b.length < 160 &&
                !qs.last().text.trimEnd().let { it.endsWith("?") || it.endsWith(".") || it.endsWith(":") || it.endsWith("!") }
            ) {
                qs.last().text += " $b"         // lanjutan kalimat soal yang terpotong OCR
                continue
            }
            passage += b
            lastWasQ = false
        }

        val valid = qs.filter { it.text.isNotBlank() || it.options.isNotEmpty() }
        val keywords = Text.tokens(
            valid.joinToString(" ") { it.text + " " + it.options.joinToString(" ") }
        ).toSet()

        // ----- teks soal
        val qSb = StringBuilder()
        valid.forEachIndexed { i, q ->
            val label = if (valid.size == 1) "Soal" else "Soal ${i + 1}"
            qSb.append(label).append(": ")
                .append(q.text.trim().ifBlank { "(teks soal tidak terbaca)" }.take(450)).append('\n')
            q.options.forEach { qSb.append(it.take(160)).append('\n') }
            qSb.append('\n')
        }
        var qText = qSb.toString().trim()
        if (qText.length > 1300) qText = qText.take(1300) + "…"

        // ----- prompt
        val sb = StringBuilder()
        val ptxt: String
        if (valid.isEmpty()) {
            val head = "Berikut teks yang diambil dari layar. Jika ada pertanyaan atau soal di dalamnya, " +
                "jawablah. Jika tidak ada, jelaskan isinya dengan singkat.\n\n"
            ptxt = fitPassage(passage, MAX_CHARS - head.length, emptySet())
            sb.append(head).append(ptxt)
        } else {
            val head = "Jawab soal berikut" + (if (passage.isNotEmpty()) " berdasarkan bacaan" else "") +
                ". Untuk pilihan ganda tulis huruf jawaban yang benar lalu alasan singkat satu kalimat. " +
                "Untuk isian atau uraian jawab ringkas.\n\n"
            val budget = (MAX_CHARS - head.length - qText.length - 20).coerceAtLeast(300)
            ptxt = fitPassage(passage, budget, keywords)
            sb.append(head)
            if (ptxt.isNotBlank()) sb.append("Bacaan:\n").append(ptxt).append("\n\n")
            sb.append(qText)
        }

        val first = valid.firstOrNull()?.text?.trim().orEmpty().ifBlank { ptxt }.take(100)
        val summary = when {
            valid.isEmpty() -> "teks layar: $first…"
            else -> "${valid.size} soal" + (if (passage.isNotEmpty()) " + bacaan" else "") + " — $first…"
        }
        return Parsed(
            prompt = sb.toString().trim(),
            searchText = if (valid.isNotEmpty()) qText else ptxt.take(300),
            summary = summary,
            questionCount = valid.size,
            hasPassage = passage.isNotEmpty()
        )
    }

    // ============================================================ klasifikasi

    private fun isQuestion(b: String): Boolean {
        val t = b.trim()
        return t.endsWith("?") || QWORD.containsMatchIn(t) || NUMBERED.containsMatchIn(t) || QHINT.containsMatchIn(t)
    }

    private fun splitOptions(block: String): OptSplit? {
        val ms = OPT_MARK.findAll(block).toList()
        if (ms.isEmpty()) return null
        var expected = ms.first().groupValues[1][0].lowercaseChar()
        val picked = ArrayList<MatchResult>()
        for (m in ms) {
            if (m.groupValues[1][0].lowercaseChar() == expected) {
                picked += m
                expected++
            }
        }
        if (picked.isEmpty()) return null
        if (picked.size < 2 && !OPT_START.containsMatchIn(block)) return null

        val stem = block.substring(0, picked.first().range.first).trim()
        val opts = picked.mapIndexed { i, m ->
            val from = m.range.last + 1
            val to = if (i + 1 < picked.size) picked[i + 1].range.first else block.length
            val letter = m.groupValues[1].uppercase()
            "$letter. ${block.substring(from, to).trim()}"
        }
        return OptSplit(stem, opts)
    }

    // ============================================================ gabung beberapa tangkapan

    private fun mergeCaptures(captures: List<String>): List<String> {
        val acc = ArrayList<String>()
        for (cap in captures) {
            var nb = cap.split(PARAGRAPH_SPLIT).map { it.trim() }
                .filter { it.isNotEmpty() && !NOISE.matches(it) }
            if (acc.isEmpty()) {
                acc += nb
                continue
            }
            // 1) awal tangkapan baru sama dengan akhir tangkapan sebelumnya (hasil scroll)
            var k = min(min(acc.size, nb.size), 8)
            var cut = 0
            while (k > 0) {
                var all = true
                for (i in 0 until k) {
                    if (!similar(acc[acc.size - k + i], nb[i])) { all = false; break }
                }
                if (all) { cut = k; break }
                k--
            }
            nb = nb.drop(cut)
            // 2) satu paragraf terbelah di batas layar: sambung kata yang tumpang tindih
            if (nb.isNotEmpty()) {
                val merged = mergeWordOverlap(acc.last(), nb.first())
                if (merged != null) {
                    acc[acc.size - 1] = merged
                    nb = nb.drop(1)
                }
            }
            // 3) buang blok yang sudah ada (judul / header yang ikut tertangkap lagi)
            for (b in nb) {
                if (acc.takeLast(25).none { similar(it, b) }) acc += b
            }
        }
        return acc
    }

    private fun norm(s: String): String = buildString {
        s.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) }
    }

    private fun similar(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val shortLen = min(x.length, y.length)
        if (shortLen >= 12 && (x.contains(y) || y.contains(x))) return true
        // toleransi salah baca OCR untuk blok pendek-menengah
        if (shortLen >= 15 && x.length <= 300 && y.length <= 300 &&
            shortLen.toDouble() / maxOf(x.length, y.length) >= 0.8
        ) return trigramDice(x, y) >= 0.85
        return false
    }

    private fun trigramDice(x: String, y: String): Double {
        if (x.length < 3 || y.length < 3) return 0.0
        val gx = HashSet<String>()
        for (i in 0..x.length - 3) gx.add(x.substring(i, i + 3))
        val gy = HashSet<String>()
        for (i in 0..y.length - 3) gy.add(y.substring(i, i + 3))
        val inter = gx.count { it in gy }
        return 2.0 * inter / (gx.size + gy.size)
    }

    private fun mergeWordOverlap(a: String, b: String): String? {
        val wa = a.split(SPACES)
        val wb = b.split(SPACES)
        val maxK = min(min(wa.size, wb.size), 40)
        for (k in maxK downTo 4) {
            var ok = true
            for (i in 0 until k) {
                val p = norm(wa[wa.size - k + i])
                if (p.isEmpty() || p != norm(wb[i])) { ok = false; break }
            }
            if (ok) return (wa + wb.drop(k)).joinToString(" ")
        }
        return null
    }

    // ============================================================ padatkan bacaan

    /** Kalau bacaan terlalu panjang: ambil kalimat yang paling nyambung dengan soal, urutan asli dijaga. */
    private fun fitPassage(paragraphs: List<String>, budget: Int, keywords: Set<String>): String {
        val full = paragraphs.joinToString("\n")
        if (full.length <= budget) return full

        val sentences = paragraphs.flatMap { it.split(SENTENCE_SPLIT) }.filter { it.isNotBlank() }
        val scored = sentences.mapIndexed { i, s -> Triple(i, s, Text.tokens(s).count { it in keywords }) }

        val chosen = ArrayList<Triple<Int, String, Int>>()
        var used = 0
        // kalimat pertama (topik) selalu diutamakan
        scored.firstOrNull()?.let {
            if (it.second.length <= budget) { chosen += it; used += it.second.length + 1 }
        }
        for (t in scored.drop(1).sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })) {
            if (used + t.second.length + 1 > budget) continue
            chosen += t
            used += t.second.length + 1
        }
        return chosen.sortedBy { it.first }.joinToString(" ") { it.second }
    }
}
