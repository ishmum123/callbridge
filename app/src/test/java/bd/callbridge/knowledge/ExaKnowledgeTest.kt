package bd.callbridge.knowledge

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExaKnowledgeTest {
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
        ExaKnowledge(apiKey = apiKey, timeoutMs = timeoutMs, searchUrl = server.url("/search").toString())

    @Test
    fun `blank api key returns no information without any network call`() = runBlocking {
        val answer = ExaKnowledge(apiKey = "", searchUrl = server.url("/search").toString()).lookup("fever in infant", null)
        assertEquals("none", answer.provider)
        assertTrue(answer.sources.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `successful response returns top 3 highlights and sources`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "results": [
                    {"title": "NHS Paracetamol", "url": "https://www.nhs.uk/a", "highlights": ["Give 7.5ml for 2-3 years"]},
                    {"title": "WHO Fever Guide", "url": "https://who.int/b", "highlights": ["Seek care if fever persists"]},
                    {"title": "Mayo Clinic", "url": "https://mayoclinic.org/c", "highlights": ["Monitor temperature every 4 hours"]},
                    {"title": "Extra", "url": "https://example.com/d", "highlights": ["should not appear, only top 3"]}
                  ]
                }
                """.trimIndent()
            )
        )

        val answer = knowledge().lookup("paracetamol dose for 2 year old", "কলার একজন কৃষক")
        assertEquals("exa", answer.provider)
        assertEquals(3, answer.sources.size)
        assertEquals("https://www.nhs.uk/a", answer.sources[0])
        assertTrue(answer.answerEn.contains("Give 7.5ml for 2-3 years"))

        val sentRequest = server.takeRequest()
        assertEquals("test-key", sentRequest.getHeader("x-api-key"))
        val sentBody = sentRequest.body.readUtf8()
        assertTrue("expected includeDomains in request body: $sentBody", sentBody.contains("nhs.uk"))
        assertTrue("expected caller context folded into query: $sentBody", sentBody.contains("কৃষক"))
    }

    @Test
    fun `empty results returns no information available`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results": []}"""))
        val answer = knowledge().lookup("what causes hiccups", null)
        assertEquals("none", answer.provider)
    }

    @Test
    fun `http error returns no information available, never throws`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))
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
            MockResponse().setBody("""{"results":[{"title":"t","url":"https://who.int/x","highlights":["h"]}]}""")
                .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
        )
        val answer = knowledge(timeoutMs = 500L).lookup("slow question", null)
        assertEquals("none", answer.provider)
    }
}
