package bd.callbridge.service

import bd.callbridge.audio.AudioPipeline
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every call so tests can assert on interaction without touching Android APIs. Also
 *  records a single ordered [callLog] across every method (not just per-method counters) so
 *  ordering-sensitive tests (activityStart before the first chunk, etc.) can assert sequence,
 *  not just counts. */
private class FakeLiveSession : LiveSession {
    val callLog = mutableListOf<String>()
    val sentAudio = mutableListOf<ShortArray>()
    val textTurns = mutableListOf<String>()
    var openCalled = false
    var closedCalled = false
    var activityStartCount = 0
    var activityEndCount = 0
    var interruptCount = 0

    /** When set, [open] suspends on this instead of returning immediately — used to simulate a
     *  slow/hanging network open for the "stop while opening" test. */
    var openGate: CompletableDeferred<Unit>? = null

    val eventsFlow = MutableSharedFlow<LiveSessionEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    val terminal = MutableStateFlow<SessionTerminalState>(SessionTerminalState.Open)

    override suspend fun open(systemPrompt: String, profile: CallerProfile) {
        openGate?.await()
        openCalled = true
        callLog += "open"
    }

    override fun sendAudio(pcm: ShortArray) {
        sentAudio.add(pcm)
        callLog += "audio:${pcm.size}"
    }

    override fun sendTextTurn(text: String) {
        textTurns.add(text)
        callLog += "textTurn"
    }

    override val droppedAudioChunkCount: Long = 0L

    override fun sendActivityStart() {
        activityStartCount++
        callLog += "activityStart"
    }

    override fun sendActivityEnd() {
        activityEndCount++
        callLog += "activityEnd"
    }

    override fun interrupt() {
        interruptCount++
        callLog += "interrupt"
    }

    override val events: Flow<LiveSessionEvent> get() = eventsFlow

    override val terminalState: StateFlow<SessionTerminalState> get() = terminal

    override suspend fun close() {
        closedCalled = true
        callLog += "close"
    }
}

