package com.fersaiyan.cyanbridge.tts

object AssistantSpeechQueuePlanner {
    const val MIN_CHUNK_CHARS = 100

    fun buildQueueEntries(text: String): List<String> {
        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
        if (normalized.isBlank()) return emptyList()

        val paragraphs = normalized
            .split(Regex("\\n\\s*\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (paragraphs.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<String>()
        paragraphs.forEach { paragraph ->
            val bulletEntries = Regex("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s*(.+)$")
                .findAll(paragraph)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()

            if (bulletEntries.isNotEmpty()) {
                chunks.addAll(bulletEntries)
                return@forEach
            }

            val collapsed = paragraph.replace(Regex("\\s+"), " ").trim()
            val sentences = Regex("[^.!?]+(?:[.!?]+|$)")
                .findAll(collapsed)
                .map { it.value.trim() }
                .filter { it.isNotBlank() }
                .toList()

            if (sentences.isEmpty()) {
                if (collapsed.isNotBlank()) chunks.add(collapsed)
                return@forEach
            }

            val current = StringBuilder()
            sentences.forEachIndexed { index, sentence ->
                val piece = if (current.isEmpty()) sentence else "${current} $sentence"
                current.clear()
                current.append(piece)

                val shouldFlush = current.length >= MIN_CHUNK_CHARS || index == sentences.lastIndex
                if (shouldFlush) {
                    chunks.add(current.toString().trim())
                    current.clear()
                }
            }
        }

        return chunks.ifEmpty { listOf(normalized) }
    }
}
