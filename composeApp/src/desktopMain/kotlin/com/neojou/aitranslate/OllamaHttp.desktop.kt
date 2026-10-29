package com.neojou.aitranslate

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val OLLAMA_TAGS_TIMEOUT_SECONDS: Long = 10
internal const val OLLAMA_CHAT_TIMEOUT_MINUTES: Long = 5

internal val ollamaHttpClient: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(OLLAMA_TAGS_TIMEOUT_SECONDS))
    .build()

internal fun ollamaRequest(origin: String, path: String, body: String?, timeout: Duration): HttpRequest {
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

internal suspend fun ollamaSend(request: HttpRequest): HttpResponse<String> =
    suspendCancellableCoroutine { continuation ->
        val future = ollamaHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
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

internal fun ollamaHttpFailure(response: HttpResponse<String>): String {
    val snippet = response.body().trim().take(500)
    return if (snippet.isEmpty()) {
        "Ollama HTTP ${response.statusCode()}"
    } else {
        "Ollama HTTP ${response.statusCode()}: $snippet"
    }
}
