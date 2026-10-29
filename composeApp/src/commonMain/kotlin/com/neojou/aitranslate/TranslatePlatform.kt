package com.neojou.aitranslate

/** A file the user picked, or a reason the pick produced no text. */
internal sealed interface PickedFile {
    data object Cancelled : PickedFile

    class Bytes(val fileName: String, val data: ByteArray) : PickedFile

    class Unreadable(val message: String) : PickedFile
}

internal sealed interface LoadResult {
    data object Cancelled : LoadResult

    data object NotUtf8 : LoadResult

    class Ready(val fileName: String, val text: String) : LoadResult

    class Failed(val message: String) : LoadResult
}

internal sealed interface SaveResult {
    data object Saved : SaveResult

    data object Cancelled : SaveResult

    class Failed(val message: String) : SaveResult
}

internal fun interpretPickedFile(picked: PickedFile): LoadResult = when (picked) {
    PickedFile.Cancelled -> LoadResult.Cancelled
    is PickedFile.Unreadable -> LoadResult.Failed(picked.message)
    is PickedFile.Bytes -> {
        val text = decodeUtf8(picked.data) ?: return LoadResult.NotUtf8
        LoadResult.Ready(picked.fileName, text)
    }
}

/** Folder name for one translation run, `yyyyMMdd-HHmmss` on Desktop. */
internal expect fun newSessionStamp(): String

internal expect suspend fun pickSourceFile(): PickedFile

internal expect suspend fun writeTranslation(suggestedName: String, text: String): SaveResult

/**
 * Read the standing command, write one round file, read that command back, and send it.
 * Desktop talks to Ollama. Wasm reports that translation is unavailable.
 */
internal expect suspend fun translatePassage(
    language: SourceLanguage,
    reference: String,
    passage: String,
    index: Int,
    stamp: String,
): TranslateStep
