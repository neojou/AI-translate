package com.neojou.aitranslate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.prefs.Preferences
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAGS_TIMEOUT_SECONDS: Long = 10
private const val CHAT_TIMEOUT_MINUTES: Long = 5

private val httpClient: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(TAGS_TIMEOUT_SECONDS))
    .build()

private class PreferencesAiSettingsStore : AiSettingsStore {
    private val prefs: Preferences = Preferences.userRoot().node(AI_PREFS_NODE)

    override fun get(key: String, default: String): String = prefs.get(key, default)

    override fun put(key: String, value: String) {
        prefs.put(key, value)
        prefs.flush()
    }
}

private val preferencesStore by lazy { PreferencesAiSettingsStore() }

internal actual fun aiSettingsAvailable(): Boolean = true

internal actual fun loadAiSettings(): AiSettings = loadAiSettingsFrom(preferencesStore)

internal actual fun saveAiSettings(settings: AiSettings) {
    saveAiSettingsTo(preferencesStore, settings)
}

internal actual suspend fun checkInstalledModel(settings: AiSettings): AiSettingsNotice =
    executeOllamaCheck(settings, ::send)

/**
 * Confirms the model is already installed, then loads it with [AiSettings.ollamaNumCtx].
 *
 * A missing name does not call `/api/chat`, so Ollama is not asked to download it.
 * `think: false` is retried once without that field when the server rejects it.
 */
internal suspend fun executeOllamaCheck(
    settings: AiSettings,
    sendRequest: suspend (HttpRequest) -> HttpResponse<String>,
): AiSettingsNotice {
    val origin = when (val parsed = ollamaOrigin(settings.ollamaBaseUrl)) {
        is OllamaOrigin.Ok -> parsed.origin
        is OllamaOrigin.Invalid -> return AiSettingsNotice.Failure(parsed.message)
    }
    val tags = try {
        sendRequest(request(origin, path = "/api/tags", body = null, Duration.ofSeconds(TAGS_TIMEOUT_SECONDS)))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return AiSettingsNotice.Failure(error.message ?: "Ollama is not reachable.")
    }
    if (tags.statusCode() !in 200..299) return AiSettingsNotice.Failure(httpFailure(tags))
    if (!modelInstalled(ollamaModelNames(tags.body()), settings.ollamaModel)) {
        return AiSettingsNotice.Failure("Model \"${settings.ollamaModel}\" is not installed.")
    }
    val first = postChat(origin, settings, includeThink = true, sendRequest)
    if (first !is AiSettingsNotice.Failure || !first.message.contains("think", ignoreCase = true)) {
        return first
    }
    return postChat(origin, settings, includeThink = false, sendRequest)
}

private suspend fun postChat(
    origin: String,
    settings: AiSettings,
    includeThink: Boolean,
    sendRequest: suspend (HttpRequest) -> HttpResponse<String>,
): AiSettingsNotice {
    val response = try {
        sendRequest(
            request(
                origin,
                path = "/api/chat",
                body = ollamaChatBody(settings.ollamaModel, settings.ollamaNumCtx, includeThink),
                Duration.ofMinutes(CHAT_TIMEOUT_MINUTES),
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return AiSettingsNotice.Failure(error.message ?: "Ollama is not reachable.")
    }
    if (response.statusCode() !in 200..299) return AiSettingsNotice.Failure(httpFailure(response))
    return AiSettingsNotice.Success(AI_CHECK_LOADED)
}

private fun request(origin: String, path: String, body: String?, timeout: Duration): HttpRequest {
    val builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(timeout)
    return if (body == null) {
        builder.GET().build()
    } else {
        builder
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }
}

private suspend fun send(request: HttpRequest): HttpResponse<String> =
    suspendCancellableCoroutine { continuation ->
        val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        future.whenComplete { response, error ->
            if (continuation.isCancelled) return@whenComplete
            if (error != null) {
                val cause = (error as? CompletionException)?.cause ?: error
                continuation.resumeWithException(cause)
            } else {
                continuation.resume(response)
            }
        }
        continuation.invokeOnCancellation { future.cancel(true) }
    }

private fun httpFailure(response: HttpResponse<String>): String {
    val snippet = response.body().trim().take(500)
    return if (snippet.isEmpty()) {
        "Ollama HTTP ${response.statusCode()}"
    } else {
        "Ollama HTTP ${response.statusCode()}: $snippet"
    }
}
