package com.groqandroid

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GroqApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GroqApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val chatApiUrl = server.url("/chat/completions").toString()
        client = GroqApiClient(
            apiKey = "test-key",
            chatApiUrl = chatApiUrl
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.shutdown()
    }

    @Test
    fun postProcessText_sendsPromptAndTranscript_returnsCleanedResponse() = runTest {
        val jsonResponse = """
            {
                "choices": [
                    {
                        "message": {
                            "content": "Cleaned formatted transcript text."
                        }
                    }
                ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val result = client.postProcessText("raw transcript text", "Format nicely.")
        assertEquals("Cleaned formatted transcript text.", result)

        val recordedRequest = server.takeRequest()
        val requestBody = recordedRequest.body.readUtf8()
        org.junit.Assert.assertTrue(requestBody.contains("Format nicely."))
        org.junit.Assert.assertTrue(requestBody.contains("<<TRANSCRIPT>>"))
        org.junit.Assert.assertTrue(requestBody.contains("raw transcript text"))
        org.junit.Assert.assertTrue(requestBody.contains("\"reasoning_effort\":\"none\""))
    }

    @Test
    fun postProcessText_removesAccidentalTagsFromResponse() = runTest {
        val jsonResponse = """
            {
                "choices": [
                    {
                        "message": {
                            "content": "<<TRANSCRIPT>> Accidental tags included. <</TRANSCRIPT>>"
                        }
                    }
                ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val result = client.postProcessText("input text", "prompt")
        assertEquals("Accidental tags included.", result)
    }

    @Test
    fun postProcessText_serverErrorOrAuthError_returnsFallbackText() = runTest {
        // Enqueue 401 for primary model and all fallback models
        for (i in 0..10) {
            server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        }

        val result = client.postProcessText("fallback transcript", "prompt")
        assertEquals("fallback transcript", result)
    }
}
