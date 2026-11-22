package com.cyberdrill.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyberdrill.app.ai.AiResult
import com.cyberdrill.app.ai.AiSource
import com.cyberdrill.app.ai.HybridAiClient
import com.cyberdrill.app.ai.StudyAi
import com.cyberdrill.app.data.QuizQuestion
import com.cyberdrill.app.data.SeedBank
import com.cyberdrill.app.data.Topic
import kotlinx.coroutines.launch

@Composable
fun TopicScreen(
    l: L,
    topic: Topic,
    onFlashcards: () -> Unit,
    onMiniQuiz: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← ${l.backHome}") }
        Text(
            text = topic.name(l.lang),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(text = topic.desc(l.lang), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(text = l.chooseMode, style = MaterialTheme.typography.titleMedium)
        Button(onClick = onFlashcards, modifier = Modifier.fillMaxWidth()) { Text(l.flashcards) }
        Text(text = l.flashcardsDesc, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onMiniQuiz, modifier = Modifier.fillMaxWidth()) { Text(l.miniQuiz) }
        Text(text = l.miniQuizDesc, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Flashcards: terms come from the seed bank (instant, offline). The
 * definition starts as the bank's own; one tap asks the HYBRID model for a
 * fresh definition and the badge shows where it really came from.
 */
@Composable
fun FlashcardsScreen(
    l: L,
    topic: Topic,
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val cards = remember(topic.id) { SeedBank.cardsFor(topic.id).shuffled() }
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var aiDef by remember { mutableStateOf<AiResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var seen by remember { mutableIntStateOf(0) }
    var recorded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val card = cards[index % cards.size]

    fun next() {
        index++
        seen++
        revealed = false
        aiDef = null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = {
            if (!recorded && seen > 0) {
                viewModel.recordSession("flashcards", topic.id, 0, seen)
                recorded = true
            }
            onBack()
        }) { Text("← ${l.backHome}") }

        Text(
            text = "${l.cardOf} ${index % cards.size + 1} / ${cards.size} · ${topic.name(l.lang)}",
            style = MaterialTheme.typography.labelLarge
        )

        Card(
            modifier = Modifier.fillMaxWidth().clickable { revealed = true },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = card.term(l.lang),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                if (revealed) {
                    val shown = aiDef
                    Text(
                        text = shown?.text ?: card.definition(l.lang),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(10.dp))
                    InferenceBadge(source = shown?.source ?: AiSource.SEED_BANK, l = l)
                } else {
                    Text(text = l.tapToReveal, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (revealed) {
            OutlinedButton(
                onClick = {
                    loading = true
                    scope.launch {
                        val result = HybridAiClient.generateHybrid(
                            StudyAi.definitionPrompt(card.termEn, l.lang)
                        )
                        if (result != null) aiDef = result
                        loading = false
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    Text("  ${l.generating}")
                } else {
                    Text(l.aiDefinition)
                }
            }
        }

        Button(onClick = { next() }, modifier = Modifier.fillMaxWidth()) { Text(l.nextCard) }
    }

    LaunchedEffect(Unit) { /* session is recorded when leaving, above */ }
}

/**
 * Mini-quiz: 5 seed questions per topic for instant offline play, with an
 * optional HYBRID-generated fresh question per round and the seed
 * explanation as the instant verdict. AI answers carry their source badge.
 * "New question with AI" goes through StudyAi.freshQuestion, which
 * guarantees a visibly different question (varied AI prompt, one stronger
 * retry, then a differing seed question) — the tap never looks dead.
 */
@Composable
fun MiniQuizScreen(
    l: L,
    topic: Topic,
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val seedSet = remember(topic.id) { SeedBank.questionsFor(topic.id).shuffled().take(5) }
    var round by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<QuizQuestion?>(null) }
    var currentSource by remember { mutableStateOf(AiSource.SEED_BANK) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var checked by remember { mutableStateOf(false) }
    var score by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var recorded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val question = current ?: seedSet.getOrNull(round)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← ${l.backHome}") }
        Text(
            text = "${l.miniQuiz} · ${topic.name(l.lang)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (finished) {
            Text(text = "${l.yourScore}: $score / ${seedSet.size}", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(l.backHome) }
            return@Column
        }

        if (question == null) {
            Text(text = l.noHistory)
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "${l.questionOf} ${round + 1} / ${seedSet.size}", style = MaterialTheme.typography.labelLarge)
            InferenceBadge(source = currentSource, l = l)
        }

        Text(text = question.question(l.lang), style = MaterialTheme.typography.bodyLarge)

        question.options(l.lang).forEachIndexed { i, opt ->
            val isAnswer = i == question.answerIndex
            val isSelected = selected == i
            val bg = when {
                checked && isAnswer -> Color(0xFFD9EDDF)
                checked && isSelected && !isAnswer -> Color(0xFFF6DADA)
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Card(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !checked) { selected = i },
                colors = CardDefaults.cardColors(containerColor = bg)
            ) {
                Text(
                    text = "${"ABCD"[i]})  $opt",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        if (checked) {
            val wasRight = selected == question.answerIndex
            Text(
                text = if (wasRight) l.correct else l.incorrect,
                fontWeight = FontWeight.Bold,
                color = if (wasRight) Color(0xFF1B7A43) else Color(0xFFB3261E)
            )
            Text(text = question.explanation(l.lang), style = MaterialTheme.typography.bodyMedium)
        }

        if (!checked) {
            Button(
                onClick = {
                    checked = true
                    if (selected == question.answerIndex) score++
                },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(l.checkAnswer) }

            OutlinedButton(
                onClick = {
                    generating = true
                    scope.launch {
                        // freshQuestion never returns the on-screen
                        // question: varied AI attempts first, then a
                        // differing seed question. The tap always lands.
                        val (newQuestion, source) = StudyAi.freshQuestion(
                            topicId = topic.id,
                            topicNameEn = topic.nameEn,
                            lang = l.lang,
                            currentText = question.question(l.lang)
                        )
                        current = newQuestion
                        currentSource = source
                        selected = null
                        generating = false
                    }
                },
                enabled = !generating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (generating) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    Text("  ${l.generating}")
                } else {
                    Text(l.newAiQuestion)
                }
            }
        } else {
            Button(
                onClick = {
                    if (round + 1 >= seedSet.size) {
                        finished = true
                        if (!recorded) {
                            viewModel.recordSession("miniquiz", topic.id, score, seedSet.size)
                            recorded = true
                        }
                    } else {
                        round++
                        current = null
                        currentSource = AiSource.SEED_BANK
                        selected = null
                        checked = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (round + 1 >= seedSet.size) l.seeResults else l.nextQuestion) }
        }
    }
}
