package bd.callbridge.gemini

import bd.callbridge.store.CallDirection
import bd.callbridge.store.CallEntity
import bd.callbridge.store.TurnEntity
import bd.callbridge.store.TurnRole
import bd.callbridge.store.dao.CallDao
import bd.callbridge.store.dao.TurnDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory fakes so this test stays a pure JVM test (no Room/Robolectric needed). */
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
class TranscriptRecorderTest {
    private val callId = 42L
    private fun newCall() = CallEntity(id = callId, number = "017", direction = CallDirection.INBOUND, startedAt = 0L)

    @Test
    fun `flushes accumulated transcript on turnComplete as one turn each`() = runTest {
        val turnDao = FakeTurnDao()
        val callDao = FakeCallDao(newCall())
        val recorder = TranscriptRecorder(callId, turnDao, callDao, startedAtMs = 0L, nowMs = { 5_000L })

        recorder.handle(LiveSessionEvent.InputTranscript("ধান "))
        recorder.handle(LiveSessionEvent.InputTranscript("চাষ কীভাবে করব?"))
        recorder.handle(LiveSessionEvent.OutputTranscript("ধান চাষের "))
        recorder.handle(LiveSessionEvent.OutputTranscript("জন্য..."))
        recorder.handle(LiveSessionEvent.TurnComplete)

        assertEquals(2, turnDao.inserted.size)
        val callerTurn = turnDao.inserted.first { it.role == TurnRole.CALLER }
        val assistantTurn = turnDao.inserted.first { it.role == TurnRole.ASSISTANT }
        assertEquals("ধান চাষ কীভাবে করব?", callerTurn.text)
        assertEquals("ধান চাষের জন্য...", assistantTurn.text)
        assertEquals(5_000L, callerTurn.tMs)
    }

    @Test
    fun `detects HANGUP token and strips it from the stored turn text`() = runTest {
        val turnDao = FakeTurnDao()
        val callDao = FakeCallDao(newCall())
        val recorder = TranscriptRecorder(callId, turnDao, callDao, startedAtMs = 0L)

        var hungUp = false
        val collectJob = launch { recorder.hangupRequested.collect { hungUp = true } }
        runCurrent()

        recorder.handle(LiveSessionEvent.OutputTranscript("আচ্ছা, বিদায়। "))
        recorder.handle(LiveSessionEvent.OutputTranscript(SystemPromptBuilder.HANGUP_TOKEN))
        recorder.handle(LiveSessionEvent.TurnComplete)
        runCurrent()

        assertTrue(hungUp)
        val assistantTurn = turnDao.inserted.first { it.role == TurnRole.ASSISTANT }
        assertEquals("আচ্ছা, বিদায়।", assistantTurn.text)
        collectJob.cancel()
    }

    @Test
    fun `finish writes seconds and cost onto the call row`() = runTest {
        val turnDao = FakeTurnDao()
        val callDao = FakeCallDao(newCall())
        val recorder = TranscriptRecorder(callId, turnDao, callDao, startedAtMs = 0L, nowMs = { 120_000L })

        // 16kHz input, 24kHz output: 60s worth of samples each.
        repeat(60) { recorder.onAudioSent(ShortArray(16_000)) }
        recorder.handle(LiveSessionEvent.AudioOut(ShortArray(24_000 * 60)))

        recorder.finish(endReason = "caller_hangup")

        val call = callDao.stored
        assertEquals(60.0, call.inputSeconds, 1e-6)
        assertEquals(60.0, call.outputSeconds, 1e-6)
        assertEquals(CostModel.estimateUsd(60.0, 60.0), call.estCostUsd, 1e-9)
        assertEquals("caller_hangup", call.endReason)
        assertEquals(120_000L, call.endedAt)
    }
}
