package com.cyberdrill.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cyberdrill.app.data.ProgressStore
import com.cyberdrill.app.data.SessionRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProgressStore(app)

    /** UI language: "es" by default, switchable to "en"; persisted. */
    val language: StateFlow<String> =
        store.language.stateIn(viewModelScope, SharingStarted.Eagerly, "es")

    val sessions: StateFlow<List<SessionRecord>> =
        store.sessions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setLanguage(lang: String) {
        viewModelScope.launch { store.setLanguage(lang) }
    }

    fun recordSession(mode: String, topicId: String, correct: Int, total: Int) {
        viewModelScope.launch {
            store.addSessions(
                listOf(SessionRecord(mode, topicId, correct, total, System.currentTimeMillis()))
            )
        }
    }

    fun recordSessions(records: List<SessionRecord>) {
        viewModelScope.launch { store.addSessions(records) }
    }

    companion object {
        /**
         * Mastery of a topic = correct / answered across scored sessions
         * (mini-quiz and exam). Flashcards sessions carry knowledge practice
         * but no score, so they never distort this number.
         */
        fun mastery(sessions: List<SessionRecord>, topicId: String): Float {
            val scored = sessions.filter {
                it.topicId == topicId && (it.mode == "miniquiz" || it.mode == "exam") && it.total > 0
            }
            val total = scored.sumOf { it.total }
            if (total == 0) return 0f
            return scored.sumOf { it.correct }.toFloat() / total.toFloat()
        }
    }
}
