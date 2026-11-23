package com.cyberdrill.app.ai

import android.content.Context
import android.util.Log
import com.cyberdrill.app.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceExtension
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Where an AI answer actually came from. Shown in the UI on every AI answer. */
enum class AiSource { ON_DEVICE, IN_CLOUD, SEED_BANK }

/** One AI answer plus the inference source reported by Firebase AI Logic. */
data class AiResult(val text: String, val source: AiSource)

/**
 * CyberDrill's single entry point to Firebase AI Logic.
 *
 * Hybrid design (the heart of the app):
 *  - SHORT, structured tasks (a definition, one quiz question, a one-line
 *    verdict) go through [hybridModel], built with
 *    `OnDeviceConfig(InferenceMode.PREFER_ON_DEVICE)`: Gemini Nano answers on
 *    the device when AICore has it available — offline, instant, private —
 *    and the very same call falls back to the cloud model when it does not.
 *  - LONG, rich generation (a full 10-question exam with explanations and the
 *    closing progress analysis, in Spanish) goes through [cloudModel] only:
 *    on-device inference is single-turn, short-output and only validated by
 *    Google for English/Korean, so rich Spanish belongs in the cloud.
 *
 * Every response carries `response.inferenceSource` (ON_DEVICE / IN_CLOUD),
 * which the UI shows as a badge — the routing is visible, never hidden.
 *
 * Seed mode: if the app ships with the placeholder google-services.json (no
 * real Firebase project yet), cloud init detects the PLACEHOLDER api key and
 * the client stays unconfigured; every generate* call returns null and the
 * UI falls back to the built-in seed bank. See README.md.
 *
 * LAZY, DECOUPLED INIT (startup-crash fix + silent-hang fix): nothing is
 * built during Application.onCreate or synchronously during composition —
 * building the hybrid model eagerly at startup killed the process on a real
 * Galaxy Z Fold7 (an Error from the on-device ML Kit GenAI stack escaped a
 * catch that only caught Exception). The CLOUD and HYBRID routes now have
 * separate locks, separate attempted/ready flags and separate failure
 * domains:
 *  - Cloud init is cheap object construction; it alone decides [configured]
 *    and is the only init the generate path ever waits for.
 *  - Hybrid init and all on-device model management ([prepareOnDevice]:
 *    checkStatus / download / warmUp) run in a background scope, each step
 *    bounded by a timeout. No lock used by the generate path is ever held
 *    across an on-device call, so a hung AICore call can only cost the
 *    on-device route — never the cloud route, never the UI.
 *  - [generateHybrid] never waits for hybrid init: it uses the hybrid model
 *    only when [hybridReady] is already true, with a timeout, and falls
 *    through to the cloud model (also with a timeout) on any failure.
 *
 * VISIBLE FAILURES: every catch records [lastError] (exception class +
 * message) and the home screen shows it; the next success clears it. Until
 * now every failure went only to Logcat, so a rejected App Check token or a
 * stuck on-device call looked exactly like "the button does nothing".
 *
 * QUESTION VARIATION: single-question generation asks for variety on
 * purpose — the prompt excludes the question already on screen and adds a
 * random rotating focus (see StudyAi), and the cloud leg of a question
 * call ([generateHybrid] with creative = true) runs on a second cloud
 * model built with temperature [QUESTION_TEMPERATURE]. Gemini Nano is
 * near-deterministic: the identical prompt earned the identical question,
 * which again looked like a dead button.
 *
 * EXAM ROUTE: the full exam used to be ONE ~2,000-output-token call with a
 * 90 s cap and a full retry — on the owner's phone it eventually arrived,
 * but the wait ran to a minute or more (and a timed-out first attempt
 * doubled it). It is now generated as TWO 5-question blocks IN PARALLEL
 * through the tuned exam model (see [cloudExamModel] and
 * [generateExamBlock]): JSON mode (no markdown fences or preamble to pay
 * tokens for) and a per-block output ceiling. Each block carries its own
 * [EXAM_BLOCK_TIMEOUT_MS] cap; a block that arrives but does not parse is
 * reported through [reportFailure] and retried on its own — the whole exam
 * is never regenerated for one bad half. [LONG_GENERATE_TIMEOUT_MS] remains
 * as the fallback budget when the tuned exam model cannot answer and the
 * default cloud model takes the block over.
 *
 * EXAM FAILURE IS VISIBLE, NEVER SILENT (owner's Fold7, §15 build): that
 * build's exam came back with the "Local bank" badge and nobody could say
 * why — the tuned config (JSON mode + a 2,048-token ceiling + thinking
 * MINIMAL) had only ever been verified against the client AAR, never
 * against a live server answer, and the exam screen swapped in the seed
 * bank without showing anything. Three hardenings followed: (1) the
 * thinking config is GONE from the exam model and the ceiling is doubled —
 * thinking tokens draw from the same output budget, so a truncated JSON
 * tail was a prime suspect for blocks that "arrived" with too few sane
 * questions to serve; (2) every generation error is recorded under its
 * ATTEMPT label ("Exam block", "Exam block (default model)", …) instead
 * of one generic "Generate", and a tuned-model failure latches
 * ([examModelFailed]) so later blocks go straight to the default model;
 * (3) StudyAi records the per-block outcome in [examFailureDetail] and
 * the exam screen shows it verbatim with a retry — the seed bank is now
 * an explicit choice on that error screen, never a silent substitute.
 */
