package bd.callbridge.knowledge

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiKnowledgeTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun knowledge(apiKey: String = "test-key", timeoutMs: Long = 2_000L) =
        OpenAiKnowledge(apiKey = apiKey, timeoutMs = timeoutMs, chatCompletionsUrl = server.url("/v1/chat/completions").toString())

    @Test
    fun `blank api key returns no information without any network call`() = runBlocking {
        val answer = OpenAiKnowledge(apiKey = "", chatCompletionsUrl = server.url("/x").toString()).lookup("cough in child", null)
        assertEquals("none", answer.provider)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `successful completion returns content as answer`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"choices": [{"message": {"role": "assistant", "content": "Give 10-15mg/kg paracetamol every 4-6 hours."}}]}
                """.trimIndent()
            )
        )

        val answer = knowledge().lookup("paracetamol dose for 2 year old", "কলার একজন কৃষক")
        assertEquals("openai", answer.provider)
        assertTrue(answer.answerEn.contains("10-15mg/kg"))
        assertEquals(1, answer.sources.size)

        val sentRequest = server.takeRequest()
        assertEquals("Bearer test-key", sentRequest.getHeader("Authorization"))
        val sentBody = sentRequest.body.readUtf8()
        assertTrue("expected model field in body: $sentBody", sentBody.contains("gpt-4o-mini"))
        assertTrue("expected max_tokens (snake_case) in body: $sentBody", sentBody.contains("max_tokens"))
        assertTrue("expected caller context folded into user content: $sentBody", sentBody.contains("কৃষক"))
    }

    @Test
    fun `empty choices returns no information available`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices": []}"""))
        val answer = knowledge().lookup("what causes hiccups", null)
        assertEquals("none", answer.provider)
    }

    @Test
    fun `http error returns no information available, never throws`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val answer = knowledge().lookup("diarrhea in toddler", null)
        assertEquals("none", answer.provider)
    }

    @Test
    fun `malformed json returns no information available, never throws`() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))
        val answer = knowledge().lookup("chest pain", null)
        assertEquals("none", answer.provider)
    }

    @Test
    fun `slow response beyond timeout returns no information available`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
                .setBodyDelay(3, TimeUnit.SECONDS)
        )
        val answer = knowledge(timeoutMs = 500L).lookup("slow question", null)
        assertEquals("none", answer.provider)
    }
}
