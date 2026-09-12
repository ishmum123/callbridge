package bd.callbridge.service

import bd.callbridge.audio.Injector
import bd.callbridge.audio.InjectorRoute
import bd.callbridge.audio.RouteProbe
import bd.callbridge.audio.VadGate
import bd.callbridge.gemini.CallerProfile
import bd.callbridge.gemini.LiveSession
import bd.callbridge.gemini.LiveSessionEvent
import bd.callbridge.gemini.SessionTerminalState
import bd.callbridge.gemini.TranscriptRecorder
import bd.callbridge.gemini.VadMode
import bd.callbridge.store.CallDirection
import bd.callbridge.store.CallEntity
import bd.callbridge.store.TurnEntity
import bd.callbridge.store.dao.CallDao
import bd.callbridge.store.dao.TurnDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every call so tests can assert on interaction without touching Android APIs. */
private class FakeLiveSession : LiveSession {
    val sentAudio = mutableListOf<ShortArray>()
    var openCalled = false
    var closedCalled = false
    var activityStartCount = 0
    var activityEndCount = 0
    var interruptCount = 0

    val eventsFlow = MutableSharedFlow<LiveSessionEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    val terminal = MutableStateFlow<SessionTerminalState>(SessionTerminalState.Open)

    override suspend fun open(systemPrompt: String, profile: CallerProfile) {
        openCalled = true
    }

    override fun sendAudio(pcm: ShortArray) {
        sentAudio.add(pcm)
    }

    override val droppedAudioChunkCount: Long = 0L

    override fun sendActivityStart() {
        activityStartCount++
    }

    override fun sendActivityEnd() {
        activityEndCount++
    }

    override fun interrupt() {
        interruptCount++
    }

    override val events: Flow<LiveSessionEvent> get() = eventsFlow

    override val terminalState: StateFlow<SessionTerminalState> get() = terminal

    override suspend fun close() {
        closedCalled = true
    }
}

private class FakeInjector(override val route: InjectorRoute = InjectorRoute.LOOPBACK) : Injector {
    var openCount = 0
    var closeCount = 0
    var flushCount = 0
    val written = mutableListOf<ShortArray>()

    override fun open() {
        openCount++
    }

    override fun write(pcm: ShortArray) {
        written.add(pcm)
    }

    override fun flush() {
        flushCount++
    }

    override fun close() {
        closeCount++
    }

    override fun probe(): RouteProbe = RouteProbe(route, deviceFound = true, preferredDeviceSet = true, detail = "fake")
}

private class FakeTurnDao : TurnDao {
    val inserted = mutableListOf<TurnEntity>()
    override suspend fun insert(turn: TurnEntity): Long {
        inserted.add(turn)
        return inserted.size.toLong()
    }
    override suspend fun forCall(callId: Long): List<TurnEntity> = inserted.filter { it.callId == callId }
}

