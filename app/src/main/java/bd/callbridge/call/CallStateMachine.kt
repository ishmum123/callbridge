package bd.callbridge.call

import bd.callbridge.Config

/**
 * Pure-Kotlin call lifecycle state machine (spec §4.1):
 *
 * ```
 * IDLE -> RINGING -> (ANSWERED | REJECTED_FOR_CALLBACK) -> ACTIVE -> ENDED -> IDLE
 * ```
 *
 * A second incoming call while ACTIVE does not change this machine's state (there is still
 * exactly one active call); it just emits [CallAction.RejectBusy] + [CallAction.SendBusySms]
 * for the new caller (spec §4.1, §3 step "second caller gets busy + SMS").
 *
 * No Android framework types here on purpose, so it is unit-testable without Robolectric.
 * [bd.callbridge.call.CallController] drives this machine from real Telecom callbacks and
 * executes the returned [CallAction]s (answer/reject/placeCall/sendSms), consulting
 * [bd.callbridge.store.CallerRepository] for the registered-caller policy before calling
 * [onCallerLookupResult].
 */
enum class CallState { IDLE, RINGING, ANSWERED, REJECTED_FOR_CALLBACK, ACTIVE, ENDED }

sealed interface CallAction {
    data class Answer(val number: String) : CallAction
    data class Reject(val number: String) : CallAction
    data class ScheduleCallback(val number: String, val delaySeconds: Long) : CallAction
    data class PlaceCallback(val number: String) : CallAction
    data class RejectBusy(val number: String) : CallAction
    data class SendBusySms(val number: String) : CallAction
}

class CallStateMachine {
    var state: CallState = CallState.IDLE
        private set

    var currentNumber: String? = null
        private set

    /** Ring arrives (`InCallService.onCallAdded`, spec §3 step 1). Only valid from IDLE/ENDED. */
    fun onIncomingRinging(number: String) {
        check(state == CallState.IDLE || state == CallState.ENDED) {
            "onIncomingRinging called in state $state"
        }
        currentNumber = number
        state = CallState.RINGING
    }

    /**
     * Registered-caller policy result (spec §3 step 2). Known caller -> answer immediately.
     * Unknown caller -> reject on first ring, schedule a callback in
     * [Config.CALLBACK_DELAY_SECONDS] so it's free for them.
     */
    fun onCallerLookupResult(isRegistered: Boolean): List<CallAction> {
        check(state == CallState.RINGING) { "onCallerLookupResult called in state $state" }
        val number = requireNotNull(currentNumber)
        return if (isRegistered) {
            state = CallState.ANSWERED
            listOf(CallAction.Answer(number))
        } else {
            state = CallState.REJECTED_FOR_CALLBACK
            listOf(
                CallAction.Reject(number),
                CallAction.ScheduleCallback(number, Config.CALLBACK_DELAY_SECONDS),
            )
        }
    }

    /** The callback delay timer fired; place the outbound call (spec §3 step 2/§4.1). */
    fun onCallbackTimerFired(): List<CallAction> {
        check(state == CallState.REJECTED_FOR_CALLBACK) { "onCallbackTimerFired called in state $state" }
        val number = requireNotNull(currentNumber)
        return listOf(CallAction.PlaceCallback(number))
    }

    /**
     * The call becomes active on the telecom side: either our `answer()` connected, or the
     * outbound callback connected ("On our outbound call connecting -> same pipeline", spec §3).
     */
    fun onCallActive() {
        check(state == CallState.ANSWERED || state == CallState.REJECTED_FOR_CALLBACK) {
            "onCallActive called in state $state"
        }
        state = CallState.ACTIVE
    }

    /** Either side hangs up, or the watchdog gives up. */
    fun onCallEnded() {
        check(state == CallState.ACTIVE || state == CallState.ANSWERED || state == CallState.REJECTED_FOR_CALLBACK) {
            "onCallEnded called in state $state"
        }
        state = CallState.ENDED
        currentNumber = null
    }

    /** Back to IDLE, ready for the next call. */
    fun reset() {
        check(state == CallState.ENDED) { "reset called in state $state" }
        state = CallState.IDLE
    }

    /**
     * A second incoming call while one is already [CallState.ACTIVE] (spec §4.1: "Only one call
     * may be ACTIVE; a second incoming call while ACTIVE -> reject + SMS"). Does not change
     * [state]; the existing call keeps running.
     */
    fun onIncomingWhileActive(number: String): List<CallAction> {
        check(state == CallState.ACTIVE) { "onIncomingWhileActive called in state $state" }
        return listOf(CallAction.RejectBusy(number), CallAction.SendBusySms(number))
    }
}
