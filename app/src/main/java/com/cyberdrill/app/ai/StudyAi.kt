package com.cyberdrill.app.ai

import com.cyberdrill.app.data.QuizQuestion
import com.cyberdrill.app.data.SeedBank
import com.cyberdrill.app.data.Topics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prompt builders and strict output parsers for the AI study tasks.
 *
 * Routing contract (why each task lives where it lives):
 *  - [definitionPrompt] and [quizQuestionPrompt] are SHORT and structured:
 *    one term, one question, capped output. They run through the HYBRID
 *    model (PREFER_ON_DEVICE) so Gemini Nano can answer them offline.
 *    Prompts are written in English (the only language Google validates
 *    on-device, together with Korean) and instruct the model to answer in
 *    the student's language with a hard length cap.
 *  - [examPrompt] and [analysisPrompt] need long, rich Spanish generation:
 *    they run through the CLOUD model only.
 *
 * Parsers enforce the contract: if the model output does not match it,
 * the function returns null and the caller falls back to the seed bank.
 * The single-question parser is strict; the exam parser is tolerant per
 * item (see [parseExam]) because discarding ten good questions over one
 * malformed item — or over a truncated tail — costs the whole feature.
 */
object StudyAi {

    private fun languageName(lang: String): String = if (lang == "en") "English" else "Spanish"

    fun definitionPrompt(term: String, lang: String): String =
        "You are a cybersecurity tutor. Define the term \"$term\" for a certification " +
            "student in at most 45 words, in simple ${languageName(lang)}. " +
            "Plain text only, no preamble, no markdown."

    /**
     * Rotating angles for single-question generation. A near-deterministic
     * model (Gemini Nano above all) answers the identical prompt with the
     * identical question; a different angle per call makes repeats unlikely
     * even before the exclusion below is applied.
     */
    private val questionFocuses = listOf(
        "Focus the question on a short practical workplace scenario the student must judge.",
        "Focus the question on the precise meaning of one key term of the topic.",
        "Focus the question on a recognized best practice for this topic.",
        "Focus the question on a common mistake or misconception about this topic.",
        "Phrase the question as a which-of-the-following-is-NOT question."
    )

    fun randomQuestionFocus(): String = questionFocuses.random()

    /**
     * Prompt for ONE fresh question. [excludeQuestion] is the question the
     * student is already looking at (in their language): the model is told
     * never to repeat or merely rephrase it. [focus] is one rotating angle
     * from [questionFocuses]. [stronger] escalates the exclusion wording
     * for the single retry after a repeated answer.
     */
    fun quizQuestionPrompt(
        topicNameEn: String,
        lang: String,
        excludeQuestion: String? = null,
        focus: String? = null,
        stronger: Boolean = false
    ): String {
        val sb = StringBuilder(
            "You are a cybersecurity tutor. Create ONE multiple-choice practice question " +
                "about \"$topicNameEn\" for a certification student. Answer in ${languageName(lang)}. " +
                "Use EXACTLY this format and nothing else:\n" +
                "QUESTION: <the question>\n" +
                "A) <option>\n" +
                "B) <option>\n" +
                "C) <option>\n" +
                "D) <option>\n" +
                "ANSWER: <one letter A-D>\n" +
                "WHY: <one short sentence explaining the correct answer>"
        )
        if (!focus.isNullOrBlank()) sb.append("\n").append(focus)
        if (!excludeQuestion.isNullOrBlank()) {
            if (stronger) {
                sb.append(
                    "\nCRITICAL: a previous attempt gave back the question below, which the " +
                        "student is ALREADY looking at. Choose a completely different aspect " +
                        "of the topic, with a different correct-answer idea. Never output " +
                        "this question or a rephrasing of it: \"$excludeQuestion\""
                )
            } else {
                sb.append(
                    "\nIMPORTANT: the question MUST be different from the one the student " +
                        "is already looking at — do not repeat it or merely rephrase it; " +
                        "pick another aspect of the topic. Already shown: \"$excludeQuestion\""
                )
            }
        }
        return sb.toString()
    }

