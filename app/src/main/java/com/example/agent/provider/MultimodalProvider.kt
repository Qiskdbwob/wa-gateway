package com.example.agent.provider

import com.example.agent.model.ModelRequest
import com.example.agent.model.ModelResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Priority 5 — multimodal request used by the media-understanding subagent.
 *
 * `imageBase64` entries are raw base64 (no data: prefix). The provider builds the
 * provider-specific payload from them plus [prompt].
 */
data class ImageUnderstandingRequest(
    val prompt: String,
    val imagesBase64: List<String>,
    val mimeType: String = "image/jpeg",
    val modelId: String? = null
)

/**
 * A provider that can look at images. The media-understanding subagent calls this when
 * the main model cannot. Implementations must describe what they actually see — the
 * result text is returned verbatim to the main agent.
 */
interface VisionProvider {
    val id: String
    val name: String

    suspend fun describeImage(request: ImageUnderstandingRequest): Result<String>
}

/**
 * OpenAI-compatible vision call (works for OpenAI gpt-4o*, OpenRouter, Gemini's OpenAI
 * compatibility layer, LM Studio with a vision model, ...).
 */
class OpenAiVisionProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultModel: String,
    private val client: OkHttpClient = visionHttpClient()
) : VisionProvider {

    override val id: String = "openai-vision"
    override val name: String = "OpenAI Compatible Vision"

    override suspend fun describeImage(request: ImageUnderstandingRequest): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("API key vision belum diisi"))
                }
                val base = baseUrl.trim().trimEnd('/')
                val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

                val content = JSONArray()
                content.put(JSONObject().put("type", "text").put("text", request.prompt))
                for (image in request.imagesBase64) {
                    val dataUrl = "data:${request.mimeType};base64,$image"
                    content.put(
                        JSONObject().put(
                            "type",
                            "image_url"
                        ).put("image_url", JSONObject().put("url", dataUrl))
                    )
                }

                val message = JSONObject()
                    .put("role", "user")
                    .put("content", content)

                val body = JSONObject()
                    .put("model", request.modelId?.takeIf { it.isNotBlank() } ?: defaultModel)
                    .put("messages", JSONArray().put(message))
                    .put("max_tokens", 1024)

                val httpRequest = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val detail = try {
                        JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: responseBody
                    } catch (_: Exception) {
                        responseBody.take(200)
                    }
                    return@withContext Result.failure(IOException("HTTP ${response.code}: $detail"))
                }

                val text = JSONObject(responseBody)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                if (text.isBlank()) {
                    Result.failure(IOException("Respons vision kosong"))
                } else {
                    Result.success(text.trim())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

/**
 * Google Gemini native API (generativelanguage.googleapis.com) using inline base64
 * image parts. Used when the user configures the vision subagent with a Gemini model
 * without the OpenAI-compat layer.
 */
class GeminiVisionProvider(
    private val apiKey: String,
    private val defaultModel: String = "gemini-2.0-flash",
    private val client: OkHttpClient = visionHttpClient()
) : VisionProvider {

    override val id: String = "gemini-vision"
    override val name: String = "Google Gemini Vision"

    override suspend fun describeImage(request: ImageUnderstandingRequest): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("API key Gemini belum diisi"))
                }
                val model = request.modelId?.takeIf { it.isNotBlank() } ?: defaultModel
                val endpoint =
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

                val parts = JSONArray()
                parts.put(JSONObject().put("text", request.prompt))
                for (image in request.imagesBase64) {
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", request.mimeType)
                                .put("data", image)
                        )
                    )
                }

                val body = JSONObject().put(
                    "contents",
                    JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
                )

                val httpRequest = Request.Builder()
                    .url(endpoint)
                    .addHeader("x-goog-api-key", apiKey.trim())
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val detail = try {
                        JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: responseBody
                    } catch (_: Exception) {
                        responseBody.take(200)
                    }
                    return@withContext Result.failure(IOException("HTTP ${response.code}: $detail"))
                }

                val candidates = JSONObject(responseBody).optJSONArray("candidates")
                val text = candidates?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.optJSONObject(0)?.optString("text").orEmpty()
                if (text.isBlank()) {
                    Result.failure(IOException("Respons Gemini kosong"))
                } else {
                    Result.success(text.trim())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

/**
 * Priority 6 — a no-network VisionProvider stub is deliberately NOT provided: when no
 * vision key is configured the media-understanding subagent reports that honestly
 * instead of pretending to understand the image.
 */

private fun visionHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()
