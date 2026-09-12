package bd.callbridge.service

import android.content.Context
import android.telecom.Call
import android.util.Log
import bd.callbridge.Config
import bd.callbridge.call.CallController
import bd.callbridge.call.CallSessionCoordinator
import bd.callbridge.gemini.CallerProfile
import bd.callbridge.gemini.SystemPromptBuilder
import bd.callbridge.store.CallBridgeDatabase
import bd.callbridge.store.CallDirection
import bd.callbridge.store.CallEntity
import bd.callbridge.store.CallerRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private var currentSession: BridgeSession? = null
    private var controller: CallController? = null

    /** [CallController] can't easily be constructed before this manager (circular otherwise);
     *  wired once by [bd.callbridge.CallBridgeApp] right after both exist. */
    fun attach(callController: CallController) {
        controller = callController
    }

    override fun onCallActive(call: Call, number: String) {
        BridgeForegroundService.start(context)
        scope.launch {
            sessionMutex.withLock {
                if (currentSession != null) {
                    Log.w(TAG, "onCallActive with a session already running; ignoring (one call at a time)")
                    return@withLock
                }
                try {
                    currentSession = buildAndStartSession(number)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start BridgeSession for $number; hanging up", e)
                    controller?.hangUp()
                }
            }
        }
    }

    override fun onCallEnded() {
        scope.launch { stopCurrentSession("call ended") }
    }

    override fun onServiceDestroyed() {
        scope.launch { stopCurrentSession("service destroyed") }
    }

    private suspend fun buildAndStartSession(number: String): BridgeSession {
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
                direction = CallDirection.INBOUND,
                startedAt = startedAtMs,
            )
        )
        val systemPrompt = SystemPromptBuilder.build(context, profile, SHOP_NAME)

        val session = BridgeSessionFactory.create(
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
        )
        session.start()
        return session
    }

    private suspend fun stopCurrentSession(reason: String) {
        val session = sessionMutex.withLock {
            val s = currentSession
            currentSession = null
            s
        } ?: return
        runCatching { session.stop(reason) }
            .onFailure { Log.e(TAG, "BridgeSession.stop threw during teardown", it) }
    }
}