@OptIn(PublicPreviewAPI::class)
object HybridAiClient {

    private const val TAG = "HybridAiClient"
    private const val CLOUD_MODEL = "gemini-3.8-flash"
    private const val PLACEHOLDER_MARKER = "PLACEHOLDER"

    /** Temperature of the cloud model instance used ONLY for single-question generation. */
    private const val QUESTION_TEMPERATURE = 0.9f

    /** Hard cap on one generateContent call (hybrid attempt or cloud). */
    private const val GENERATE_TIMEOUT_MS = 25_000L

    /**
     * Cap for a LONG cloud generation on the DEFAULT cloud model.
     * Originally the cap for the whole 10-question exam (~2,000 output
     * tokens in Spanish — it never fit in [GENERATE_TIMEOUT_MS], which is
     * why the exam used to time out and fall back to the seed bank). The
     * exam now runs in parallel blocks on the tuned exam model; this cap
     * survives as the budget of the default-model fallback inside
     * [generateExamBlock].
     */
    const val LONG_GENERATE_TIMEOUT_MS = 90_000L

    /**
     * Cap for ONE exam block (5 questions, ~1,000 output tokens) on the
     * tuned exam model. Two blocks run in parallel, so the wall-clock wait
     * is roughly the SLOWEST block instead of one monolith's full length —
     * and a block that fails is retried alone, never the whole exam.
     */
    const val EXAM_BLOCK_TIMEOUT_MS = 45_000L

    /**
     * Output-token ceiling for one exam block. Five questions with four
     * short options and a one-sentence explanation run ~1,000–1,300
     * tokens; the ceiling leaves generous headroom (it was 2,048 in the
     * §15 build — with the model's thinking tokens drawing from the same
     * budget, a block's JSON tail could be cut mid-question, and the
     * salvaged remainder fell under the per-block minimum).
     */
    private const val EXAM_BLOCK_MAX_OUTPUT_TOKENS = 4096

    /** Cap on one on-device management step (checkStatus / warmUp). */
    private const val STEP_TIMEOUT_MS = 20_000L

    /** Cap on the on-device model download (it is a real download). */
    private const val DOWNLOAD_TIMEOUT_MS = 180_000L

    // ---- Cloud route state (its own lock; never held across AICore calls).
    private val cloudLock = Any()
    @Volatile private var cloudAttempted: Boolean = false
    @Volatile private var cloudReady: Boolean = false
    @Volatile private var cloudModel: GenerativeModel? = null
    @Volatile private var cloudQuestionModel: GenerativeModel? = null
    @Volatile private var cloudExamModel: GenerativeModel? = null
    @Volatile private var backend: FirebaseAI? = null

    /**
     * Latched the first time the tuned exam model fails to answer: from
     * then on, exam blocks go straight to the default cloud model instead
     * of paying the same rejection once per block.
     */
    @Volatile private var examModelFailed: Boolean = false

    // ---- Hybrid route state (separate lock, separate failure domain).
    private val hybridLock = Any()
    @Volatile private var hybridAttempted: Boolean = false
    @Volatile private var hybridReady: Boolean = false
    @Volatile private var hybridModel: GenerativeModel? = null

