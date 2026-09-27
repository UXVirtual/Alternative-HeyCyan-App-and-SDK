package com.fersaiyan.cyanbridge.ai.vision

import java.util.Locale

data class ImageQuestionSettings(
    val appLanguageTag: String,
    val defaultQuestion: String,
    val usesBuiltInDefault: Boolean,
)

enum class ImageQuestionRoute {
    PRO_RELAY,
    LOCAL_GEMMA,
    TASKER_GEMINI,
}

/** A single resolved prompt is deliberately shared by every image-question route. */
data class ResolvedImageQuestionPrompt(
    val text: String,
) {
    fun forRoute(route: ImageQuestionRoute): String = when (route) {
        ImageQuestionRoute.PRO_RELAY,
        ImageQuestionRoute.LOCAL_GEMMA,
        ImageQuestionRoute.TASKER_GEMINI
        -> text
    }
}

object ImageQuestionDefaults {
    fun listeningCueForLanguage(languageTag: String): String =
        listeningCuesForLanguage(languageTag).random()

    fun listeningCuesForLanguage(languageTag: String): List<String> = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> listOf("Diga?", "Sim?", "Estou ouvindo.")
        "es" -> listOf("¿Sí?", "Te escucho.", "Adelante.")
        "de" -> listOf("Ja?", "Ich höre zu.", "Bitte.")
        "fr" -> listOf("Oui ?", "Je vous écoute.", "Je vous en prie.")
        "it" -> listOf("Sì?", "Ti ascolto.", "Dimmi.")
        "zh" -> listOf("嗯？", "我在听。", "请说。")
        "ko" -> listOf("네?", "듣고 있습니다.", "말씀하세요.")
        "ru" -> listOf("Да?", "Я слушаю.", "Говорите.")
        else -> listOf("Uh-huh?", "Yeah?", "Okay, shoot.")
    }

    fun questionCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Pergunte."
        "es" -> "Pregunta."
        "de" -> "Frag."
        "fr" -> "Demandez."
        "it" -> "Chiedi."
        "zh" -> "请提问。"
        "ko" -> "질문하세요."
        "ru" -> "Спросите."
        else -> "Ask."
    }

    fun questionForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Dê-me uma descrição concisa da imagem"
        "es" -> "Dame una descripción concisa de la imagen"
        "de" -> "Gib mir eine kurze Beschreibung des Bildes"
        "fr" -> "Donnez-moi une description concise de l'image"
        "it" -> "Dammi una descrizione concisa dell'immagine"
        "zh" -> "请简洁地描述这张图片"
        "ko" -> "이미지를 간단히 설명해 주세요"
        "ru" -> "Дайте мне краткое описание изображения"
        else -> "Give me a concise description of the image"
    }

    fun responseLanguageInstruction(languageTag: String): String =
        "Answer only in ${languageLabel(languageTag)} ($languageTag)."

    private fun languageLabel(languageTag: String): String =
        Locale.forLanguageTag(languageTag)
            .getDisplayLanguage(Locale.ENGLISH)
            .ifBlank { languageTag }
}

object ImageQuestionPromptResolver {
    fun resolve(
        settings: ImageQuestionSettings,
        userQuestion: String?,
    ): ResolvedImageQuestionPrompt {
        val languageTag = settings.appLanguageTag.ifBlank { "en" }
        val question = userQuestion?.trim().takeUnless { it.isNullOrBlank() }
            ?: settings.defaultQuestion.trim().ifBlank {
                ImageQuestionDefaults.questionForLanguage(languageTag)
            }

        return ResolvedImageQuestionPrompt(
            text = "$question\n\n${ImageQuestionDefaults.responseLanguageInstruction(languageTag)}",
        )
    }
}
