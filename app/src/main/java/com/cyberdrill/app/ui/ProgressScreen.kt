package com.cyberdrill.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyberdrill.app.data.Topics
import java.text.DateFormat
import java.util.Date

/** Progress: totals, per-topic mastery and recent session history. All local. */
@Composable
fun ProgressScreen(
    l: L,
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val scored = sessions.filter { it.mode == "miniquiz" || it.mode == "exam" }
    val totalAnswered = scored.sumOf { it.total }
    val totalCorrect = scored.sumOf { it.correct }
    val accuracy = if (totalAnswered > 0) totalCorrect * 100 / totalAnswered else 0
    val examCount = sessions.filter { it.mode == "exam" }
        .map { it.timestamp }.distinct().size
    val sessionCount = sessions.filter { it.mode == "miniquiz" }.size + examCount +
        sessions.filter { it.mode == "flashcards" }.size

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text("← ${l.backHome}") }
            Text(
                text = l.progress,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(l.sessions, sessionCount.toString(), Modifier.weight(1f))
                StatCard(l.answered, totalAnswered.toString(), Modifier.weight(1f))
                StatCard(l.accuracy, "$accuracy%", Modifier.weight(1f))
            }
        }

        item {
            Text(
                text = l.topicsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        items(Topics.all) { topic ->
            val mastery = AppViewModel.mastery(sessions, topic.id)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = topic.name(l.lang), style = MaterialTheme.typography.bodyLarge)
                    Text(text = "${(mastery * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { mastery }, modifier = Modifier.fillMaxWidth())
            }
        }

        item {
            Text(
                text = l.history,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (sessions.isEmpty()) {
            item { Text(text = l.noHistory, style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(sessions.sortedByDescending { it.timestamp }.take(20)) { s ->
                val modeLabel = when (s.mode) {
                    "miniquiz" -> l.modeMiniQuiz
                    "exam" -> l.modeExam
                    else -> l.modeFlashcards
                }
                val topicLabel = Topics.all.firstOrNull { it.id == s.topicId }?.name(l.lang) ?: ""
                val detail = if (s.mode == "flashcards") {
                    "${s.total} ${l.cardsSeen}"
                } else {
                    "${s.correct}/${s.total}"
                }
                val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(s.timestamp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "$modeLabel · $topicLabel · $detail",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = date, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
    }
}
