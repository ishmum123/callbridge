package bd.callbridge.service

import android.content.Context
import bd.callbridge.CallBridgeApp
import android.telecom.Call
import android.util.Log
import bd.callbridge.Config
import bd.callbridge.BuildConfig
import bd.callbridge.call.CallController
import bd.callbridge.call.CallSessionCoordinator
import bd.callbridge.gemini.CallerProfile
import bd.callbridge.gemini.FunctionDeclaration
import bd.callbridge.gemini.HealthPromptBn
import bd.callbridge.gemini.SystemPromptBuilder
import bd.callbridge.knowledge.CompositeHealthKnowledge
import bd.callbridge.knowledge.HealthKnowledge
import bd.callbridge.store.CallBridgeDatabase
import bd.callbridge.store.CallDirection
import bd.callbridge.store.CallEntity
import bd.callbridge.store.CallerRepository
import bd.callbridge.store.PatientProfileEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "BridgeSessionManager"

/** Shop name interpolated into the system prompt (spec §6 `{shop}` placeholder). Pilot has one
 *  shop; move to a settings screen if/when there's more than one. */
private const val SHOP_NAME = "CallBridge দোকান"

/**
 * [CallSessionCoordinator] implementation that owns exactly one [BridgeSession] at a time,
 * created/destroyed by [bd.callbridge.call.CallController]'s Telecom lifecycle. Lives on
 * [bd.callbridge.CallBridgeApp] as a singleton next to [CallController] itself.
 *
 * Exposes [status] as the minimal StateFlow the status screen (spec §4.6) needs — brief calls for
 * "keep minimal; a StateFlow on the app singleton is fine", so this intentionally doesn't try to
 * be a general call-history API (that's `calls`/`turns` in Room, already there from M0).
 *
 * **Concurrency (code review fix):** [currentSession] is assigned *before* [BridgeSession.start]
 * is called (construction is local/synchronous — no network) rather than after it returns, and
 * [BridgeSession.start] itself now returns promptly (see its doc comment: the actual
 * [bd.callbridge.gemini.LiveSession.open] network call runs in a background job the session owns
 * and [BridgeSession.stop] can cancel directly). This closes the original gap where a call ending
 * mid-open (`onCallEnded` racing a slow `open()`) had nothing to tear down yet, leaking the
 * WebSocket/AudioTrack/AudioRecord/collectors for up to the full 10s setup timeout. [sessionMutex]
 * is only ever held for the quick, synchronous field read/write — never across a suspending
 * network call.
 */