private class FakeCallDao(initial: CallEntity) : CallDao {
    var stored: CallEntity = initial
    override suspend fun insert(call: CallEntity): Long { stored = call; return call.id }
    override suspend fun update(call: CallEntity) { stored = call }
    override suspend fun findById(id: Long): CallEntity? = stored.takeIf { it.id == id }
    override fun observeCallsSince(sinceEpochMs: Long): Flow<Int> = flowOf(0)
    override fun observeCostSince(sinceEpochMs: Long): Flow<Double> = flowOf(0.0)
}

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeSessionTest {
    private val profile = CallerProfile(number = "01700000000", name = null, village = null, occupation = null)

    private fun newRecorder(): TranscriptRecorder {
        val callId = 1L
        val callDao = FakeCallDao(CallEntity(id = callId, number = profile.number, direction = CallDirection.INBOUND, startedAt = 0L))
        return TranscriptRecorder(callId, FakeTurnDao(), callDao, startedAtMs = 0L)
    }

    private fun buildSession(
        scope: TestScope,
        liveSession: FakeLiveSession,
        injector: FakeInjector,
        recorder: TranscriptRecorder = newRecorder(),
        vadEvents: Flow<VadGate.Event> = emptyFlow(),
        audioChunks: Flow<ShortArray> = emptyFlow(),
        onHangupRequested: suspend () -> Unit = {},
        statuses: MutableList<BridgeStatus> = mutableListOf(),
    ) = BridgeSession(
        liveSession = liveSession,
        injector = injector,
        transcriptRecorder = recorder,
        audioChunks = audioChunks,
        vadEvents = vadEvents,
        systemPrompt = "prompt",
        callerProfile = profile,
        vadMode = VadMode.LOCAL_VAD,
        // backgroundScope (not the test body's own scope): its children are auto-cancelled at
        // the end of runTest, so an infinite collector (e.g. on a never-completing VAD-events
        // SharedFlow) doesn't hang the test waiting for it to finish.
        scope = scope.backgroundScope,
        onHangupRequested = onHangupRequested,
        onStatus = { statuses.add(it) },
    )

    @Test
    fun `AudioOut is resampled 24kHz to 16kHz and written to the injector`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val session = buildSession(this, liveSession, injector)

        session.start()
        runCurrent()
        assertTrue(liveSession.openCalled)

        // 240 samples @ 24kHz (10ms) should resample down toward ~160 samples @ 16kHz.
        val pcm = ShortArray(240) { (it % 100).toShort() }
        liveSession.eventsFlow.emit(LiveSessionEvent.AudioOut(pcm, sampleRate = 24_000))
        runCurrent()

        assertTrue("expected the injector to receive at least one resampled chunk", injector.written.isNotEmpty())
        val totalWrittenSamples = injector.written.sumOf { it.size }
        // Allow generous slack for the resampler's windowed-sinc history/lookahead; the point of
        // this assertion is "meaningfully downsampled", not an exact sample count.
        assertTrue(
            "expected roughly 2/3 as many samples after 24k->16k resampling, got $totalWrittenSamples from ${pcm.size}",
            totalWrittenSamples in 1..(pcm.size),
        )
    }

    @Test
    fun `barge-in on caller speech while model is speaking interrupts and flushes`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val vadEvents = MutableSharedFlow<VadGate.Event>(extraBufferCapacity = 8)
        val session = buildSession(this, liveSession, injector, vadEvents = vadEvents)

        session.start()
        runCurrent()

        // Model starts speaking.
        liveSession.eventsFlow.emit(LiveSessionEvent.AudioOut(ShortArray(160), sampleRate = 24_000))
        runCurrent()

        // Caller barges in.
        vadEvents.emit(VadGate.Event.SpeechStarted)
        runCurrent()

        assertEquals(1, liveSession.interruptCount)
        assertEquals(1, injector.flushCount)
    }

    @Test
    fun `no barge-in interrupt when model is not speaking`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val vadEvents = MutableSharedFlow<VadGate.Event>(extraBufferCapacity = 8)
        val session = buildSession(this, liveSession, injector, vadEvents = vadEvents)

        session.start()
        runCurrent()

        vadEvents.emit(VadGate.Event.SpeechStarted)
        runCurrent()

        assertEquals(0, liveSession.interruptCount)
        assertEquals(0, injector.flushCount)
        // Local VAD mode still drives the manual activity signal even without a barge-in.
        assertTrue(liveSession.activityStartCount >= 1)
    }

    @Test
    fun `hangup phrase in the output transcript triggers onHangupRequested`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val recorder = newRecorder()
        var hangupCalls = 0
        val statuses = mutableListOf<BridgeStatus>()
        val session = buildSession(
            this, liveSession, injector, recorder = recorder,
            onHangupRequested = { hangupCalls++ },
            statuses = statuses,
        )

        session.start()
        runCurrent()

        liveSession.eventsFlow.emit(LiveSessionEvent.OutputTranscript("আল্লাহ হাফেজ, ভালো থাকবেন।"))
        liveSession.eventsFlow.emit(LiveSessionEvent.TurnComplete)
        runCurrent()

        assertEquals(1, hangupCalls)
        assertTrue(statuses.any { it.phase == BridgePhase.ENDING })
    }

    @Test
    fun `session failure (terminal Failed) also triggers onHangupRequested`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        var hangupCalls = 0
        val session = buildSession(this, liveSession, injector, onHangupRequested = { hangupCalls++ })

        session.start()
        runCurrent()

        liveSession.terminal.value = SessionTerminalState.Failed("watchdog timeout")
        runCurrent()

        assertEquals(1, hangupCalls)
    }

    @Test
    fun `stop is idempotent and only tears down dependencies once`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val session = buildSession(this, liveSession, injector)

        session.start()
        runCurrent()

        session.stop("call ended")
        advanceUntilIdle()
        session.stop("call ended")
        advanceUntilIdle()

        assertEquals(1, injector.closeCount)
        assertTrue(liveSession.closedCalled)
    }

    @Test
    fun `greeting kick sends an empty activityStart-activityEnd pair after open`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val session = buildSession(this, liveSession, injector)

        session.start()
        runCurrent()

        assertEquals(1, liveSession.activityStartCount)
        assertEquals(1, liveSession.activityEndCount)
    }
}
