package bd.callbridge.call

import bd.callbridge.Config

/**
 * Pure-Kotlin call lifecycle state machine (spec §4.1, extended to model the callback window):
 *
 * ```
 * IDLE -> RINGING -> ANSWERED ------------------------\
 *                 \-> REJECTED_FOR_CALLBACK -> CALLING_BACK -> ACTIVE -> ENDED -> IDLE
 * ```
 *
 * `REJECTED_FOR_CALLBACK` covers the whole window between rejecting the inbound leg and the
 * outbound callback connecting: the inbound call being removed by Telecom while we're still
 * waiting on the 2 s timer does NOT end this machine (see [onCallEnded]) - only the timer firing
 * (or a fresh ring for the same/any number, which cancels the pending timer) moves it on.
 *
 * A second incoming call while ACTIVE does not change this machine's state (there is still
 * exactly one active call); it just emits [CallAction.RejectBusy] + [CallAction.SendBusySms]
 * for the new caller (spec §4.1, §3 step "second caller gets busy + SMS").
 *
 * Every public transition is non-throwing: Telecom can legitimately deliver events out of the
 * expected order (e.g. the callback timer firing after the call was already torn down some other
 * way), and a crash on every such race is worse than ignoring it. An event that doesn't apply to
 * the current state is a no-op that returns an empty action list and records [lastIllegalTransition]
 * for the caller to log; it never throws.
 *
 * No Android framework types here on purpose, so it is unit-testable without Robolectric.
 * [bd.callbridge.call.CallController] drives this machine from real Telecom callbacks and
 * executes the returned [CallAction]s (answer/reject/placeCall/sendSms), consulting
 * [bd.callbridge.store.CallerRepository] for the registered-caller policy before calling
 * [onCallerLookupResult].
 */
enum class CallState { IDLE, RINGING, ANSWERED, REJECTED_FOR_CALLBACK, CALLING_BACK, ACTIVE, ENDED }

sealed interface CallAction {
    data class Answer(val number: String) : CallAction
    data class Reject(val number: String) : CallAction
    data class ScheduleCallback(val number: String, val delaySeconds: Long) : CallAction
    data class PlaceCallback(val number: String) : CallAction
    data class RejectBusy(val number: String) : CallAction
    data class SendBusySms(val number: String) : CallAction

    /** A pending callback timer (and its Telecom Job, controller-side) should be cancelled -
     *  emitted when a fresh ring arrives for the number we were about to call back. */
    object CancelPendingCallback : CallAction
}

/**
 * @param clock injectable wall-clock source (defaults to [System.currentTimeMillis]) so the
 * per-number reschedule cap ([RESCHEDULE_CAP_WINDOW_MS]) is deterministically unit-testable.
 */
class CallStateMachine(private val clock: () -> Long = System::currentTimeMillis) {
    var state: CallState = CallState.IDLE
        private set

    var currentNumber: String? = null
        private set

    /** Most recent transition attempt that didn't apply to [state]. Pure Kotlin, no android.util.Log
     *  here on purpose (see class doc) - [bd.callbridge.call.CallController] logs it. Null once no
     *  illegal transition has been attempted since construction; otherwise sticky until the next one. */
    var lastIllegalTransition: String? = null
        private set

    /** True once we've rejected an unknown caller and scheduled the callback timer for the number
     *  currently in [REJECTED_FOR_CALLBACK]; false if the reschedule cap suppressed the timer, in
     *  which case [onCallEnded] should resolve straight to ENDED instead of waiting forever. */
    private var callbackScheduled = false

    /** number -> wall-clock millis of the last time we scheduled a callback for it. Caps reject
     *  loops: at most one reschedule per number per [RESCHEDULE_CAP_WINDOW_MS]. */
    private val lastRescheduleAtMillis = mutableMapOf<String, Long>()

    private fun illegal(event: String): List<CallAction> {
        lastIllegalTransition = "$event called in state $state"
        return emptyList()
    }

    /**
     * Ring arrives (`InCallService.onCallAdded`, spec §3 step 1). Valid from IDLE/ENDED (a fresh
     * call), and also from REJECTED_FOR_CALLBACK/CALLING_BACK (a new ring - same number calling
     * back early, or a different number - while we're mid-callback for the previous one; this
     * cancels the pending timer/Job so it isn't fired twice or against the wrong call).
     */
    fun onIncomingRinging(number: String): List<CallAction> {
        val waitingOnCallback = state == CallState.REJECTED_FOR_CALLBACK || state == CallState.CALLING_BACK
        if (state != CallState.IDLE && state != CallState.ENDED && !waitingOnCallback) {
            return illegal("onIncomingRinging")
        }
        currentNumber = number
        state = CallState.RINGING
        return if (waitingOnCallback) listOf(CallAction.CancelPendingCallback) else emptyList()
    }

