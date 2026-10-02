package dev.agentle.interventions.voice

/**
 * Splits text into chunks no longer than `maxLen` (`TextToSpeech.getMaxSpeechInputLength()`), preferring sentence,
 * then clause, then word boundaries (R09 Appendix A.3).
 */
object TtsTextChunker {
    private val sentenceEnd = Regex("(?<=[.!?…])\\s+|\\n+")

    fun split(text: String, maxLen: Int): List<String> {
        require(maxLen > 0) { "maxLen must be positive" }
        val clean = text.trim()
        return when {
            clean.isEmpty() -> emptyList()
            clean.length <= maxLen -> listOf(clean)
            else -> pack(clean, maxLen)
        }
    }

    private fun pack(clean: String, maxLen: Int): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        clean.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }.forEach { sentence ->
            hardSplit(sentence, maxLen).forEach { piece ->
                val separator = if (current.isEmpty()) 0 else 1
                if (current.length + separator + piece.length > maxLen) {
                    out += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun hardSplit(sentence: String, maxLen: Int): List<String> {
        val parts = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxLen) {
            val window = rest.substring(0, maxLen + 1)
            val clause = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "))
            val space = window.lastIndexOf(' ')
            // A clause break keeps its comma or semicolon on the left; otherwise a word break; otherwise a hard cut.
            val cut = when {
                clause > maxLen / 2 -> clause + 1
                space > maxLen / 2 -> space
                else -> maxLen
            }
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }
}
