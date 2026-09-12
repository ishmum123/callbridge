package bd.callbridge.call

import android.telecom.Call
import android.telecom.InCallService
import bd.callbridge.CallBridgeApp

/**
 * Registered in the manifest with `BIND_INCALL_SERVICE`; this is what makes CallBridge a
 * default-dialer candidate (spec §4.1). All policy lives in [CallController]/[CallStateMachine];
 * this class only forwards Telecom lifecycle callbacks.
 */
class CallBridgeInCallService : InCallService() {

    private val callController: CallController
        get() = (application as CallBridgeApp).callController

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_ACTIVE) {
                callController.onTelecomCallActive()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        call.registerCallback(callCallback)
        callController.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        callController.onCallRemoved()
    }
}