private class FakeInjector(
    override val route: InjectorRoute = InjectorRoute.LOOPBACK,
    private val negotiatedRateHz: Int? = 16_000,
) : Injector {
    var openCount = 0
    var closeCount = 0
    var flushCount = 0
    var opened = false
    val written = mutableListOf<ShortArray>()

    override val openSampleRateHz: Int? get() = if (opened) negotiatedRateHz else null

    override fun open() {
        openCount++
        opened = true
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
        pipelineEvents: Flow<AudioPipeline.PipelineEvent> = emptyFlow(),
        onHangupRequested: suspend () -> Unit = {},
        statuses: MutableList<BridgeStatus> = mutableListOf(),
        drainTimeoutMs: Long = 4_000L,
    ) = BridgeSession(
        liveSession = liveSession,
        injector = injector,
        transcriptRecorder = recorder,
        pipelineEvents = pipelineEvents,
        systemPrompt = "prompt",
        callerProfile = profile,
        vadMode = VadMode.LOCAL_VAD,
        // backgroundScope (not the test body's own scope): its children are auto-cancelled at
        // the end of runTest, so an infinite collector (e.g. on a never-completing VAD-events
        // SharedFlow) doesn't hang the test waiting for it to finish.
        scope = scope.backgroundScope,
        onHangupRequested = onHangupRequested,
        onStatus = { statuses.add(it) },
        drainTimeoutMs = drainTimeoutMs,
        // Dispatchers.IO would be a real thread pool the virtual-time test dispatcher can't
        // control; keep everything on the test dispatcher so runCurrent()/advanceUntilIdle() see
        // every side effect deterministically.
        injectorDispatcher = StandardTestDispatcher(scope.testScheduler),
    )

    @Test
    fun `AudioOut is resampled to the injector's negotiated rate and written`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector(negotiatedRateHz = 16_000)
        val session = buildSession(this, liveSession, injector)

        session.start()
        runCurrent()
        assertTrue(liveSession.openCalled)

        // 2400 samples @ 24kHz should resample to ~1600 samples @ 16kHz (2/3 ratio).
        val pcm = ShortArray(2_400) { (it % 100).toShort() }
        liveSession.eventsFlow.emit(LiveSessionEvent.AudioOut(pcm, sampleRate = 24_000))
        runCurrent()

        val totalWrittenSamples = injector.written.sumOf { it.size }
        assertTrue(
            "expected ~1600 samples after 24k->16k resampling, got $totalWrittenSamples",
            totalWrittenSamples in 1_400..1_800,
        )
    }

    @Test
    fun `injector rate fallback to 8kHz resamples to 8kHz, not a hardcoded 16kHz`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector(negotiatedRateHz = 8_000)
        val session = buildSession(this, liveSession, injector)

        session.start()
        runCurrent()

        val pcm = ShortArray(2_400) { (it % 100).toShort() }
        liveSession.eventsFlow.emit(LiveSessionEvent.AudioOut(pcm, sampleRate = 24_000))
        runCurrent()

        val totalWrittenSamples = injector.written.sumOf { it.size }
        // 2400 @ 24kHz -> ~800 @ 8kHz (1/3 ratio), not ~1600 (which would be the 16kHz-assuming bug).
        assertTrue(
            "expected ~800 samples after 24k->8k resampling, got $totalWrittenSamples",
            totalWrittenSamples in 600..1_000,
        )
    }

    @Test
    fun `pipeline events drive sendAudio and activityStart-End in strict encounter order`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val chunk1 = ShortArray(10) { 1 }
        val chunk2 = ShortArray(10) { 2 }
        val tail = ShortArray(10) { 3 }
        val events = flowOf(
            AudioPipeline.PipelineEvent.Vad(VadGate.Event.SpeechStarted),
            AudioPipeline.PipelineEvent.Chunk(chunk1),
            AudioPipeline.PipelineEvent.Chunk(chunk2),
            AudioPipeline.PipelineEvent.Chunk(tail),
            AudioPipeline.PipelineEvent.Vad(VadGate.Event.SpeechEnded),
        )
        val session = buildSession(this, liveSession, injector, pipelineEvents = events)

        session.start()
        runCurrent()

        assertEquals(listOf(chunk1, chunk2, tail), liveSession.sentAudio)
        // activityStart must precede the first audio chunk, activityEnd must follow the last one.
        val relevant = liveSession.callLog.filter { it == "activityStart" || it.startsWith("audio:") || it == "activityEnd" }
        assertEquals(listOf("activityStart", "audio:10", "audio:10", "audio:10", "activityEnd"), relevant)
    }

    @Test
    fun `barge-in on caller speech while model is speaking interrupts and flushes, without a duplicate activityStart`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val vadEvents = MutableSharedFlow<AudioPipeline.PipelineEvent>(extraBufferCapacity = 8)
        val session = buildSession(this, liveSession, injector, pipelineEvents = vadEvents)

        session.start()
        runCurrent()

        liveSession.eventsFlow.emit(LiveSessionEvent.AudioOut(ShortArray(160), sampleRate = 24_000))
        runCurrent()

        vadEvents.emit(AudioPipeline.PipelineEvent.Vad(VadGate.Event.SpeechStarted))
        runCurrent()

        assertEquals(1, liveSession.interruptCount)
        assertEquals(1, injector.flushCount)
        // interrupt() (LOCAL_VAD) already sends the manual activityStart signal; the barge-in
        // branch must not also call sendActivityStart() itself.
        assertEquals(0, liveSession.activityStartCount)
    }

    @Test
    fun `no barge-in interrupt when model is not speaking, and activityStart is sent once`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val vadEvents = MutableSharedFlow<AudioPipeline.PipelineEvent>(extraBufferCapacity = 8)
        val session = buildSession(this, liveSession, injector, pipelineEvents = vadEvents)

        session.start()
        runCurrent()

        vadEvents.emit(AudioPipeline.PipelineEvent.Vad(VadGate.Event.SpeechStarted))
        runCurrent()

        assertEquals(0, liveSession.interruptCount)
        assertEquals(0, injector.flushCount)
        assertEquals(1, liveSession.activityStartCount)
    }

    @Test
    fun `stale audio after barge-in is suppressed until Interrupted or TurnComplete`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val vadEvents = MutableSharedFlow<AudioPipeline.PipelineEvent>(extraBufferCapacity = 8)
        val session = buildSession(this, liveSession, injector, pipelineEvents = vadEvents)

        session.start()
        runCurrent()

        liveSession.eventsFlow.emit(LiveSessionEvent.AudioOut(ShortArray(160) { 9 }, sampleRate = 24_000))
        runCurrent()
        assertEquals(1, injector.written.size)

        vadEvents.emit(AudioPipeline.PipelineEvent.Vad(VadGate.Event.SpeechStarted)) // barge-in
        runCurrent()

        // Stale audio from the interrupted turn must not reach the injector.
        liveSession.eventsFlow.emit(LiveSessionEvent.AudioOut(ShortArray(160) { 9 }, sampleRate = 24_000))
        runCurrent()
        assertEquals("stale post-barge-in audio must be dropped", 1, injector.written.size)

        liveSession.eventsFlow.emit(LiveSessionEvent.Interrupted)
        runCurrent()

        // New turn's audio (after Interrupted) must flow normally again.
        liveSession.eventsFlow.emit(LiveSessionEvent.AudioOut(ShortArray(160) { 9 }, sampleRate = 24_000))
        runCurrent()
        assertEquals(2, injector.written.size)
    }

    @Test
    fun `hangup phrase drains until TurnComplete before triggering onHangupRequested`() = runTest(StandardTestDispatcher()) {
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
        runCurrent()
        // The phrase was detected but the turn hasn't completed yet — must NOT hang up yet.
        assertEquals(0, hangupCalls)

        liveSession.eventsFlow.emit(LiveSessionEvent.TurnComplete)
        runCurrent()

        assertEquals(1, hangupCalls)
        assertTrue(statuses.any { it.phase == BridgePhase.ENDING })
    }

    @Test
    fun `hangup phrase falls back to the drain timeout if TurnComplete never arrives`() = kotlinx.coroutines.runBlocking {
        // Real dispatchers + a short real timeout here, not runTest's virtual-time dispatcher —
        // same deliberate choice GeminiLiveSessionTest's watchdog tests make (see
        // docs/gemini-live.md): withTimeoutOrNull's internal cancellation timer needs a `Delay`
        // driven consistently by whichever scheduler is advancing it, and a real short duration
        // is simpler to get right here than coordinating virtual time across two independently
        // constructed TestDispatcher instances backing the same CoroutineScope.
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        var hangupCalls = 0
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = BridgeSession(
            liveSession = liveSession,
            injector = injector,
            transcriptRecorder = newRecorder(),
            pipelineEvents = emptyFlow(),
            systemPrompt = "prompt",
            callerProfile = profile,
            vadMode = VadMode.LOCAL_VAD,
            scope = realScope,
            onHangupRequested = { hangupCalls++ },
            drainTimeoutMs = 150L,
        )

        session.start()
        delay(50)

        liveSession.eventsFlow.emit(LiveSessionEvent.OutputTranscript("আল্লাহ হাফেজ, ভালো থাকবেন।"))
        delay(50)
        assertEquals(0, hangupCalls)

        withTimeoutOrNull(2_000L) { while (hangupCalls == 0) delay(20) }
        assertEquals(1, hangupCalls)
        realScope.cancel()
    }

    @Test
    fun `double hangup guard - closing phrase and terminal Failed both firing invokes onHangupRequested exactly once`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        var hangupCalls = 0
        val session = buildSession(this, liveSession, injector, onHangupRequested = { hangupCalls++ }, drainTimeoutMs = 200L)

        session.start()
        runCurrent()

        liveSession.eventsFlow.emit(LiveSessionEvent.OutputTranscript("আল্লাহ হাফেজ, ভালো থাকবেন।"))
        liveSession.terminal.value = SessionTerminalState.Failed("watchdog timeout")
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, hangupCalls)
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
    fun `stop while open() is still in flight cancels it promptly and tears down cleanly`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        liveSession.openGate = CompletableDeferred() // never completes on its own
        val injector = FakeInjector()
        val session = buildSession(this, liveSession, injector)

        session.start()
        runCurrent()
        // open() is parked on openGate; start() itself has already returned (it doesn't await
        // the network open), and the session hasn't gone ACTIVE yet.
        assertTrue(!liveSession.openCalled)

        session.stop("call ended before setup completed")
        advanceUntilIdle()

        // Teardown must complete (not hang waiting out the never-completing open()) and must
        // still close the injector/session.
        assertEquals(1, injector.closeCount)
        assertTrue(liveSession.closedCalled)
        // The in-flight open() must never have been allowed to "complete" and proceed to mark
        // the session ACTIVE / send the greeting after stop() already tore it down.
        assertTrue(liveSession.textTurns.isEmpty())
    }

    @Test
    fun `greeting kick sends a text turn after open, once ACTIVE`() = runTest(StandardTestDispatcher()) {
        val liveSession = FakeLiveSession()
        val injector = FakeInjector()
        val session = buildSession(this, liveSession, injector)

        session.start()
        runCurrent()

        assertEquals(1, liveSession.textTurns.size)
        assertTrue(liveSession.activityStartCount == 0 && liveSession.activityEndCount == 0)
    }
}