    /** True when the placeholder google-services.json was detected. */
    @Volatile private var seedMode: Boolean = false

    /** Background lane for hybrid init and on-device model management. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prepStarted = AtomicBoolean(false)

    private val _configured = MutableStateFlow(false)
    /** True once a real (non-placeholder) Firebase project is connected. */
    val configured: StateFlow<Boolean> = _configured

    private val _onDeviceStatus = MutableStateFlow("unknown")
    /** unknown | seed | available | downloadable | downloading | unavailable */
    val onDeviceStatus: StateFlow<String> = _onDeviceStatus

    private val _lastError = MutableStateFlow<String?>(null)
    /**
     * Last AI failure, human-readable ("<where>: <ExceptionClass>: <msg>"),
     * shown on the home screen. Null when the last AI operation succeeded.
     */
    val lastError: StateFlow<String?> = _lastError

    private val _examFailureDetail = MutableStateFlow<String?>(null)
    /**
     * Why the last full-exam fetch failed, block by block (transport error
     * verbatim, or response size vs. sane-question count), written by
     * StudyAi.fetchExam and shown VERBATIM on the exam error screen. This
     * is the diagnostic that names the exact cause — a server rejection
     * (with its exception class and message), a timeout with its budget,
     * or a response that arrived but did not parse — instead of the old
     * silent swap to the seed bank.
     */
    val examFailureDetail: StateFlow<String?> = _examFailureDetail

    private val _debugToken = MutableStateFlow<String?>(null)
    /**
     * App Check DEBUG token persisted by the debug provider in this app's
     * own SharedPreferences (debug builds only). Shown on the home screen so
     * it can be registered in Firebase console > App Check > Manage debug
     * tokens without needing Logcat/ADB.
     */
    val debugToken: StateFlow<String?> = _debugToken

    /** Application context, used only to read the App Check debug prefs. */
    @Volatile private var appContext: Context? = null

    val isConfigured: Boolean get() = _configured.value

    /**
     * Called from CyberDrillApplication.onCreate. Stores the application
     * context (for the debug-token read) and takes a first look for an
     * already-persisted App Check debug token. Does NO Firebase AI work:
     * model construction stays lazy (see class doc).
     */
    fun attachContext(context: Context) {
        appContext = context.applicationContext
        refreshDebugToken()
    }

    private fun recordError(where: String, t: Throwable) {
        val msg = "$where: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        _lastError.value = msg.take(300)
        Log.w(TAG, msg, t)
    }

    private fun recordTimeout(where: String, timeoutMs: Long = GENERATE_TIMEOUT_MS) {
        val msg = "$where: Timeout: no response within ${timeoutMs / 1000}s"
        _lastError.value = msg.take(300)
        Log.w(TAG, msg)
    }

    /**
     * Records a failure detected OUTSIDE the client — e.g. StudyAi got a
     * response the transport delivered fine but could not parse it — so it
     * is visible on the home screen via [lastError], exactly like a
     * transport error. Parse failures used to be invisible: generate() had
     * already cleared lastError on success, so the exam fell back to the
     * seed bank with no trace anywhere.
     */
    fun reportFailure(where: String, detail: String) {
        val msg = "$where: $detail"
        _lastError.value = msg.take(300)
        Log.w(TAG, msg)
    }

    /** Records why a full-exam fetch failed; shown on the exam error screen. */
    fun recordExamFailure(detail: String) {
        _examFailureDetail.value = detail.take(700)
        Log.w(TAG, "Exam fetch failed: $detail")
    }

    /** Clears the exam failure record after a successful exam fetch. */
    fun clearExamFailure() {
        _examFailureDetail.value = null
    }

    // ------------------------------------------------------------------
    // Cloud init — the only init the generate path ever waits for.
    // ------------------------------------------------------------------

    private fun ensureCloudInitialized() {
        if (cloudAttempted) return
        synchronized(cloudLock) {
            if (cloudAttempted) return
            try {
                doCloudInit()
            } finally {
                cloudAttempted = true
            }
        }
    }

