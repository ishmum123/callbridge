package bd.callbridge.call

import bd.callbridge.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallStateMachineTest {

    @Test
    fun `initial state is IDLE`() {
        val sm = CallStateMachine()
        assertEquals(CallState.IDLE, sm.state)
    }

    @Test
    fun `known caller is answered immediately`() {
        val sm = CallStateMachine()
        sm.onIncomingRinging("+8801700000000")
        assertEquals(CallState.RINGING, sm.state)

        val actions = sm.onCallerLookupResult(isRegistered = true)
        assertEquals(CallState.ANSWERED, sm.state)
        assertEquals(listOf(CallAction.Answer("+8801700000000")), actions)

        sm.onCallActive()
        assertEquals(CallState.ACTIVE, sm.state)

        sm.onCallEnded()
        assertEquals(CallState.ENDED, sm.state)

        sm.reset()
        assertEquals(CallState.IDLE, sm.state)
    }

    @Test
    fun `unknown caller is rejected then called back`() {
        val sm = CallStateMachine()
        sm.onIncomingRinging("+8801800000000")

        val rejectActions = sm.onCallerLookupResult(isRegistered = false)
        assertEquals(CallState.REJECTED_FOR_CALLBACK, sm.state)
        assertEquals(
            listOf(
                CallAction.Reject("+8801800000000"),
                CallAction.ScheduleCallback("+8801800000000", Config.CALLBACK_DELAY_SECONDS),
            ),
            rejectActions,
        )

        val callbackActions = sm.onCallbackTimerFired()
        // Still waiting for the outbound leg to connect.
        assertEquals(CallState.REJECTED_FOR_CALLBACK, sm.state)
        assertEquals(listOf(CallAction.PlaceCallback("+8801800000000")), callbackActions)

        // Our outbound call connects -> same pipeline as an answered call (spec §3).
        sm.onCallActive()
        assertEquals(CallState.ACTIVE, sm.state)

        sm.onCallEnded()
        assertEquals(CallState.ENDED, sm.state)
        sm.reset()
        assertEquals(CallState.IDLE, sm.state)
    }

    @Test
    fun `second incoming call while active is rejected as busy with sms and stays active`() {
        val sm = CallStateMachine()
        sm.onIncomingRinging("+8801700000000")
        sm.onCallerLookupResult(isRegistered = true)
        sm.onCallActive()
        assertEquals(CallState.ACTIVE, sm.state)

        val busyActions = sm.onIncomingWhileActive("+8801900000000")
        assertEquals(
            listOf(
                CallAction.RejectBusy("+8801900000000"),
                CallAction.SendBusySms("+8801900000000"),
            ),
            busyActions,
        )
        // The existing call is untouched.
        assertEquals(CallState.ACTIVE, sm.state)
    }

    @Test
    fun `illegal transitions throw`() {
        val sm = CallStateMachine()
        assertThrows(IllegalStateException::class.java) { sm.onCallerLookupResult(true) }
        assertThrows(IllegalStateException::class.java) { sm.onCallbackTimerFired() }
        assertThrows(IllegalStateException::class.java) { sm.onCallActive() }
        assertThrows(IllegalStateException::class.java) { sm.onCallEnded() }
        assertThrows(IllegalStateException::class.java) { sm.onIncomingWhileActive("x") }
        assertThrows(IllegalStateException::class.java) { sm.reset() }
    }

    @Test
    fun `can handle back to back calls after reset`() {
        val sm = CallStateMachine()
        repeat(3) { i ->
            sm.onIncomingRinging("+88017000000$i")
            sm.onCallerLookupResult(isRegistered = true)
            sm.onCallActive()
            sm.onCallEnded()
            sm.reset()
            assertEquals(CallState.IDLE, sm.state)
        }
    }
}