    /**
     * A NEW question for [topicId] that is never the one already on screen.
     *
     * The "New question with AI" button used to fire one fixed prompt and,
     * on failure or repetition, left the screen untouched — on the owner's
     * Fold7 the first tap changed the question and later taps appeared to
     * do nothing, because Gemini Nano returns the identical question for
     * the identical prompt. This flow layers three guarantees:
     *  1. Every prompt carries the current question as an exclusion plus a
     *     random rotating focus (see [quizQuestionPrompt]).
     *  2. If the model still returns the same question, one retry with a
     *     stronger variation directive.
     *  3. If generation returns null, fails to parse, or repeats again, a
     *     seed-bank question for the topic that differs from the current
     *     one (source SEED_BANK, "Local bank" badge).
     * So whenever any source is available, the tap visibly changes the
     * question.
     */
    suspend fun freshQuestion(
        topicId: String,
        topicNameEn: String,
        lang: String,
        currentText: String?
    ): Pair<QuizQuestion, AiSource> {
        val first = generateParsed(topicId, topicNameEn, lang, currentText, stronger = false)
        if (first != null && !isSameQuestion(first.first, lang, currentText)) return first
        if (first != null) {
            // The model answered but repeated the on-screen question:
            // one retry with the stronger directive before giving up on AI.
            val second = generateParsed(topicId, topicNameEn, lang, currentText, stronger = true)
            if (second != null && !isSameQuestion(second.first, lang, currentText)) return second
        }
        val bank = SeedBank.questionsFor(topicId)
        val seed = bank.filter { !isSameQuestion(it, lang, currentText) }.randomOrNull()
            ?: bank.firstOrNull()
            ?: SeedBank.questions.first()
        return seed to AiSource.SEED_BANK
    }

    private suspend fun generateParsed(
        topicId: String,
        topicNameEn: String,
        lang: String,
        currentText: String?,
        stronger: Boolean
    ): Pair<QuizQuestion, AiSource>? {
        val result = HybridAiClient.generateHybrid(
            quizQuestionPrompt(
                topicNameEn = topicNameEn,
                lang = lang,
                excludeQuestion = currentText,
                focus = randomQuestionFocus(),
                stronger = stronger
            ),
            creative = true
        ) ?: return null
        val parsed = parseQuizQuestion(result.text, topicId, lang) ?: return null
        return parsed to result.source
    }

    private fun isSameQuestion(q: QuizQuestion, lang: String, currentText: String?): Boolean {
        if (currentText.isNullOrBlank()) return false
        return normalizeQuestion(q.question(lang)) == normalizeQuestion(currentText)
    }

    private fun normalizeQuestion(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase()

    /** Questions in a full exam; the generation is split into blocks. */
    const val EXAM_QUESTION_COUNT = 10

    /** A block counts as usable with at least this many sane questions. */
    private const val EXAM_BLOCK_MIN = 4

    /** The assembled exam is served with at least this many questions. */
    private const val EXAM_MIN_QUESTIONS = 8

    /**
     * Prompt for ONE exam block: [count] questions over [topicNamesEn]
     * (half the topic list), in the same JSON shape the parser has always
     * consumed. The full exam is two of these fired in parallel — see
     * [fetchExam]. Two deliberate trims versus the old monolith prompt,
     * both pure output-token savings: the explanation is ONE short
     * sentence (was 1–2) and the options are kept concise; the question
     * quality bar is unchanged.
     */
    fun examBlockPrompt(lang: String, topicNamesEn: List<String>, count: Int): String {
        val topics = topicNamesEn.joinToString(", ")
        return "You are a cybersecurity certification tutor. Create $count multiple-choice " +
            "practice questions covering these topics: $topics. " +
            "Write every question, option and explanation in ${languageName(lang)}. " +
            "Each question has exactly 4 options and one correct answer. " +
            "Keep each option concise and each explanation to ONE short sentence. " +
            "Return ONLY valid JSON, no markdown fences, in exactly this shape:\n" +
            "{\"questions\":[{\"topic\":\"<topic name in ${languageName(lang)}>\",\"question\":\"...\",\"options\":[\"...\",\"...\",\"...\",\"...\"],\"answer\":0,\"explanation\":\"...\"}]}"
    }

    /**
     * The original single-call exam prompt (all 10 questions at once).
     * Kept for reference/compat; [fetchExam] no longer uses it — the
     * monolith was the wait problem (see fetchExam's doc).
     */
    fun examPrompt(lang: String): String {
        val topics = Topics.all.joinToString(", ") { it.nameEn }
        return "You are a cybersecurity certification tutor. Create a practice exam with " +
            "EXACTLY 10 multiple-choice questions covering these topics: $topics. " +
            "Write every question, option and explanation in ${languageName(lang)}. " +
            "Each question has exactly 4 options and one correct answer. " +
            "Each explanation is 1-2 sentences. " +
            "Return ONLY valid JSON, no markdown fences, in exactly this shape:\n" +
            "{\"questions\":[{\"topic\":\"<topic name in ${languageName(lang)}>\",\"question\":\"...\",\"options\":[\"...\",\"...\",\"...\",\"...\"],\"answer\":0,\"explanation\":\"...\"}]}"
    }

    fun analysisPrompt(lang: String, perTopic: Map<String, Pair<Int, Int>>, totalCorrect: Int, total: Int): String {
        val detail = perTopic.entries.joinToString("; ") { (topic, sc) ->
            "$topic: ${sc.first}/${sc.second} correct"
        }
        return "You are a cybersecurity study tutor. A student finished a practice " +
            "exam: $totalCorrect of $total correct. Per topic: $detail. " +
            "Write a short analysis in ${languageName(lang)} (4-6 sentences): what is solid, " +
            "what to review first and one concrete study tip. Plain text, no markdown."
    }

    /** Parse the strict line format of [quizQuestionPrompt]. Null on any mismatch. */
    fun parseQuizQuestion(raw: String, topicId: String, lang: String): QuizQuestion? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun find(prefix: String): String? =
            lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
                ?.substring(prefix.length)?.trim()

        val q = find("QUESTION:") ?: return null
        val opts = listOf("A)", "B)", "C)", "D)").map { find(it) ?: return null }
        val answerLetter = find("ANSWER:")?.uppercase()?.firstOrNull { it in 'A'..'D' } ?: return null
        val why = find("WHY:") ?: return null
        val answerIndex = answerLetter - 'A'
        return if (lang == "en") {
            QuizQuestion(topicId, "", q, emptyList(), opts, answerIndex, "", why)
        } else {
            QuizQuestion(topicId, q, "", opts, emptyList(), answerIndex, why, "")
        }
    }

