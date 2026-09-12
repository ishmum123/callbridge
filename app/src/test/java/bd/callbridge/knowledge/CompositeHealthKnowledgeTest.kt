package bd.callbridge.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeKnowledge(private val result: KnowledgeAnswer? , private val throws: Boolean = false) : HealthKnowledge {
    var callCount = 0
        private set

    override suspend fun lookup(question: String, callerContext: String?): KnowledgeAnswer {
        callCount++
        if (throws) throw RuntimeException("boom")
        return result ?: noInformationAvailable()
    }
}

class CompositeHealthKnowledgeTest {

    @Test
    fun `prefers exa when it returns a usable answer`() = runBlocking {
        val exa = FakeKnowledge(KnowledgeAnswer("exa answer", listOf("https://who.int/x"), "exa"))
        val openAi = FakeKnowledge(KnowledgeAnswer("openai answer", emptyList(), "openai"))
        val composite = CompositeHealthKnowledge(exa, openAi)

        val answer = composite.lookup("fever", null)
        assertEquals("exa", answer.provider)
        assertEquals(1, exa.callCount)
        assertEquals(0, openAi.callCount)
    }

    @Test
    fun `falls back to openai when exa returns no information`() = runBlocking {
        val exa = FakeKnowledge(noInformationAvailable())
        val openAi = FakeKnowledge(KnowledgeAnswer("openai answer", emptyList(), "openai"))
        val composite = CompositeHealthKnowledge(exa, openAi)

        val answer = composite.lookup("fever", null)
        assertEquals("openai", answer.provider)
        assertEquals(1, exa.callCount)
        assertEquals(1, openAi.callCount)
    }

    @Test
    fun `falls back to openai when exa throws unexpectedly`() = runBlocking {
        val exa = FakeKnowledge(null, throws = true)
        val openAi = FakeKnowledge(KnowledgeAnswer("openai answer", emptyList(), "openai"))
        val composite = CompositeHealthKnowledge(exa, openAi)

        val answer = composite.lookup("fever", null)
        assertEquals("openai", answer.provider)
    }

    @Test
    fun `returns no information when both fail`() = runBlocking {
        val exa = FakeKnowledge(noInformationAvailable())
        val openAi = FakeKnowledge(noInformationAvailable())
        val composite = CompositeHealthKnowledge(exa, openAi)

        val answer = composite.lookup("fever", null)
        assertEquals("none", answer.provider)
    }

    @Test
    fun `returns no information when both providers are null (disabled)`() = runBlocking {
        val composite = CompositeHealthKnowledge(null, null)
        val answer = composite.lookup("fever", null)
        assertEquals("none", answer.provider)
    }

    @Test
    fun `skips exa entirely when disabled, goes straight to openai`() = runBlocking {
        val openAi = FakeKnowledge(KnowledgeAnswer("openai answer", emptyList(), "openai"))
        val composite = CompositeHealthKnowledge(null, openAi)
        val answer = composite.lookup("fever", null)
        assertEquals("openai", answer.provider)
        assertEquals(1, openAi.callCount)
    }
}
