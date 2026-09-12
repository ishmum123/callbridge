package bd.callbridge.call

import android.content.Context
import android.net.Uri
import android.telecom.Call
import android.telecom.VideoProfile
import android.telephony.SmsManager
import android.util.Log
import bd.callbridge.Config
import bd.callbridge.store.CallerRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "CallController"
private const val BUSY_SMS_TEXT = "Sorry, we're on another call right now. We'll call you back."

/**
 * M3 hook: [CallController] notifies this of the Telecom lifecycle events that matter for
 * starting/stopping a [bd.callbridge.service.BridgeSession] (capture -> Gemini -> injector for
 * one call), without [CallController] itself knowing anything about audio/Gemini/injection.
 * Implemented by [bd.callbridge.service.BridgeSessionManager]. All methods are fire-and-forget
 * from [CallController]'s point of view — the coordinator owns its own coroutine scope and must
 * never let an exception escape back into a Telecom callback.
 */
interface CallSessionCoordinator {
    /** The call reached [CallState.ACTIVE]. [call] is the live Telecom call object (for anything
     *  the session needs from it beyond the number); [number] is the caller's number; [isOutgoing]
     *  is true for our own outbound callback leg connecting (spec §3's registered-caller callback
     *  flow), false for a normal inbound call answered directly. */
    fun onCallActive(call: Call, number: String, isOutgoing: Boolean)

    /** The active call ended (Telecom `onCallRemoved`/`STATE_DISCONNECTED`). Safe to call even
     *  when no session is running. */
    fun onCallEnded()

    /** [CallBridgeInCallService] is being destroyed; tear down any session as a safety net. */
    fun onServiceDestroyed()
}

/**
 * Bridges [CallBridgeInCallService]'s Telecom [Call] callbacks to the pure [CallStateMachine]
 * and executes the [CallAction]s it returns (spec §4.1: answer/reject/disconnect/placeCall/SMS).
 *
 * The state machine's transitions never throw (see its class doc); this controller is the layer
 * that logs when Telecom delivers an event out of the expected order ([logIfIllegal]) instead of
 * crashing the process, and it never lets an unhandled exception inside a launched coroutine kill
 * the app either (see [exceptionHandler]).
 *
 * M0 wires call control only. The Gemini bridge (M2/M3) hooks in by observing [CallStateMachine]
 * transitions (or a callback here) to open/close a [bd.callbridge.gemini.LiveSession] and start
 * [bd.callbridge.audio.AudioCapture] once a call reaches [CallState.ACTIVE].
 */
