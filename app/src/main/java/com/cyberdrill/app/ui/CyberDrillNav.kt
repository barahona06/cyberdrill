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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cyberdrill.app.BuildConfig
import com.cyberdrill.app.ai.HybridAiClient
import com.cyberdrill.app.data.Topics

@Composable
fun CyberDrillNav(viewModel: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val lang by viewModel.language.collectAsState()
    val l = L(lang)

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                l = l,
                viewModel = viewModel,
                onTopic = { nav.navigate("topic/$it") },
                onExam = { nav.navigate("exam") },
                onProgress = { nav.navigate("progress") }
            )
        }
        composable(
            "topic/{topicId}",
            arguments = listOf(navArgument("topicId") { type = NavType.StringType })
        ) { entry ->
            val topic = Topics.byId(entry.arguments?.getString("topicId") ?: "")
            TopicScreen(
                l = l,
                topic = topic,
                onFlashcards = { nav.navigate("flashcards/${topic.id}") },
                onMiniQuiz = { nav.navigate("miniquiz/${topic.id}") },
                onBack = { nav.popBackStack() }
            )
        }
        composable(
            "flashcards/{topicId}",
            arguments = listOf(navArgument("topicId") { type = NavType.StringType })
        ) { entry ->
            FlashcardsScreen(
                l = l,
                topic = Topics.byId(entry.arguments?.getString("topicId") ?: ""),
                viewModel = viewModel,
                onBack = { nav.popBackStack() }
            )
        }
        composable(
            "miniquiz/{topicId}",
            arguments = listOf(navArgument("topicId") { type = NavType.StringType })
        ) { entry ->
            MiniQuizScreen(
                l = l,
                topic = Topics.byId(entry.arguments?.getString("topicId") ?: ""),
                viewModel = viewModel,
                onBack = { nav.popBackStack() }
            )
        }
        composable("exam") {
            ExamScreen(
                l = l,
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack("home", inclusive = false) }
            )
        }
        composable("progress") {
            ProgressScreen(
                l = l,
                viewModel = viewModel,
                onBack = { nav.popBackStack() }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    l: L,
    viewModel: AppViewModel,
    onTopic: (String) -> Unit,
    onExam: () -> Unit,
    onProgress: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val aiConfigured by HybridAiClient.configured.collectAsState()
    val deviceStatus by HybridAiClient.onDeviceStatus.collectAsState()
    val aiError by HybridAiClient.lastError.collectAsState()
    val appCheckDebugToken by HybridAiClient.debugToken.collectAsState()

    // On-device model management: check, download if needed, warm up.
    // Runs once, after the first composition, inside this coroutine —
    // HybridAiClient self-initializes lazily inside prepareOnDevice(), so
    // no AI work happens on the app startup path (eager model construction
    // in Application.onCreate crashed the app on a real device). When the
    // lazy init completes, `aiConfigured` flips and the status card below
    // updates from the flows as before.
    LaunchedEffect(Unit) {
        HybridAiClient.prepareOnDevice()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "CyberDrill",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = l.tagline, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = l.lang == "es",
                        onClick = { viewModel.setLanguage("es") },
                        label = { Text("ES") }
                    )
                    FilterChip(
                        selected = l.lang == "en",
                        onClick = { viewModel.setLanguage("en") },
                        label = { Text("EN") }
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = l.aiStatusTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    val statusText = when {
                        !aiConfigured -> l.seedMode
                        deviceStatus == "available" -> l.onDeviceAvailable
                        deviceStatus == "downloading" -> l.onDeviceDownloading
                        deviceStatus == "unavailable" -> l.onDeviceUnavailable
                        else -> l.onDeviceUnknown
                    }
                    Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
                    // Visible failure line: until now AI errors went only to
                    // Logcat, so a rejected App Check token or a stuck
                    // on-device call looked like "the button does nothing".
                    if (aiError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${l.aiError}: $aiError",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // Debug builds: the App Check debug token, so it can be
                    // registered in the Firebase console without ADB/Logcat.
                    if (BuildConfig.DEBUG && appCheckDebugToken != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "App Check debug token: $appCheckDebugToken",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Button(onClick = onExam, modifier = Modifier.fillMaxWidth()) {
                Text(l.fullExam)
            }
            Text(
                text = l.fullExamDesc,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onProgress, modifier = Modifier.fillMaxWidth()) {
                Text(l.progress)
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
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onTopic(topic.id) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = topic.monogram,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            text = topic.name(l.lang),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = topic.desc(l.lang), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { mastery }, modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "${(mastery * 100).toInt()}% ${l.mastery}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
