package com.neojou.aitranslate

import kotlinx.coroutines.CancellationException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.prefs.Preferences

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
    executeOllamaCheck(settings, ::ollamaSend)

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
        sendRequest(ollamaRequest(origin, path = "/api/tags", body = null, Duration.ofSeconds(OLLAMA_TAGS_TIMEOUT_SECONDS)))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return AiSettingsNotice.Failure(error.message ?: "Ollama is not reachable.")
    }
    if (tags.statusCode() !in 200..299) return AiSettingsNotice.Failure(ollamaHttpFailure(tags))
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
            ollamaRequest(
                origin,
                path = "/api/chat",
                body = ollamaChatBody(settings.ollamaModel, settings.ollamaNumCtx, includeThink),
                Duration.ofMinutes(OLLAMA_CHAT_TIMEOUT_MINUTES),
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return AiSettingsNotice.Failure(error.message ?: "Ollama is not reachable.")
    }
    if (response.statusCode() !in 200..299) return AiSettingsNotice.Failure(ollamaHttpFailure(response))
    return AiSettingsNotice.Success(AI_CHECK_LOADED)
}
