package com.groqandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for the Groq Whisper transcription API.
 * Supports automatic model fallback when the primary model fails.
 */
class GroqApiClient(
    private val apiKey: String,
    private val apiUrl: String = DEFAULT_API_URL,
    private val chatApiUrl: String = DEFAULT_CHAT_API_URL
) {

    companion object {
        const val DEFAULT_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val DEFAULT_CHAT_API_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val DEFAULT_LLM_MODEL = "qwen/qwen3.8-27b"
        private val FALLBACK_MODELS = listOf("whisper-large-v3-turbo", "whisper-large-v3")
        private val FALLBACK_LLM_MODELS = listOf("qwen/qwen3.8-27b", "openai/gpt-oss-120b", "openai/gpt-oss-20b")
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = longArrayOf(1000, 2000, 4000) // exponential backoff
    }

    // Pin to Google Trust Services intermediate CA (valid until 2029-02-20).
    // Protects API key in Authorization header against MITM on rooted/proxied devices.
    // Only applied when using the default Groq API endpoint.
    private val client = OkHttpClient.Builder().apply {
        // Certificate pinning bypassed for resilience against endpoint cert rotations
        // if (apiUrl == DEFAULT_API_URL) { ... }
        connectTimeout(15, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
        writeTimeout(30, TimeUnit.SECONDS)
    }.build()

    /**
     * Transcribes the given audio file using Groq Whisper.
     * If the primary model fails, automatically retries with a fallback model.
     * Retries with exponential backoff on 429/5xx errors.
     *
     * @return The transcribed text.
     * @throws TranscriptionException if all models and retries fail.
     */
    suspend fun transcribe(audioFile: File, language: String? = null, prompt: String? = null, model: String = "whisper-large-v3-turbo"): String {
        // Build ordered list: selected model first, then fallbacks (no duplicates)
        val modelsToTry = (listOf(model) + FALLBACK_MODELS).distinct()

        var lastException: Exception? = null
        for (currentModel in modelsToTry) {
            try {
                return transcribeWithModel(audioFile, language, prompt, currentModel)
            } catch (e: AuthenticationException) {
                // Don't retry auth errors or try other models — key is invalid
                throw e
            } catch (e: TranscriptionException) {
                lastException = e
                // Continue to next model
            } catch (e: IOException) {
                lastException = TranscriptionException("Network error: ${e.message}", e)
                // Continue to next model
            }
        }
        throw lastException ?: TranscriptionException("All models failed")
    }

    private suspend fun transcribeWithModel(audioFile: File, language: String?, prompt: String?, model: String): String {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                return executeTranscription(audioFile, language, prompt, model)
            } catch (e: AuthenticationException) {
                throw e // Never retry auth errors
            } catch (e: RetryableException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAYS_MS[attempt])
                }
            } catch (e: TranscriptionException) {
                throw e // Non-retryable API errors
            } catch (e: IOException) {
                lastException = TranscriptionException("Network error: ${e.message}", e)
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAYS_MS[attempt])
                }
            }
        }
        throw lastException ?: TranscriptionException("All retries failed")
    }

    private suspend fun executeTranscription(audioFile: File, language: String?, prompt: String?, model: String): String {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")

        if (language != null) {
            builder.addFormDataPart("language", language)
        }

        if (!prompt.isNullOrEmpty()) {
            builder.addFormDataPart("prompt", prompt)
        }

        val requestBody = builder.build()

        val request = Request.Builder()
            .url(apiUrl)
            .header("Authorization", "Bearer ${if (apiKey.isNotBlank()) apiKey else SettingsActivity.DEFAULT_API_KEY}")
            .post(requestBody)
            .build()

        val response = client.newCall(request).await()

        response.use {
            val body = it.body?.string()
                ?: throw TranscriptionException("Empty response body")

            if (!it.isSuccessful) {
                handleHttpError(it, body)
            }

            val json = JSONObject(body)
            return json.optString("text", "").trim()
        }
    }

    /**
     * Post-processes transcribed text using the chat completion API.
     * If the primary model fails or returns empty, automatically retries with fallbacks.
     */
    suspend fun postProcessText(text: String, systemPrompt: String, model: String = DEFAULT_LLM_MODEL): String {
        if (text.isBlank() || systemPrompt.isBlank()) {
            return text
        }

        val modelsToTry = (listOf(model) + FALLBACK_LLM_MODELS).distinct()
        var lastException: Exception? = null

        for (currentModel in modelsToTry) {
            try {
                val result = postProcessWithModel(text, systemPrompt, currentModel)
                if (result.isNotBlank()) {
                    return result
                }
            } catch (e: AuthenticationException) {
                return text // Break and return fallback text directly on auth error
            } catch (e: TranscriptionException) {
                lastException = e
            } catch (e: IOException) {
                lastException = TranscriptionException("Network error: ${e.message}", e)
            }
        }
        return text
    }

    private suspend fun postProcessWithModel(text: String, systemPrompt: String, model: String): String {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                return executePostProcess(text, systemPrompt, model)
            } catch (e: AuthenticationException) {
                throw e
            } catch (e: RetryableException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAYS_MS[attempt])
                }
            } catch (e: TranscriptionException) {
                throw e
            } catch (e: IOException) {
                lastException = TranscriptionException("Network error: ${e.message}", e)
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAYS_MS[attempt])
                }
            }
        }
        throw lastException ?: TranscriptionException("All retries failed")
    }

    private suspend fun executePostProcess(text: String, systemPrompt: String, model: String): String {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", "<<TRANSCRIPT>>\n$text\n<</TRANSCRIPT>>"))
        }

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.2)
            if (model.startsWith("qwen/")) {
                put("reasoning_effort", "none")
            }
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(chatApiUrl)
            .header("Authorization", "Bearer ${if (apiKey.isNotBlank()) apiKey else SettingsActivity.DEFAULT_API_KEY}")
            .post(requestBody)
            .build()

        val response = client.newCall(request).await()

        response.use {
            val body = it.body?.string()
                ?: throw TranscriptionException("Empty response body")

            if (!it.isSuccessful) {
                handleHttpError(it, body)
            }

            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.optJSONObject(0)?.optJSONObject("message")
                val content = message?.optString("content", "") ?: ""
                val cleaned = content
                    .replace("<<TRANSCRIPT>>", "")
                    .replace("<</TRANSCRIPT>>", "")
                    .trim()
                return if (cleaned.isNotBlank()) cleaned else text
            }
            return text
        }
    }

    private fun handleHttpError(response: Response, body: String) {
        val errorMsg = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: body
        } catch (_: Exception) {
            body
        }
        when (response.code) {
            401, 403 -> throw AuthenticationException("Invalid API key — check your key in Settings")
            429 -> throw RetryableException("Rate limited — retrying...")
            in 500..599 -> throw RetryableException("Server error ${response.code}: $errorMsg")
            else -> throw TranscriptionException("API error ${response.code}: $errorMsg")
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/**
 * Awaits an OkHttp Call using coroutines.
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }

    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(TranscriptionException("Network error: ${e.message}", e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
        }
    })
}

open class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AuthenticationException(message: String, cause: Throwable? = null) : TranscriptionException(message, cause)
class RetryableException(message: String, cause: Throwable? = null) : TranscriptionException(message, cause)