    /**
     * Outcome of ONE exam block, success or failure. On failure, [note]
     * says exactly what happened — the cloud error verbatim (exception
     * class + message, snapshotted from HybridAiClient.lastError), or the
     * response size vs. the sane questions it yielded — so a failed exam
     * fetch can be reported block by block instead of failing in silence.
     */
    private data class BlockOutcome(
        val questions: List<QuizQuestion>?,
        val source: AiSource?,
        val note: String
    )

    /**
     * Fetch a full exam from the cloud — as TWO blocks IN PARALLEL.
     *
     * Why: the old flow asked for all 10 questions in ONE call (~2,000
     * output tokens of Spanish JSON) under a 90 s cap, and on failure
     * retried the WHOLE monolith — the owner confirmed the exam did
     * arrive, but only after a long, mute wait on the "Preparing your
     * exam..." screen (worst case ~180 s). Output tokens are generated
     * sequentially, so one 2,000-token answer costs roughly twice one
     * 1,000-token answer; two 5-question blocks fired at the same time
     * (each on the tuned exam model — JSON mode, see HybridAiClient)
     * make the wall-clock wait ≈ the slowest block.
     *
     * Failure shape is per block: a block whose response arrives but
     * does not parse is reported through [HybridAiClient.reportFailure]
     * and retried ONCE on its own; a block that returns null already had
     * its two model chances inside generateExamBlock (tuned, then
     * default), so it is not re-fired. The exam is served when the blocks
     * together hold at least [EXAM_MIN_QUESTIONS] sane questions (8–10).
     *
     * Below that, null — and the reason is RECORDED, never silent:
     * fetchExam writes the per-block outcome to
     * [HybridAiClient.recordExamFailure] and the exam screen shows it
     * verbatim on an error screen (retry, or the seed bank as an
     * EXPLICIT choice). The §15 build swapped in the seed bank without a
     * word and the owner got a "Local bank" exam with no clue why; that
     * silent substitution is the bug this contract removes.
     *
     * [onProgress] reports (questions ready, exam target) as each block
     * lands, so the loading screen can show real progress instead of a
     * mute spinner.
     *
     * Returns the questions plus their real source, or null when the
     * cloud could not assemble the exam.
     */
    suspend fun fetchExam(
        lang: String,
        onProgress: ((ready: Int, total: Int) -> Unit)? = null
    ): Pair<List<QuizQuestion>, AiSource>? = coroutineScope {
        val groups = Topics.all.chunked((Topics.all.size + 1) / 2)
        val perBlock = EXAM_QUESTION_COUNT / groups.size
        val ready = AtomicInteger(0)
        val outcomes = groups.mapIndexed { i, group ->
            async {
                i to fetchExamBlock(lang, group.map { it.nameEn }, perBlock) { count ->
                    onProgress?.invoke(ready.addAndGet(count), EXAM_QUESTION_COUNT)
                }
            }
        }.awaitAll().sortedBy { it.first }.map { it.second }
        val questions = outcomes.flatMap { it.questions.orEmpty() }
        if (questions.size >= EXAM_MIN_QUESTIONS) {
            HybridAiClient.clearExamFailure()
            questions.take(EXAM_QUESTION_COUNT) to outcomes.mapNotNull { it.source }.first()
        } else {
            val detail = buildString {
                outcomes.forEachIndexed { i, outcome ->
                    if (i > 0) append(" · ")
                    append("Block ${i + 1}: ${outcome.note}")
                }
                append(" · Usable total: ${questions.size}/$EXAM_QUESTION_COUNT (minimum $EXAM_MIN_QUESTIONS)")
                HybridAiClient.lastError.value?.let { append(" · Last error: $it") }
            }
            HybridAiClient.recordExamFailure(detail)
            null
        }
    }

