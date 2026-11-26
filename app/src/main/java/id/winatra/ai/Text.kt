package id.winatra.ai

object Text {
    private val STOP = setOf(
        "yang", "dan", "atau", "dengan", "untuk", "dari", "pada", "adalah", "itu", "ini",
        "apa", "siapa", "kapan", "dimana", "bagaimana", "kenapa", "mengapa", "apakah",
        "bisa", "akan", "tolong", "the", "and", "for", "what", "who", "how", "why",
        "are", "was", "with", "this", "that", "does", "can"
    )
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun tokens(s: String): List<String> =
        s.lowercase().split(SPLIT).filter { it.length > 2 && it !in STOP }
}
