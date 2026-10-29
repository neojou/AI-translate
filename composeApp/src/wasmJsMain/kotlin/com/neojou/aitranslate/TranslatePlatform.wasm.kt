@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.neojou.aitranslate

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.toJsString
import kotlin.js.unsafeCast

internal actual fun newSessionStamp(): String = "wasm"

internal actual suspend fun translatePassage(
    language: SourceLanguage,
    reference: String,
    passage: String,
    index: Int,
    stamp: String,
): TranslateStep = TranslateStep.Failed(DESKTOP_ONLY_NOTE)

/**
 * Browser file picker. Dismissing the dialog does not fire `change`, so a later window focus
 * with an empty selection is treated as cancel.
 */
internal actual suspend fun pickSourceFile(): PickedFile = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    val body = document.body
    if (body == null) {
        cont.resume(PickedFile.Cancelled)
        return@suspendCancellableCoroutine
    }
    val input = document.createElement("input").unsafeCast<HTMLInputElement>()
    input.type = "file"
    input.accept = ".txt,.md,.markdown,text/plain,text/markdown"
    input.style.display = "none"
    body.appendChild(input)
    var settled = false
    fun finish(result: PickedFile) {
        if (settled) return
        settled = true
        input.remove()
        if (cont.isActive) cont.resume(result)
    }
    input.onchange = {
        val file = input.files?.item(0)
        if (file == null) finish(PickedFile.Cancelled) else readPickedFile(file, ::finish)
    }
    input.click()
    window.setTimeout({
        lateinit var listener: (Event) -> Unit
        listener = {
            window.removeEventListener("focus", listener)
            window.setTimeout({
                val files = input.files
                if (!settled && (files == null || files.length == 0)) finish(PickedFile.Cancelled)
                null
            }, 400)
            Unit
        }
        window.addEventListener("focus", listener)
        null
    }, 400)
    cont.invokeOnCancellation {
        settled = true
        input.remove()
    }
}

internal actual suspend fun writeTranslation(suggestedName: String, text: String): SaveResult {
    return try {
        val parts = JsArray<JsAny?>()
        parts[0] = text.toJsString()
        val blob = Blob(parts, BlobPropertyBag(type = "text/plain;charset=utf-8"))
        val url = URL.createObjectURL(blob)
        val anchor = document.createElement("a").unsafeCast<HTMLAnchorElement>()
        anchor.href = url
        anchor.download = suggestedName
        document.body?.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
        SaveResult.Saved
    } catch (error: Throwable) {
        SaveResult.Failed(error.message ?: SAVE_FAILED_NOTE)
    }
}

@Suppress("UNUSED_PARAMETER")
private fun strictUtf8(buffer: ArrayBuffer): String? = js(
    "(() => { try { return new TextDecoder('utf-8', { fatal: true }).decode(buffer) } catch (e) { return null } })()",
)

private fun readPickedFile(file: File, finish: (PickedFile) -> Unit) {
    val reader = FileReader()
    reader.onload = {
        val buffer = reader.result?.unsafeCast<ArrayBuffer>()
        if (buffer == null) {
            finish(PickedFile.Unreadable("無法讀取檔案。"))
        } else {
            val text = strictUtf8(buffer)
            if (text == null) {
                finish(PickedFile.Unreadable(NOT_UTF8_NOTE))
            } else {
                finish(PickedFile.Bytes(file.name, text.encodeToByteArray()))
            }
        }
    }
    reader.onerror = { finish(PickedFile.Unreadable("無法讀取檔案。")) }
    reader.readAsArrayBuffer(file)
}
