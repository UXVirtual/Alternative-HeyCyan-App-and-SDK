package com.fersaiyan.cyanbridge.tts

object AssistantSpeechQueuePlanner {
    const val MIN_CHUNK_CHARS = 100

    fun buildQueueEntries(text: String): List<String> {
        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
        if (normalized.isBlank()) return emptyList()

        val bulletEntries = Regex("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s*(.+)$")
            .findAll(normalized)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (bulletEntries.isNotEmpty()) {
            return bulletEntries
        }

        val trimmed = normalized.replace(Regex("\\s+"), " ").trim()
        val sentences = Regex("[^.!?]+(?:[.!?]+|$)")
            .findAll(trimmed)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()

        if (sentences.isEmpty()) {
            return listOf(trimmed)
        }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (sentence in sentences) {
            val candidate = if (current.isEmpty()) sentence else "${current} $sentence"
            if (current.isEmpty()) {
                current.append(sentence)
                continue
            }

            if (candidate.length >= MIN_CHUNK_CHARS) {
                chunks.add(candidate.trim())
                current.setLength(0)
            } else {
                current.append(" ").append(sentence)
            }
        }

        if (current.isNotBlank()) {
            val remainder = current.toString().trim()
            if (chunks.isNotEmpty() && remainder.length < MIN_CHUNK_CHARS) {
                val lastIndex = chunks.lastIndex
                chunks[lastIndex] = "${chunks[lastIndex]} $remainder".trim()
            } else {
                chunks.add(remainder)
            }
        }

        return chunks.ifEmpty { listOf(trimmed) }
    }
}