    /**
     * Registered-caller policy result (spec §3 step 2). Known caller -> answer immediately.
     * Unknown caller -> reject on first ring, schedule a callback in
     * [Config.CALLBACK_DELAY_SECONDS] so it's free for them - unless we already rescheduled for
     * this number within [RESCHEDULE_CAP_WINDOW_MS], in which case just reject (no new timer) to
     * avoid a reject-callback-reject loop.
     */
    fun onCallerLookupResult(isRegistered: Boolean): List<CallAction> {
        if (state != CallState.RINGING) return illegal("onCallerLookupResult")
        val number = requireNotNull(currentNumber)
        if (isRegistered) {
            state = CallState.ANSWERED
            return listOf(CallAction.Answer(number))
        }

        val now = clock()
        val last = lastRescheduleAtMillis[number]
        val capped = last != null && now - last < RESCHEDULE_CAP_WINDOW_MS
        state = CallState.REJECTED_FOR_CALLBACK
        callbackScheduled = !capped
        return if (capped) {
            listOf(CallAction.Reject(number))
        } else {
            lastRescheduleAtMillis[number] = now
            listOf(
                CallAction.Reject(number),
                CallAction.ScheduleCallback(number, Config.CALLBACK_DELAY_SECONDS),
            )
        }
    }

    /** The callback delay timer fired; place the outbound call (spec §3 step 2/§4.1). Moves to
     *  CALLING_BACK so the outbound Telecom call that's about to arrive via `onCallAdded` is
     *  recognized as our own leg rather than a fresh inbound ring. */
    fun onCallbackTimerFired(): List<CallAction> {
        if (state != CallState.REJECTED_FOR_CALLBACK || !callbackScheduled) {
            return illegal("onCallbackTimerFired")
        }
        val number = requireNotNull(currentNumber)
        state = CallState.CALLING_BACK
        callbackScheduled = false
        return listOf(CallAction.PlaceCallback(number))
    }

    /**
     * The call becomes active on the telecom side: either our `answer()` connected, or the
     * outbound callback connected ("On our outbound call connecting -> same pipeline", spec §3).
     */
    fun onCallActive(): List<CallAction> {
        if (state != CallState.ANSWERED && state != CallState.CALLING_BACK) {
            return illegal("onCallActive")
        }
        state = CallState.ACTIVE
        return emptyList()
    }

    /**
     * Either side hangs up, the watchdog gives up, or Telecom otherwise tears the call down
     * (`onCallRemoved` and/or `onStateChanged(STATE_DISCONNECTED)` - callers may invoke this from
     * either or both; it's idempotent).
     *
     * Special case: while [CallState.REJECTED_FOR_CALLBACK] with a callback still scheduled, the
     * inbound leg being removed is expected (we rejected it) and must NOT end the machine - it
     * stays put, waiting for [onCallbackTimerFired]. If the reschedule cap suppressed the timer
     * (no callback scheduled), there's nothing left to wait for, so it resolves to ENDED normally.
     */
    fun onCallEnded(): List<CallAction> {
        return when (state) {
            CallState.REJECTED_FOR_CALLBACK -> {
                if (callbackScheduled) {
                    emptyList()
                } else {
                    state = CallState.ENDED
                    currentNumber = null
                    emptyList()
                }
            }
            CallState.RINGING, CallState.ANSWERED, CallState.CALLING_BACK, CallState.ACTIVE -> {
                state = CallState.ENDED
                currentNumber = null
                emptyList()
            }
            CallState.IDLE, CallState.ENDED -> emptyList() // already gone - idempotent no-op
        }
    }

    /** Back to IDLE, ready for the next call. */
    fun reset(): List<CallAction> {
        if (state != CallState.ENDED) return illegal("reset")
        state = CallState.IDLE
        return emptyList()
    }

    /**
     * A second incoming call while one is already [CallState.ACTIVE] (spec §4.1: "Only one call
     * may be ACTIVE; a second incoming call while ACTIVE -> reject + SMS"). Does not change
     * [state]; the existing call keeps running.
     */
    fun onIncomingWhileActive(number: String): List<CallAction> {
        if (state != CallState.ACTIVE) return illegal("onIncomingWhileActive")
        return listOf(CallAction.RejectBusy(number), CallAction.SendBusySms(number))
    }

    companion object {
        private const val RESCHEDULE_CAP_WINDOW_MS = 60_000L
    }
}
