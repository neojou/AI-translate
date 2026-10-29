package com.neojou.aitranslate

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiSettingsTest {
    @Test
    fun loopbackOriginDropsV1AndRejectsOtherHosts() {
        assertEquals(
            "http://127.0.0.1:11434",
            (ollamaOrigin("http://127.0.0.1:11434/v1/") as OllamaOrigin.Ok).origin,
        )
        assertEquals(
            "http://localhost:11434",
            (ollamaOrigin("HTTP://LocalHost:11434/v1") as OllamaOrigin.Ok).origin,
        )
        assertEquals(
            "https://127.0.0.1",
            (ollamaOrigin("https://127.0.0.1/v1") as OllamaOrigin.Ok).origin,
        )
        assertEquals(AI_HOST_LOOPBACK, (ollamaOrigin("http://example.com:11434") as OllamaOrigin.Invalid).message)
        assertEquals(AI_URL_INVALID, (ollamaOrigin("ftp://127.0.0.1:11434") as OllamaOrigin.Invalid).message)
        assertEquals(AI_URL_INVALID, (ollamaOrigin("http://127.0.0.1:11434/v1?x=1") as OllamaOrigin.Invalid).message)
        assertEquals(AI_HOST_LOOPBACK, (ollamaOrigin("http://[::1]:11434") as OllamaOrigin.Invalid).message)
    }

    @Test
    fun modelNameMatchesTagButNotALongerName() {
        val names = ollamaModelNames("""{"models":[{"name":"gemma4-64k:latest"},{"name":"llama3.2:latest"}]}""")
        assertEquals(listOf("gemma4-64k:latest", "llama3.2:latest"), names)
        assertTrue(modelInstalled(names, "gemma4-64k"))
        assertTrue(modelInstalled(names, "gemma4-64k:latest"))
        assertFalse(modelInstalled(names, "gemma"))
        assertFalse(modelInstalled(listOf("gemma4-64k-extra:latest"), "gemma4-64k"))
    }

    @Test
    fun saveUsesDefaultsAndRejectsBadNumCtx() {
        val store = MemoryAiSettingsStore()
        val saved = prepareSave("  ", " ", "8192")
        assertIs<PreparedSettings.Ok>(saved)
        saveAiSettingsTo(store, saved.settings)
        assertEquals(AiSettings.defaults.copy(ollamaNumCtx = 8192), loadAiSettingsFrom(store))

        store.put(AI_KEY_NUM_CTX, "nope")
        store.put(AI_KEY_URL, " ")
        store.put(AI_KEY_MODEL, "")
        assertEquals(AiSettings.defaults, loadAiSettingsFrom(store))

        val rejected = prepareSave("http://127.0.0.1:11434/v1", "gemma4-64k", "0")
        assertEquals(AI_NUM_CTX_ERROR, (rejected as PreparedSettings.Rejected).message)
        val publicHost = prepareSave("http://example.com", "gemma4-64k", "1024")
        assertEquals(AI_HOST_LOOPBACK, (publicHost as PreparedSettings.Rejected).message)
    }

    @Test
    fun checkRejectsBlankModelAndBadNumCtxWithoutNetwork() {
        val blankModel = runBlocking {
            checkAiSettingsFields("http://127.0.0.1:11434/v1", "  ", "1024")
        }
        assertEquals(AiSettingsNotice.Failure(AI_MODEL_EMPTY), blankModel)
        val badCtx = runBlocking {
            checkAiSettingsFields("http://127.0.0.1:11434/v1", "gemma4-64k", "abc")
        }
        assertEquals(AiSettingsNotice.Failure(AI_NUM_CTX_ERROR), badCtx)
    }

    @Test
    fun checkStripsV1LoadsInstalledModelAndUnloads() = runBlocking {
        val probe = Probe()
        withProbe(probe) { port ->
            val notice = executeOllamaCheck(
                AiSettings("http://127.0.0.1:$port/v1", "gemma4-64k", 4321),
                probe.sender(),
            )
            assertEquals(AiSettingsNotice.Success(AI_CHECK_LOADED), notice)
        }
        assertEquals(listOf("/api/tags", "/api/chat"), probe.paths)
        val body = probe.chatBodies.single()
        assertTrue(body.contains("\"num_ctx\":4321"))
        assertTrue(body.contains("\"keep_alive\":0"))
        assertTrue(body.contains("\"num_predict\":8"))
        assertTrue(body.contains("\"think\":false"))
        assertTrue(body.contains("\"model\":\"gemma4-64k\""))
    }

    @Test
    fun missingModelDoesNotCallChat() = runBlocking {
        val probe = Probe(tagsBody = """{"models":[{"name":"llama3.2:latest"}]}""")
        withProbe(probe) { port ->
            val notice = executeOllamaCheck(
                AiSettings("http://127.0.0.1:$port/v1", "gemma4-64k", 1024),
                probe.sender(),
            )
            assertEquals(
                AiSettingsNotice.Failure("Model \"gemma4-64k\" is not installed."),
                notice,
            )
        }
        assertEquals(listOf("/api/tags"), probe.paths)
        assertTrue(probe.chatBodies.isEmpty())
    }

    @Test
    fun thinkRejectionIsRetriedOnceWithoutThatField() = runBlocking {
        val probe = Probe(
            chatHandler = { body ->
                if (body.contains("\"think\"")) {
                    400 to """{"error":"think is not supported"}"""
                } else {
                    200 to """{"message":{"content":""}}"""
                }
            },
        )
        withProbe(probe) { port ->
            val notice = executeOllamaCheck(
                AiSettings("http://127.0.0.1:$port", "gemma4-64k", 1024),
                probe.sender(),
            )
            assertEquals(AiSettingsNotice.Success(AI_CHECK_LOADED), notice)
        }
        assertEquals(2, probe.chatBodies.size)
        assertTrue(probe.chatBodies[0].contains("\"think\":false"))
        assertFalse(probe.chatBodies[1].contains("think"))
    }

    @Test
    fun chatErrorWithoutThinkIsNotRetried() = runBlocking {
        val probe = Probe(chatHandler = { _ -> 500 to "model failed to load" })
        withProbe(probe) { port ->
            val notice = executeOllamaCheck(
                AiSettings("http://127.0.0.1:$port/v1", "gemma4-64k", 1024),
                probe.sender(),
            )
            assertIs<AiSettingsNotice.Failure>(notice)
            assertTrue(notice.message.contains("Ollama HTTP 500"))
            assertTrue(notice.message.contains("model failed to load"))
        }
        assertEquals(1, probe.chatBodies.size)
    }

    @Test
    fun closedPortIsAFailure() = runBlocking {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        val notice = executeOllamaCheck(
            AiSettings("http://127.0.0.1:9/v1", "gemma4-64k", 1024),
        ) { request ->
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }
        assertIs<AiSettingsNotice.Failure>(notice)
        assertFalse(notice.message.contains("Model loaded"))
    }
}

private class Probe(
    var tagsBody: String = """{"models":[{"name":"gemma4-64k:latest"}]}""",
    var chatHandler: (String) -> Pair<Int, String> = { _ ->
        200 to """{"message":{"content":"ok"}}"""
    },
) {
    val paths = mutableListOf<String>()
    val chatBodies = mutableListOf<String>()

    fun sender(): suspend (HttpRequest) -> HttpResponse<String> {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        return { request ->
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }
}

private suspend fun withProbe(probe: Probe, block: suspend (Int) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/tags") { exchange ->
        probe.paths += exchange.requestURI.path
        val bytes = probe.tagsBody.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.createContext("/api/chat") { exchange ->
        probe.paths += exchange.requestURI.path
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        probe.chatBodies += body
        val (status, response) = probe.chatHandler(body)
        val bytes = response.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
        block(server.address.port)
    } finally {
        server.stop(0)
    }
}