class BridgeSessionManager(
    private val context: Context,
    private val database: CallBridgeDatabase,
    private val callerRepository: CallerRepository,
) : CallSessionCoordinator {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in BridgeSessionManager scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val sessionMutex = Mutex()
    @Volatile private var lastCallId: Long? = null
    private var currentSession: BridgeSession? = null
    private var controller: CallController? = null

    /** Tracks the in-flight [onCallActive] build/start job so [onCallEnded]/[onServiceDestroyed]
     *  can cancel it if the call ends before [currentSession] even gets assigned (code review
     *  fix, closing the last sliver of the "call ends during open" leak: [buildSession] does a
     *  little local/DB work before [currentSession] is set, and without this a call ending in
     *  that window would find nothing to stop and the build would complete/start anyway). */
    private var pendingJob: Job? = null

    /** [CallController] can't easily be constructed before this manager (circular otherwise);
     *  wired once by [bd.callbridge.CallBridgeApp] right after both exist. */
    fun attach(callController: CallController) {
        controller = callController
    }

    override fun onCallActive(call: Call, number: String, isOutgoing: Boolean) {
        BridgeForegroundService.start(context)
        pendingJob = scope.launch {
            val session = try {
                buildSession(number, isOutgoing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build BridgeSession for $number; hanging up", e)
                controller?.hangUp()
                return@launch
            }

            val accepted = sessionMutex.withLock {
                if (currentSession != null) {
                    false
                } else {
                    currentSession = session
                    true
                }
            }
            if (!accepted) {
                Log.w(TAG, "onCallActive with a session already running; ignoring (one call at a time)")
                runCatching { session.stop("superseded: session already running") }
                return@launch
            }

            // start() returns promptly (the network open runs in a background job the session
            // itself owns) — the mutex is never held across it.
            session.start()
        }
    }

    override fun onCallEnded() {
        scope.launch { endActiveOrPendingSession("call ended") }
    }

    override fun onServiceDestroyed() {
        scope.launch { endActiveOrPendingSession("service destroyed") }
    }

    private suspend fun endActiveOrPendingSession(reason: String) {
        pendingJob?.cancelAndJoin()
        pendingJob = null
        stopCurrentSession(reason)
    }

    private suspend fun buildSession(number: String, isOutgoing: Boolean): BridgeSession {
        val caller = callerRepository.find(number)
        val profile = CallerProfile(
            number = number,
            name = caller?.name,
            village = caller?.village,
            occupation = caller?.occupation,
        )
        val startedAtMs = System.currentTimeMillis()
        val callId = database.callDao().insert(
            CallEntity(
                number = number,
                // isOutgoing is true for our own outbound callback leg connecting (spec §3's
                // registered-caller callback flow) — code review fix, this was previously
                // hardcoded to INBOUND for every call.
                direction = if (isOutgoing) CallDirection.OUTBOUND_CALLBACK else CallDirection.INBOUND,
                startedAt = startedAtMs,
            )
        )
        lastCallId = callId

        // Health-demo scope switch (Config.HEALTH_DEMO): health-only Bangla persona + the
        // lookup_health_info tool, grounded in the caller's existing patient-profile summary if
        // one exists, instead of the general shop-assistant prompt (docs/gemini-tools.md).
        val (systemPrompt, tools, healthKnowledge, callerContextSummary) = if (Config.HEALTH_DEMO) {
            val patientProfileRepository = (context.applicationContext as? bd.callbridge.CallBridgeApp)?.profileRepository
            val patient = patientProfileRepository?.find(number)
            val summary = patientProfileSummary(patient)
            HealthSessionInputs(
                systemPrompt = HealthPromptBn.healthSystemPrompt(summary),
                tools = listOf(HealthPromptBn.lookupHealthInfoTool),
                healthKnowledge = CompositeHealthKnowledge.fromKeys(BuildConfig.EXA_API_KEY, BuildConfig.OPENAI_API_KEY),
                callerContextSummary = summary,
            )
        } else {
            HealthSessionInputs(
                systemPrompt = SystemPromptBuilder.build(context, profile, SHOP_NAME),
                tools = emptyList(),
                healthKnowledge = null,
                callerContextSummary = null,
            )
        }

        return BridgeSessionFactory.create(
            context = context,
            callerProfile = profile,
            systemPrompt = systemPrompt,
            callId = callId,
            turnDao = database.turnDao(),
            callDao = database.callDao(),
            startedAtMs = startedAtMs,
            scope = scope,
            onHangupRequested = { controller?.hangUp() },
            onStatus = { _status.value = it },
            tools = tools,
            healthKnowledge = healthKnowledge,
            callerContextSummary = callerContextSummary,
        )
    }

    /** Grouped return value for [buildSession]'s health-demo/shop-assistant branch — avoids a
     *  4-way `Pair`-of-`Pair`s or four separate `var`s. */
    private data class HealthSessionInputs(
        val systemPrompt: String,
        val tools: List<FunctionDeclaration>,
        val healthKnowledge: HealthKnowledge?,
        val callerContextSummary: String?,
    )

    /**
     * Short Bangla+English caller-context line built from an existing [PatientProfileEntity]
     * (demo "Patient profile" feature — see `store/ProfileRepository.kt`), for injection into
     * [HealthPromptBn.healthSystemPrompt]. Null when there's no profile yet or nothing in it is
     * populated (a brand-new/unknown caller).
     */
    private fun patientProfileSummary(patient: PatientProfileEntity?): String? {
        if (patient == null) return null
        val bn = mutableListOf<String>()
        val en = mutableListOf<String>()
        patient.displayName?.takeIf { it.isNotBlank() }?.let {
            bn += "নাম $it"; en += "name $it"
        }
        patient.ageYears?.let {
            bn += "বয়স আনুমানিক $it"; en += "age ~$it"
        }
        if (patient.chronicConditions.isNotEmpty()) {
            bn += "রোগ: ${patient.chronicConditions.joinToString(", ")}"
            en += "conditions: ${patient.chronicConditions.joinToString(", ")}"
        }
        if (patient.medications.isNotEmpty()) {
            bn += "ওষুধ: ${patient.medications.joinToString(", ")}"
            en += "meds: ${patient.medications.joinToString(", ")}"
        }
        if (patient.riskFlags.isNotEmpty()) {
            bn += "ঝুঁকি চিহ্ন: ${patient.riskFlags.joinToString(", ")}"
            en += "risk flags: ${patient.riskFlags.joinToString(", ")}"
        }
        patient.adviceGiven.lastOrNull()?.let {
            bn += "সর্বশেষ পরামর্শ: $it"; en += "last advice: $it"
        }
        if (bn.isEmpty()) return null
        return "${bn.joinToString("; ")} (${en.joinToString("; ")})"
    }

    private suspend fun stopCurrentSession(reason: String) {
        val session = sessionMutex.withLock {
            val s = currentSession
            currentSession = null
            s
        } ?: return
        runCatching { session.stop(reason) }
            .onFailure { Log.e(TAG, "BridgeSession.stop threw during teardown", it) }
        // Patient-profile demo: merge this call's transcript into the caller's profile once the
        // transcript is finished (TranscriptRecorder.finish() runs inside session.stop()).
        val callId = lastCallId ?: return
        lastCallId = null
        scope.launch {
            runCatching {
                (context.applicationContext as? CallBridgeApp)?.profileSummarizer?.onCallFinished(callId)
            }.onFailure { Log.w(TAG, "ProfileSummarizer.onCallFinished($callId) threw", it) }
        }
    }
}
