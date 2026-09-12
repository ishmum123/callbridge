package bd.callbridge.call

import android.content.Context
import android.net.Uri
import android.telecom.Call
import android.telecom.VideoProfile
import android.telephony.SmsManager
import android.util.Log
import bd.callbridge.store.CallerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "CallController"
private const val BUSY_SMS_TEXT = "Sorry, we're on another call right now. We'll call you back."

/**
 * Bridges [CallBridgeInCallService]'s Telecom [Call] callbacks to the pure [CallStateMachine]
 * and executes the [CallAction]s it returns (spec §4.1: answer/reject/disconnect/placeCall/SMS).
 *
 * M0 wires call control only. The Gemini bridge (M2/M3) hooks in by observing [CallStateMachine]
 * transitions (or a callback here) to open/close a [bd.callbridge.gemini.LiveSession] and start
 * [bd.callbridge.audio.AudioCapture] once a call reaches [CallState.ACTIVE].
 */
class CallController(
    private val context: Context,
    private val callerRepository: CallerRepository,
    private val stateMachine: CallStateMachine = CallStateMachine(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private var activeTelecomCall: Call? = null

    val state: CallState get() = stateMachine.state

    /** Called from [CallBridgeInCallService.onCallAdded] for a newly ringing/incoming call. */
    fun onCallAdded(call: Call) {
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"

        if (stateMachine.state == CallState.ACTIVE) {
            val actions = stateMachine.onIncomingWhileActive(number)
            execute(actions, call)
            return
        }

        activeTelecomCall = call
        stateMachine.onIncomingRinging(number)

        scope.launch {
            val isRegistered = callerRepository.isRegistered(number)
            val actions = stateMachine.onCallerLookupResult(isRegistered)
            execute(actions, call)
        }
    }

    /** Called from the InCallService when Telecom reports the call state changed to ACTIVE. */
    fun onTelecomCallActive() {
        if (stateMachine.state == CallState.ANSWERED || stateMachine.state == CallState.REJECTED_FOR_CALLBACK) {
            stateMachine.onCallActive()
        }
    }

    /** Called from the InCallService when Telecom reports the call was disconnected. */
    fun onCallRemoved() {
        if (stateMachine.state == CallState.ACTIVE ||
            stateMachine.state == CallState.ANSWERED ||
            stateMachine.state == CallState.REJECTED_FOR_CALLBACK
        ) {
            stateMachine.onCallEnded()
        }
        activeTelecomCall = null
        if (stateMachine.state == CallState.ENDED) stateMachine.reset()
    }

    fun hangUp() {
        activeTelecomCall?.disconnect()
    }

    private fun execute(actions: List<CallAction>, call: Call) {
        for (action in actions) {
            when (action) {
                is CallAction.Answer -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
                is CallAction.Reject -> call.reject(false, null)
                is CallAction.RejectBusy -> call.reject(false, null)
                is CallAction.SendBusySms -> sendSms(action.number, BUSY_SMS_TEXT)
                is CallAction.ScheduleCallback -> scheduleCallback(action.number, action.delaySeconds)
                is CallAction.PlaceCallback -> placeCall(action.number)
            }
        }
    }

    private fun scheduleCallback(number: String, delaySeconds: Long) {
        scope.launch {
            delay(delaySeconds * 1000)
            val actions = stateMachine.onCallbackTimerFired()
            execute(actions, activeTelecomCall ?: return@launch)
        }
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
}
