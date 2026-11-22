package com.cyberdrill.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "cyberdrill_prefs")

/**
 * Local persistence (DataStore): the UI language and the session history.
 * Nothing leaves the device — progress is study data, and the app has no
 * account system by design.
 */
class ProgressStore(private val context: Context) {

    private val keyLang = stringPreferencesKey("language")
    private val keySessions = stringPreferencesKey("sessions_json")

    /** "es" (default) or "en". Spanish is the app's official default. */
    val language: Flow<String> = context.dataStore.data.map { it[keyLang] ?: "es" }

    val sessions: Flow<List<SessionRecord>> = context.dataStore.data.map { prefs ->
        decodeSessions(prefs[keySessions])
    }

    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { it[keyLang] = lang }
    }

    suspend fun addSessions(newRecords: List<SessionRecord>) {
        if (newRecords.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = decodeSessions(prefs[keySessions]).toMutableList()
            current.addAll(newRecords)
            // Keep the store small: last 300 records are plenty for mastery.
            prefs[keySessions] = encodeSessions(current.takeLast(300))
        }
    }

    private fun encodeSessions(list: List<SessionRecord>): String {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("mode", r.mode)
                    .put("topic", r.topicId)
                    .put("correct", r.correct)
                    .put("total", r.total)
                    .put("ts", r.timestamp)
            )
        }
        return arr.toString()
    }

    private fun decodeSessions(json: String?): List<SessionRecord> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        SessionRecord(
                            mode = o.optString("mode"),
                            topicId = o.optString("topic"),
                            correct = o.optInt("correct"),
                            total = o.optInt("total"),
                            timestamp = o.optLong("ts")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
