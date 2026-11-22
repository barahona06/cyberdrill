package com.cyberdrill.app.data

/** A study topic on the owner's NetAcad cybersecurity path. */
data class Topic(
    val id: String,
    val nameEs: String,
    val nameEn: String,
    val descEs: String,
    val descEn: String,
    /** Two-letter monogram shown on the topic card. */
    val monogram: String
) {
    fun name(lang: String): String = if (lang == "en") nameEn else nameEs
    fun desc(lang: String): String = if (lang == "en") descEn else descEs
}

/** One flashcard: a term and its short definition, in both app languages. */
data class Flashcard(
    val topicId: String,
    val termEs: String,
    val termEn: String,
    val defEs: String,
    val defEn: String
) {
    fun term(lang: String): String = if (lang == "en") termEn else termEs
    fun definition(lang: String): String = if (lang == "en") defEn else defEs
}

/** One multiple-choice question with 4 options and an explanation. */
data class QuizQuestion(
    val topicId: String,
    val questionEs: String,
    val questionEn: String,
    val optionsEs: List<String>,
    val optionsEn: List<String>,
    val answerIndex: Int,
    val explanationEs: String,
    val explanationEn: String
) {
    fun question(lang: String): String = if (lang == "en") questionEn else questionEs
    fun options(lang: String): List<String> = if (lang == "en") optionsEn else optionsEs
    fun explanation(lang: String): String = if (lang == "en") explanationEn else explanationEs
}

/**
 * One finished study session, stored locally.
 * mode: "flashcards" | "miniquiz" | "exam". For exams, one record is written
 * per topic touched (same timestamp), so per-topic mastery stays meaningful.
 * Flashcards records carry total = cards seen and correct = cards marked known
 * is NOT tracked; they are excluded from mastery (see AppViewModel.mastery).
 */
data class SessionRecord(
    val mode: String,
    val topicId: String,
    val correct: Int,
    val total: Int,
    val timestamp: Long
)
