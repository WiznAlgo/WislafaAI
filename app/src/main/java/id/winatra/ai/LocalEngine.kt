package id.winatra.ai

/** Mesin jawab 100% offline: mengambil jawaban dari knowledge yang diajarkan user. */
object LocalEngine {
    fun reply(question: String, hits: List<Hit>): String {
        if (hits.isEmpty()) {
            return "Aku belum punya pengetahuan soal itu.\n" +
                "Ajari lewat tab Knowledge di app Wislafa AI, atau balas notifikasi dengan \"ingat: judul | isi\"."
        }
        val best = hits.first().item
        val sb = StringBuilder(best.content)
        if (hits.size > 1) {
            sb.append("\n\nTerkait: ").append(hits.drop(1).joinToString(", ") { it.item.title })
        }
        return sb.toString()
    }
}