class CallController(
    private val context: Context,
    private val callerRepository: CallerRepository,
    private val stateMachine: CallStateMachine = CallStateMachine(),
    /** M3 wiring hook; null keeps this class runnable standalone (as in every M0/M1/M2 test). */
    private val sessionCoordinator: CallSessionCoordinator? = null,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.w(TAG, "Unhandled exception in CallController scope", throwable)
        },
    ),
) {
    private var activeTelecomCall: Call? = null
    private var callbackJob: Job? = null
    private var lastLoggedIllegalTransition: String? = null

    val state: CallState get() = stateMachine.state

    /**
     * Called from [CallBridgeInCallService.onCallAdded] for a call Telecom just handed us. This
     * is either: a genuinely new ring, a second call while ACTIVE (busy+SMS), or - if we're
     * [CallState.CALLING_BACK] - our own outbound callback leg connecting, which must NOT go
     * through caller lookup/reject (it *is* the call we placed).
     */
    fun onCallAdded(call: Call) {
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"

        if (stateMachine.state == CallState.CALLING_BACK && isOutgoing(call)) {
            activeTelecomCall = call
            return
        }

        if (stateMachine.state == CallState.ACTIVE) {
            val actions = stateMachine.onIncomingWhileActive(number)
            logIfIllegal()
            execute(actions, call)
            return
        }

        activeTelecomCall = call
        val ringActions = stateMachine.onIncomingRinging(number)
        logIfIllegal()
        execute(ringActions, null)

        scope.launch {
            val isRegistered = Config.ANSWER_UNREGISTERED_CALLERS || callerRepository.isRegistered(number)
            val actions = stateMachine.onCallerLookupResult(isRegistered)
            logIfIllegal()
            execute(actions, call)
        }
    }

    /** Called from the InCallService when Telecom reports the call state changed to ACTIVE. */
    fun onTelecomCallActive() {
        val actions = stateMachine.onCallActive()
        logIfIllegal()
        execute(actions, activeTelecomCall)

        val call = activeTelecomCall
        val number = stateMachine.currentNumber
        if (call != null && number != null && stateMachine.state == CallState.ACTIVE) {
            runCatching { sessionCoordinator?.onCallActive(call, number, isOutgoing(call)) }
                .onFailure { Log.e(TAG, "sessionCoordinator.onCallActive threw", it) }
        }
    }

    /**
     * Called from the InCallService on either `onCallRemoved` or
     * `onStateChanged(STATE_DISCONNECTED)` - whichever fires first; idempotent, safe to call from
     * both for the same call.
     */
    fun onCallRemoved() {
        runCatching { sessionCoordinator?.onCallEnded() }
            .onFailure { Log.e(TAG, "sessionCoordinator.onCallEnded threw", it) }

        if (stateMachine.state == CallState.IDLE) return // already fully settled - no-op

        val actions = stateMachine.onCallEnded()
        logIfIllegal()
        execute(actions, null)
        activeTelecomCall = null

        if (stateMachine.state == CallState.ENDED) {
            val resetActions = stateMachine.reset()
            logIfIllegal()
            execute(resetActions, null)
        }
    }

    /** Called from [CallBridgeInCallService.onDestroy] so a stale timer can't fire into a torn
     *  down service. Does not cancel [scope] itself - [CallController] is a long-lived singleton
     *  that outlives any one InCallService binding. */
    fun onServiceDestroyed() {
        cancelPendingCallback()
        runCatching { sessionCoordinator?.onServiceDestroyed() }
            .onFailure { Log.e(TAG, "sessionCoordinator.onServiceDestroyed threw", it) }
    }

    fun hangUp() {
        cancelPendingCallback()
        activeTelecomCall?.disconnect()
    }

    private fun execute(actions: List<CallAction>, call: Call?) {
        for (action in actions) {
            when (action) {
                is CallAction.Answer -> call?.answer(VideoProfile.STATE_AUDIO_ONLY)
                is CallAction.Reject -> call?.reject(false, null)
                is CallAction.RejectBusy -> call?.reject(false, null)
                is CallAction.SendBusySms -> sendSms(action.number, BUSY_SMS_TEXT)
                is CallAction.ScheduleCallback -> scheduleCallback(action.number, action.delaySeconds)
                is CallAction.PlaceCallback -> placeCall(action.number)
                is CallAction.CancelPendingCallback -> cancelPendingCallback()
            }
        }
    }

    private fun scheduleCallback(number: String, delaySeconds: Long) {
        callbackJob?.cancel()
        callbackJob = scope.launch {
            delay(delaySeconds * 1000)
            val actions = stateMachine.onCallbackTimerFired()
            logIfIllegal()
            // No Call object needed/available here - PlaceCallback goes straight through
            // TelecomManager (see placeCall below); that's the whole point of this being a
            // separate action from Answer/Reject.
            execute(actions, null)
        }
    }

    private fun cancelPendingCallback() {
        callbackJob?.cancel()
        callbackJob = null
    }

    /** Direction detection per spec/brief: `callDirection` is API 29+, always available here
     *  (minSdk 33); state fallback covers the brief window before Telecom finishes classifying it. */
    private fun isOutgoing(call: Call): Boolean {
        val direction = call.details?.callDirection
        return direction == Call.Details.DIRECTION_OUTGOING ||
            call.state == Call.STATE_DIALING ||
            call.state == Call.STATE_CONNECTING
    }

    private fun placeCall(number: String) {
        try {
            val telecomManager = context.getSystemService(android.telecom.TelecomManager::class.java)
            telecomManager?.placeCall(Uri.fromParts("tel", number, null), null)
        } catch (e: SecurityException) {
            Log.e(TAG, "placeCall failed for $number", e)
        }
    }

    private fun sendSms(number: String, text: String) {
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager?.sendTextMessage(number, null, text, null, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "sendSms failed for $number", e)
        }
    }

    private fun logIfIllegal() {
        val illegal = stateMachine.lastIllegalTransition
        if (illegal != null && illegal != lastLoggedIllegalTransition) {
            Log.w(TAG, "Ignored out-of-order call event: $illegal")
            lastLoggedIllegalTransition = illegal
        }
    }
}