    /**
     * One exam block: [count] questions over [topicNamesEn]. Retried once
     * ONLY when a response arrived but did not parse (a re-roll is cheap
     * and usually parses); a null result means the transport/model layer
     * already exhausted its chances and recorded the cause in lastError,
     * which the returned [BlockOutcome.note] snapshots. [onReady] fires
     * once, with the block's sane-question count.
     */
    private suspend fun fetchExamBlock(
        lang: String,
        topicNamesEn: List<String>,
        count: Int,
        onReady: (Int) -> Unit
    ): BlockOutcome {
        var lastNote = "no attempt ran"
        repeat(2) { attempt ->
            val result = HybridAiClient.generateExamBlock(examBlockPrompt(lang, topicNamesEn, count))
            if (result == null) {
                val err = HybridAiClient.lastError.value
                    ?: "no error detail recorded (a parallel success may have cleared it)"
                return BlockOutcome(null, null, "cloud returned nothing — $err")
            }
            val parsed = parseExam(result.text, minQuestions = EXAM_BLOCK_MIN)
            if (parsed != null) {
                onReady(parsed.size)
                return BlockOutcome(parsed, result.source, "ok (${parsed.size} questions)")
            }
            val sane = parseExam(result.text, minQuestions = 1)?.size ?: 0
            lastNote = "response arrived (${result.text.length} chars) but only $sane sane " +
                "questions parsed (block minimum $EXAM_BLOCK_MIN)"
            HybridAiClient.reportFailure(
                "Exam block parse",
                "attempt ${attempt + 1}: ${result.text.length} chars did not parse as an exam block"
            )
        }
        return BlockOutcome(null, null, lastNote)
    }