    private fun doCloudInit() {
        val app = try {
            FirebaseApp.getInstance()
        } catch (t: Throwable) {
            Log.i(TAG, "FirebaseApp not initialized -> seed mode", t)
            null
        }
        val apiKey = app?.options?.apiKey.orEmpty()
        if (app == null || apiKey.isBlank() || apiKey.contains(PLACEHOLDER_MARKER)) {
            // SEED MODE: placeholder google-services.json. The app is fully
            // functional from its built-in bank; no network AI is attempted.
            // This is a designed mode, not an error: lastError stays null.
            seedMode = true
            _configured.value = false
            _onDeviceStatus.value = "seed"
            Log.i(TAG, "Placeholder Firebase config detected -> seed mode")
            return
        }
        val ai = try {
            Firebase.ai(backend = GenerativeBackend.googleAI())
        } catch (t: Throwable) {
            recordError("Cloud init", t)
            _configured.value = false
            return
        }
        backend = ai
        // Cloud model FIRST and ALONE decides [configured]: every AI feature
        // can live on the cloud route even if the on-device stack is broken.
        try {
            cloudModel = ai.generativeModel(modelName = CLOUD_MODEL)
            cloudReady = true
            _configured.value = true
            _lastError.value = null
            Log.i(TAG, "Firebase AI Logic configured (cloud model)")
        } catch (t: Throwable) {
            recordError("Cloud init", t)
            cloudReady = false
            _configured.value = false
        }
        if (cloudReady) {
            // Second cloud instance used ONLY for single-question
            // generation: temperature 0.9 so repeated taps do not converge
            // on the same question. The model above keeps the default
            // config its strict exam-JSON contract prefers. A failure here
            // is non-fatal: question generation then uses the default
            // cloud model (see generateHybrid).
            cloudQuestionModel = try {
                ai.generativeModel(
                    modelName = CLOUD_MODEL,
                    generationConfig = generationConfig { temperature = QUESTION_TEMPERATURE }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Question model unavailable; using default cloud model", t)
                null
            }
            // Third cloud instance, used ONLY for exam blocks — tuned so
            // the exam stops being the slowest screen in the app:
            //  - JSON mode: the answer IS a JSON document; without it the
            //    model spends output tokens on fences/preamble and the
            //    parser has to dig the document out.
            //  - An output ceiling sized for one block (see
            //    EXAM_BLOCK_MAX_OUTPUT_TOKENS).
            // The §15 build ALSO set thinking MINIMAL here; that element
            // was removed after the owner's Fold7 test (§16): it was the
            // least-proven part of the config (verified against the client
            // AAR, never against a live server answer) and thinking tokens
            // draw from the same output ceiling, truncating the JSON tail.
            // A construction failure is non-fatal: generateExamBlock then
            // runs its blocks on the default cloud model.
            cloudExamModel = try {
                ai.generativeModel(
                    modelName = CLOUD_MODEL,
                    generationConfig = generationConfig {
                        responseMimeType = "application/json"
                        maxOutputTokens = EXAM_BLOCK_MAX_OUTPUT_TOKENS
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Exam model unavailable; exam blocks use the default cloud model", t)
                null
            }
        }
    }

    // ------------------------------------------------------------------
    // Hybrid init — background only; the generate path never waits for it.
    // ------------------------------------------------------------------

    private fun ensureHybridInitialized() {
        if (hybridAttempted) return
        synchronized(hybridLock) {
            if (hybridAttempted) return
            try {
                doHybridInit()
            } finally {
                hybridAttempted = true
            }
        }
    }

    private fun doHybridInit() {
        val ai = backend ?: return
        // Constructing the hybrid model wires the on-device ML Kit GenAI
        // stack — exactly what killed the process when it ran eagerly at
        // startup. Any Throwable here only disables the hybrid route.
        try {
            hybridModel = ai.generativeModel(
                modelName = CLOUD_MODEL,
                onDeviceConfig = OnDeviceConfig(
                    mode = InferenceMode.PREFER_ON_DEVICE,
                    // On-device answers must stay short (Nano's validated envelope).
                    maxOutputTokens = 256
                )
            )
            hybridReady = true
            Log.i(TAG, "Hybrid model ready (on-device preferred)")
        } catch (t: Throwable) {
            recordError("Hybrid init", t)
            hybridModel = null
            hybridReady = false
        }
    }

    /** Fire-and-forget hybrid init so later calls can use the device route. */
    private fun kickHybridInit() {
        if (hybridAttempted || seedMode) return
        backgroundScope.launch { ensureHybridInitialized() }
    }

    /**
     * On-device model management, triggered once from the home screen.
     * Returns immediately: the whole sequence (cloud check, hybrid init,
     * status check, download, warm-up) runs in the background scope, every
     * step bounded by a timeout and guarded by catch (Throwable). If Gemini
     * Nano is downloadable, it is downloaded here — the SDK never downloads
     * on its own at inference time. No-op in seed mode.
     */
    suspend fun prepareOnDevice() {
        if (!prepStarted.compareAndSet(false, true)) return
        backgroundScope.launch { runOnDevicePrep() }
    }

    private suspend fun runOnDevicePrep() {
        ensureCloudInitialized()
        refreshDebugToken()
        if (seedMode) return
        ensureHybridInitialized()
        if (!hybridReady) return
        val ext = hybridModel?.onDeviceExtension ?: return
        try {
            when (val status = withTimeoutOrNull(STEP_TIMEOUT_MS) { ext.checkStatus() }) {
                OnDeviceModelStatus.AVAILABLE -> {
                    _onDeviceStatus.value = "available"
                    warmUp(ext)
                }
                OnDeviceModelStatus.DOWNLOADABLE -> {
                    _onDeviceStatus.value = "downloading"
                    try {
                        withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                            ext.download().collect { /* progress events; completion ends the flow */ }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        recordError("On-device download", t)
                    }
                    val after = withTimeoutOrNull(STEP_TIMEOUT_MS) { ext.checkStatus() }
                    _onDeviceStatus.value =
                        if (after == OnDeviceModelStatus.AVAILABLE) "available" else "unavailable"
                    if (after == OnDeviceModelStatus.AVAILABLE) warmUp(ext)
                }
                OnDeviceModelStatus.DOWNLOADING -> _onDeviceStatus.value = "downloading"
                null -> {
                    recordTimeout("On-device checkStatus", STEP_TIMEOUT_MS)
                    _onDeviceStatus.value = "unavailable"
                }
                else -> _onDeviceStatus.value = "unavailable"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            recordError("On-device prep", t)
            _onDeviceStatus.value = "unavailable"
        }
    }

    private suspend fun warmUp(ext: OnDeviceExtension) {
        try {
            withTimeoutOrNull(STEP_TIMEOUT_MS) { ext.warmUp() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            recordError("On-device warmUp", t)
        }
    }

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    /**
     * Short structured task, device preferred. Uses the hybrid model ONLY
     * when it is already built ([hybridReady]) — never blocks waiting for
     * hybrid init — with a hard timeout; on any failure it falls through to
     * the cloud model (also timed), and to null (seed bank) when nothing is
     * configured. Runs on Dispatchers.Default so no SDK call can stall the
     * UI thread.
     *
     * [creative] is for single-question generation: the cloud leg then runs
     * on the temperature-0.9 question model instead of the default cloud
     * model, so repeated calls vary. The hybrid (on-device) leg is shared:
     * its variety comes from the prompt itself (exclusion + rotating
     * focus, see StudyAi).
     */
    suspend fun generateHybrid(prompt: String, creative: Boolean = false): AiResult? =
        withContext(Dispatchers.Default) {
            ensureCloudInitialized()
            if (seedMode || !cloudReady) {
                kickHybridInit()
                return@withContext null
            }
            if (hybridReady) {
                val onDeviceFirst = generateWithTimeout(hybridModel, "Hybrid generate", prompt)
                if (onDeviceFirst != null) return@withContext onDeviceFirst
            } else {
                kickHybridInit()
            }
            generateWithTimeout(
                if (creative) cloudQuestionModel ?: cloudModel else cloudModel,
                "Cloud generate",
                prompt
            )
        }

    /**
     * Long rich generation through the cloud model only. [timeoutMs]
     * defaults to the short-task cap; the full exam passes
     * [LONG_GENERATE_TIMEOUT_MS] (see StudyAi.fetchExam).
     */
    suspend fun generateCloud(prompt: String, timeoutMs: Long = GENERATE_TIMEOUT_MS): AiResult? =
        withContext(Dispatchers.Default) {
            ensureCloudInitialized()
            if (seedMode || !cloudReady) return@withContext null
            generateWithTimeout(cloudModel, "Cloud generate", prompt, timeoutMs)
        }

    /**
     * ONE exam block (half the exam) on the tuned exam model
     * ([cloudExamModel]: JSON mode, output ceiling), capped at
     * [EXAM_BLOCK_TIMEOUT_MS]. StudyAi.fetchExam fires two of these IN
     * PARALLEL — GenerativeModel calls are independent requests, so the
     * wait is the slowest block, not the sum.
     *
     * Belt and suspenders: if the tuned model cannot answer (its config
     * rejected, a timeout, any failure), the SAME block is attempted once
     * on the default cloud model with the long budget before giving up —
     * a config problem must degrade to "slower", never to "no AI exam".
     * The first tuned failure also latches [examModelFailed], so the
     * remaining blocks (and the parse retry) skip the tuned model instead
     * of paying the same rejection again. Every failure is recorded under
     * its attempt label, so the exam error screen can name which attempt
     * said what.
     */
    suspend fun generateExamBlock(
        prompt: String,
        timeoutMs: Long = EXAM_BLOCK_TIMEOUT_MS
    ): AiResult? = withContext(Dispatchers.Default) {
        ensureCloudInitialized()
        if (seedMode || !cloudReady) return@withContext null
        val examModel = cloudExamModel
        if (examModel != null && !examModelFailed) {
            val tuned = generateWithTimeout(examModel, "Exam block", prompt, timeoutMs)
            if (tuned != null) return@withContext tuned
            examModelFailed = true
        }
        generateWithTimeout(cloudModel, "Exam block (default model)", prompt, LONG_GENERATE_TIMEOUT_MS)
    }

    private suspend fun generateWithTimeout(
        model: GenerativeModel?,
        route: String,
        prompt: String,
        timeoutMs: Long = GENERATE_TIMEOUT_MS
    ): AiResult? {
        if (model == null) return null
        return try {
            withTimeout(timeoutMs) { generate(model, prompt, route) }
        } catch (e: TimeoutCancellationException) {
            recordTimeout(route, timeoutMs)
            null
        }
    }

    private suspend fun generate(model: GenerativeModel, prompt: String, route: String): AiResult? {
        return try {
            val response = model.generateContent(prompt)
            val text = response.text?.trim().orEmpty()
            if (text.isEmpty()) {
                null
            } else {
                _lastError.value = null
                AiResult(
                    text = text,
                    source = if (response.inferenceSource == InferenceSource.ON_DEVICE) {
                        AiSource.ON_DEVICE
                    } else {
                        AiSource.IN_CLOUD
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Offline, quota (AICore BUSY), safety block, App Check
            // rejection, backend error — or an Error from the on-device
            // stack at inference time: the caller falls back, and the
            // failure is VISIBLE via lastError under the ATTEMPT's label
            // (route), so an exam failure names the exact attempt and the
            // exact exception the server/SDK produced.
            recordError(route, t)
            null
        } finally {
            // The first App Check token request (triggered by the call
            // above) makes the debug provider persist its secret; pick it
            // up so the home screen can show it for console registration.
            refreshDebugToken()
        }
    }

    // ------------------------------------------------------------------
    // App Check debug token (debug builds only)
    // ------------------------------------------------------------------

    /**
     * Reads the App Check debug token from this app's OWN SharedPreferences.
     * Storage location established from the firebase-appcheck-debug AAR
     * (StorageHelper / DebugAppCheckProvider bytecode, 19.4.1):
     * file "com.google.firebase.appcheck.debug.store." +
     * FirebaseApp.getPersistenceKey(), key
     * "com.google.firebase.appcheck.debug.DEBUG_SECRET". The persistence
     * key itself comes from the public FirebaseApp API — nothing is guessed.
     */
    private fun refreshDebugToken() {
        if (!BuildConfig.DEBUG) return
        val ctx = appContext ?: return
        try {
            val app = FirebaseApp.getInstance()
            val prefsName = "com.google.firebase.appcheck.debug.store." + app.persistenceKey
            val token = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
            if (!token.isNullOrBlank()) {
                _debugToken.value = token
                Log.i(TAG, "App Check debug token available (shown on home screen)")
            }
        } catch (t: Throwable) {
            // FirebaseApp not initialized yet, or prefs not created yet —
            // the token simply is not there; a later refresh will find it.
            Log.i(TAG, "Debug token not available yet: ${t.javaClass.simpleName}")
        }
    }
}
