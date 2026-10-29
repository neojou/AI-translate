package com.neojou.aitranslate

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranslateRoundTest {
    @Test
    fun projectRootWalksUpToTheSettingsFile() {
        val root = Files.createTempDirectory("translate-root").toFile()
        try {
            File(root, "settings.gradle.kts").writeText("// test\n")
            val nested = File(root, "composeApp/src")
            nested.mkdirs()
            assertEquals(root.canonicalFile, findProjectRoot(nested).canonicalFile)
            val nowhere = Files.createTempDirectory("translate-nowhere").toFile()
            try {
                assertEquals(nowhere.canonicalFile, findProjectRoot(nowhere).canonicalFile)
            } finally {
                nowhere.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun roundReadsTheCommandFileAndKeepsTheModelLoaded() = runBlocking {
        val root = Files.createTempDirectory("translate-round").toFile()
        try {
            File(root, "AI-commands").mkdirs()
            File(root, "AI-commands/translate.md").writeText(
                "Translate {{source_language}}.\n\n{{reference}}\n\n{{passage}}\n",
            )
            val probe = RoundProbe()
            withRoundProbe(probe) { port ->
                val step = executeTranslationRound(
                    settings = AiSettings("http://127.0.0.1:$port/v1", "gemma4-64k", 2048),
                    language = SourceLanguage.English,
                    reference = "(none)",
                    passage = "Hello.",
                    index = 1,
                    stamp = "20261030-120000",
                    root = root,
                    sendRequest = probe.sender(),
                )
                assertEquals(TranslateStep.Ok("你好。"), step)
            }
            val body = probe.chatBodies.single()
            assertTrue(body.contains("\"keep_alive\":\"10m\""))
            assertTrue(body.contains("\"num_ctx\":2048"))
            assertFalse(body.contains("num_predict"))
            val sent = jsonStringField(body, "content")
            val round = File(root, "AI-commands/runs/20261030-120000/001.md").readText()
            assertEquals(sent, commandFromRoundFile(round))
            assertTrue(sent!!.contains("Hello."))
            assertTrue(sent.contains("Translate English."))
            assertTrue(round.contains("## Response"))
            assertTrue(round.contains("你好。"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun thinkRejectionIsRetriedAndContentIsNotTheThinkingText() = runBlocking {
        val root = Files.createTempDirectory("translate-think").toFile()
        try {
            val probe = RoundProbe(
                chatHandler = { body ->
                    if (body.contains("\"think\"")) {
                        400 to """{"error":"think is not supported"}"""
                    } else {
                        200 to """{"message":{"thinking":"draft","content":"你好。"}}"""
                    }
                },
            )
            withRoundProbe(probe) { port ->
                val step = executeTranslationRound(
                    settings = AiSettings("http://127.0.0.1:$port", "gemma4-64k", 1024),
                    language = SourceLanguage.Japanese,
                    reference = "(none)",
                    passage = "こんにちは。",
                    index = 2,
                    stamp = "../sneaky",
                    root = root,
                    sendRequest = probe.sender(),
                )
                assertEquals(TranslateStep.Ok("你好。"), step)
            }
            assertEquals(2, probe.chatBodies.size)
            assertTrue(probe.chatBodies[0].contains("\"think\":false"))
            assertFalse(probe.chatBodies[1].contains("think"))
            assertTrue(File(root, "AI-commands/runs/session/002.md").isFile)
            assertFalse(File(root, "AI-commands/runs/../sneaky").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun httpFailureLeavesTheCommandWithoutAResponse() = runBlocking {
        val root = Files.createTempDirectory("translate-fail").toFile()
        try {
            val probe = RoundProbe(chatHandler = { _ -> 500 to "model failed" })
            withRoundProbe(probe) { port ->
                val step = executeTranslationRound(
                    settings = AiSettings("http://127.0.0.1:$port", "gemma4-64k", 1024),
                    language = SourceLanguage.English,
                    reference = "(none)",
                    passage = "Hello.",
                    index = 1,
                    stamp = "20261030-120001",
                    root = root,
                    sendRequest = probe.sender(),
                )
                val failed = assertIs<TranslateStep.Failed>(step)
                assertTrue(failed.message.contains("Ollama HTTP 500"))
            }
            val round = File(root, "AI-commands/runs/20261030-120001/001.md").readText()
            assertTrue(round.contains("Hello."))
            assertFalse(round.contains("## Response"))
        } finally {
            root.deleteRecursively()
        }
    }
}

private class RoundProbe(
    var chatHandler: (String) -> Pair<Int, String> = { _ ->
        200 to """{"message":{"thinking":"draft","content":"你好。"}}"""
    },
) {
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

private suspend fun withRoundProbe(probe: RoundProbe, block: suspend (Int) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/chat") { exchange ->
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