    /**
     * Parse the exam JSON of [examPrompt]. Tolerant by design.
     *
     * The previous version was all-or-nothing: one malformed item (answer
     * as a letter, a missing explanation) or a truncated tail threw and
     * discarded ALL ten questions. Now each question is validated on its
     * own — bad items are skipped, not fatal — the answer is accepted as
     * an index (0–3), a numeric string or a letter (A–D), the explanation
     * is optional, and if the whole-document parse fails (the classic
     * case: output cut off mid-JSON), the complete question objects are
     * salvaged one by one. Null unless at least [minQuestions] sane
     * questions survive (5 for a whole exam, [EXAM_BLOCK_MIN] for a block).
     */
    fun parseExam(raw: String, minQuestions: Int = 5): List<QuizQuestion>? {
        val fromDocument = try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) {
                null
            } else {
                val root = JSONObject(raw.substring(start, end + 1))
                val arr = root.optJSONArray("questions")
                if (arr == null) null else collectQuestions(arr)
            }
        } catch (e: Exception) {
            null
        }
        if (fromDocument != null && fromDocument.size >= minQuestions) return fromDocument
        val salvaged = salvageQuestions(raw)
        return if (salvaged.size >= minQuestions) salvaged else null
    }

    private fun collectQuestions(arr: JSONArray): List<QuizQuestion> {
        val out = mutableListOf<QuizQuestion>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseQuestionObject(item)?.let { out.add(it) }
        }
        return out
    }

    /** One exam question, validated on its own. Null when it is not sane. */
    private fun parseQuestionObject(o: JSONObject): QuizQuestion? {
        val question = o.optString("question").trim()
        if (question.isEmpty()) return null
        val optsJson = o.optJSONArray("options") ?: return null
        if (optsJson.length() != 4) return null
        val opts = List(4) { optsJson.optString(it).trim() }
        if (opts.any { it.isEmpty() }) return null
        val answer = coerceAnswer(o.opt("answer")) ?: return null
        val explanation = o.optString("explanation").trim()
        val topicName = o.optString("topic")
        val topicId = Topics.all.firstOrNull {
            it.nameEs.equals(topicName, true) || it.nameEn.equals(topicName, true)
        }?.id ?: "exam"
        return QuizQuestion(
            topicId = topicId,
            questionEs = question,
            questionEn = question,
            optionsEs = opts,
            optionsEn = opts,
            answerIndex = answer,
            explanationEs = explanation,
            explanationEn = explanation
        )
    }

    /** Accept the answer as an index (2), a numeric string ("2") or a letter ("C"). */
    private fun coerceAnswer(value: Any?): Int? = when (value) {
        is Number -> value.toInt().takeIf { it in 0..3 }
        is String -> {
            val s = value.trim()
            s.toIntOrNull()?.takeIf { it in 0..3 }
                ?: s.uppercase().firstOrNull { it in 'A'..'D' }?.let { it - 'A' }
        }
        else -> null
    }

    /**
     * Salvage complete question objects from a truncated or fence-wrapped
     * response: scan for balanced {...} spans (string- and escape-aware);
     * a span holding the questions array is unpacked, a span holding one
     * question is validated, and a span that never closes (the truncated
     * root, or the cut-off last question) is stepped INTO, so the complete
     * questions inside it are still found.
     */
    private fun salvageQuestions(raw: String): List<QuizQuestion> {
        val out = mutableListOf<QuizQuestion>()
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '{') {
                i++
                continue
            }
            var depth = 0
            var inString = false
            var escaped = false
            var j = i
            var closed = false
            while (j < raw.length) {
                val c = raw[j]
                if (inString) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                closed = true
                                break
                            }
                        }
                    }
                }
                j++
            }
            if (!closed) {
                // Truncated span (root object or last question): step
                // inside it and keep looking for complete questions.
                i++
                continue
            }
            val obj = try {
                JSONObject(raw.substring(i, j + 1))
            } catch (e: Exception) {
                null
            }
            when {
                obj == null -> i++
                obj.has("questions") -> {
                    obj.optJSONArray("questions")?.let { out.addAll(collectQuestions(it)) }
                    i = j + 1
                }
                obj.has("question") -> {
                    parseQuestionObject(obj)?.let { out.add(it) }
                    i = j + 1
                }
                else -> i++
            }
        }
        return out
    }

    /** Local analysis used when the cloud model is unreachable (or seed mode). */
    fun localAnalysis(lang: String, perTopic: Map<String, Pair<Int, Int>>, totalCorrect: Int, total: Int): String {
        val weakest = perTopic.entries
            .filter { it.value.second > 0 }
            .minByOrNull { it.value.first.toDouble() / it.value.second }
        return if (lang == "en") {
            buildString {
                append("You answered $totalCorrect of $total questions correctly. ")
                if (weakest != null && weakest.value.first < weakest.value.second) {
                    append("Your weakest area this time was ${weakest.key} (${weakest.value.first}/${weakest.value.second}). ")
                    append("Review its flashcards first, then retake a mini-quiz on that topic before the next full exam.")
                } else {
                    append("Solid work across every topic. Keep the pace with short daily drills and raise the difficulty on your next exam.")
                }
            }
        } else {
            buildString {
                append("Respondiste bien $totalCorrect de $total preguntas. ")
                if (weakest != null && weakest.value.first < weakest.value.second) {
                    append("Tu área más floja esta vez fue ${weakest.key} (${weakest.value.first}/${weakest.value.second}). ")
                    append("Repasa primero sus tarjetas y luego haz un mini-quiz de ese tema antes del próximo examen completo.")
                } else {
                    append("Trabajo sólido en todos los temas. Mantén el ritmo con prácticas cortas diarias y sube la dificultad en el próximo examen.")
                }
            }
        }
    }
}
