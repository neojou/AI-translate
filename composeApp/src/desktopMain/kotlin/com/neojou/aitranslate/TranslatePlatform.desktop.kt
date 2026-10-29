package com.neojou.aitranslate

import kotlinx.coroutines.CancellationException
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val SESSION_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

internal actual fun newSessionStamp(): String = LocalDateTime.now().format(SESSION_STAMP)

internal actual suspend fun pickSourceFile(): PickedFile {
    val dialog = FileDialog(null as Frame?, "載入原文", FileDialog.LOAD)
    dialog.isAlwaysOnTop = true
    dialog.isVisible = true
    val name = dialog.file ?: return PickedFile.Cancelled
    val directory = dialog.directory ?: return PickedFile.Cancelled
    return try {
        PickedFile.Bytes(name, File(directory, name).readBytes())
    } catch (error: Exception) {
        PickedFile.Unreadable(error.message ?: "無法讀取檔案。")
    }
}

internal actual suspend fun writeTranslation(suggestedName: String, text: String): SaveResult {
    val dialog = FileDialog(null as Frame?, "儲存譯文", FileDialog.SAVE)
    dialog.file = suggestedName
    dialog.isAlwaysOnTop = true
    dialog.isVisible = true
    val name = dialog.file ?: return SaveResult.Cancelled
    val directory = dialog.directory ?: return SaveResult.Cancelled
    return try {
        File(directory, name).writeText(text, Charsets.UTF_8)
        SaveResult.Saved
    } catch (error: Exception) {
        SaveResult.Failed(error.message ?: SAVE_FAILED_NOTE)
    }
}

internal actual suspend fun translatePassage(
    language: SourceLanguage,
    reference: String,
    passage: String,
    index: Int,
    stamp: String,
): TranslateStep {
    val root = findProjectRoot(File(System.getProperty("user.dir")))
    return executeTranslationRound(
        settings = loadAiSettings(),
        language = language,
        reference = reference,
        passage = passage,
        index = index,
        stamp = stamp,
        root = root,
        sendRequest = ::ollamaSend,
    )
}

/**
 * Walk up from [start] to a directory that contains `settings.gradle.kts`.
 * The working directory is the fallback, so a packaged launch still has a place to write.
 */
internal fun findProjectRoot(start: File): File {
    var dir: File? = start.absoluteFile
    var hops = 0
    while (dir != null && hops < 8) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
        hops++
    }
    return start.absoluteFile
}

internal fun ensureTranslateTemplate(root: File): File {
    val commands = File(root, "AI-commands")
    if (!commands.isDirectory && !commands.mkdirs()) {
        error("Could not create ${commands.path}")
    }
    val template = File(commands, "translate.md")
    if (!template.exists()) template.writeText(TRANSLATE_TEMPLATE, Charsets.UTF_8)
    return template
}

/**
 * One passage: fill the template, write the round file, read the command back, then post `/api/chat`.
 *
 * `keep_alive` stays at 10 minutes so the next passage does not reload the model.
 * A rejection that mentions `think` is retried once without that field.
 * The check call is unchanged and still unloads with `keep_alive` 0.
 */
internal suspend fun executeTranslationRound(
    settings: AiSettings,
    language: SourceLanguage,
    reference: String,
    passage: String,
    index: Int,
    stamp: String,
    root: File,
    sendRequest: suspend (HttpRequest) -> HttpResponse<String>,
): TranslateStep {
    val origin = when (val parsed = ollamaOrigin(settings.ollamaBaseUrl)) {
        is OllamaOrigin.Ok -> parsed.origin
        is OllamaOrigin.Invalid -> return TranslateStep.Failed(parsed.message)
    }
    val template = try {
        ensureTranslateTemplate(root).readText(Charsets.UTF_8)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return TranslateStep.Failed(error.message ?: "無法讀取翻譯指令。")
    }
    val filled = fillTemplate(template, language.promptName, reference, passage)
    val round = try {
        writeRoundFile(root, stamp, index, settings.ollamaModel, language.promptName, filled, response = null)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return TranslateStep.Failed(error.message ?: "無法寫入指令檔。")
    }
    val command = try {
        commandFromRoundFile(round.readText(Charsets.UTF_8))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return TranslateStep.Failed(error.message ?: "無法讀取指令檔。")
    }
    if (command.isBlank()) return TranslateStep.Failed(EMPTY_COMMAND_NOTE)

    val first = postTranslation(origin, settings, command, includeThink = true, sendRequest)
    val step = if (first is TranslateStep.Failed && first.message.contains("think", ignoreCase = true)) {
        postTranslation(origin, settings, command, includeThink = false, sendRequest)
    } else {
        first
    }
    if (step is TranslateStep.Ok) {
        try {
            writeRoundFile(root, stamp, index, settings.ollamaModel, language.promptName, command, step.translation)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The translation is already in hand. A late write failure still returns it.
        }
    }
    return step
}

private suspend fun postTranslation(
    origin: String,
    settings: AiSettings,
    command: String,
    includeThink: Boolean,
    sendRequest: suspend (HttpRequest) -> HttpResponse<String>,
): TranslateStep {
    val response = try {
        sendRequest(
            ollamaRequest(
                origin,
                path = "/api/chat",
                body = ollamaTranslateBody(settings.ollamaModel, settings.ollamaNumCtx, command, includeThink),
                timeout = Duration.ofMinutes(OLLAMA_CHAT_TIMEOUT_MINUTES),
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return TranslateStep.Failed(error.message ?: "Ollama is not reachable.")
    }
    if (response.statusCode() !in 200..299) return TranslateStep.Failed(ollamaHttpFailure(response))
    val content = jsonStringField(response.body(), "content")?.trim()
    if (content.isNullOrEmpty()) return TranslateStep.Failed(EMPTY_TRANSLATION_NOTE)
    return TranslateStep.Ok(content)
}

private fun writeRoundFile(
    root: File,
    stamp: String,
    index: Int,
    model: String,
    sourceLanguage: String,
    command: String,
    response: String?,
): File {
    val safeStamp = stamp.filter { it.isDigit() || it == '-' }.ifBlank { "session" }
    val dir = File(root, "AI-commands/runs/$safeStamp")
    if (!dir.isDirectory && !dir.mkdirs()) error("Could not create ${dir.path}")
    val file = File(dir, index.coerceAtLeast(1).toString().padStart(3, '0') + ".md")
    file.writeText(
        roundFileMarkdown(index, sourceLanguage, model, command, response),
        Charsets.UTF_8,
    )
    return file
}
