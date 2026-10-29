package com.neojou.aitranslate

/**
 * Source language chosen in the left pane.
 *
 * [label] is the control text. [promptName] is what the command template receives.
 */
internal enum class SourceLanguage(val label: String, val promptName: String) {
    English("英文", "English"),
    Japanese("日文", "Japanese"),
}

/** One piece sent to the model. [paragraphBreakAfter] is true on the last piece of a paragraph that is not the last paragraph. */
internal data class SourcePiece(
    val text: String,
    val paragraphBreakAfter: Boolean,
)

internal data class LimitedText(
    val text: String,
    val truncated: Boolean,
)

internal sealed interface TranslateStep {
    data class Ok(val translation: String) : TranslateStep

    data class Failed(val message: String) : TranslateStep
}

internal const val SOURCE_CHAR_LIMIT: Int = 100_000
internal const val CHUNK_CHAR_LIMIT: Int = 1_500

internal const val TRUNCATED_NOTE: String = "已超過 100,000 字，文末已截掉。"
internal const val DESKTOP_ONLY_NOTE: String = "桌面版才能翻譯"
internal const val NOT_UTF8_NOTE: String = "這個檔案不是 UTF-8 文字。"
internal const val SAVE_FAILED_NOTE: String = "無法儲存。"
internal const val EMPTY_COMMAND_NOTE: String = "指令檔是空的。"
internal const val EMPTY_TRANSLATION_NOTE: String = "Ollama 沒有回傳譯文。"

/**
 * Standing command. Placeholders are [source language][promptName], the previous passage, and the current passage.
 * Desktop copies this into `AI-commands/translate.md` when that file is missing.
 */
internal val TRANSLATE_TEMPLATE: String = """
    # Translate

    Translate {{source_language}} into Traditional Chinese used in Taiwan.

    Rules:
    - Output only the translation of the passage.
    - Do not explain, annotate, quote the passage, or add a title.
    - Keep punctuation, numbers, Markdown, and line breaks that belong to the passage.
    - Keep names and wording consistent with the reference when one is given.
    - Do not translate the reference again.

    ## Reference

    {{reference}}

    ## Passage

    {{passage}}
    """.trimIndent() + "\n"

internal fun codePointCount(text: String): Int {
    var count = 0
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        index += if (ch.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

internal fun takeCodePoints(text: String, count: Int): String {
    if (count <= 0) return ""
    var seen = 0
    var index = 0
    while (index < text.length && seen < count) {
        val ch = text[index]
        index += if (ch.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
        seen++
    }
    return text.substring(0, index)
}

/** Keep the leading [limit] Unicode scalar values. Longer text drops the tail. */
internal fun limitText(text: String, limit: Int = SOURCE_CHAR_LIMIT): LimitedText {
    if (codePointCount(text) <= limit) return LimitedText(text, truncated = false)
    return LimitedText(takeCodePoints(text, limit), truncated = true)
}

/**
 * Split [text] into model-sized pieces.
 *
 * Blank lines separate paragraphs. A paragraph within [chunkLimit] stays whole.
 * A longer paragraph is split on sentence endings, then a still-longer sentence is cut by [chunkLimit].
 */
internal fun splitPassage(
    text: String,
    language: SourceLanguage,
    chunkLimit: Int = CHUNK_CHAR_LIMIT,
): List<SourcePiece> {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (normalized.isEmpty()) return emptyList()
    val paragraphs = normalized.split(PARAGRAPH_GAP).map { it.trim() }.filter { it.isNotEmpty() }
    val pieces = mutableListOf<SourcePiece>()
    paragraphs.forEachIndexed { paragraphIndex, paragraph ->
        val chunks = if (codePointCount(paragraph) <= chunkLimit) {
            listOf(paragraph)
        } else {
            splitLongParagraph(paragraph, language, chunkLimit)
        }
        val lastParagraph = paragraphIndex == paragraphs.lastIndex
        chunks.forEachIndexed { chunkIndex, chunk ->
            pieces += SourcePiece(
                text = chunk,
                paragraphBreakAfter = chunkIndex == chunks.lastIndex && !lastParagraph,
            )
        }
    }
    return pieces
}

internal fun translationReference(previousPassage: String?, previousTranslation: String?): String {
    if (previousPassage.isNullOrBlank()) return "(none)"
    return "Previous passage:\n${previousPassage.trim()}\n\nPrevious translation:\n${previousTranslation.orEmpty().trim()}"
}

/**
 * Fill the standing command.
 * When the passage placeholder has been removed, the passage is appended so the model still receives it.
 */
internal fun fillTemplate(
    template: String,
    sourceLanguage: String,
    reference: String,
    passage: String,
): String {
    val filled = template
        .replace("{{source_language}}", sourceLanguage)
        .replace("{{reference}}", reference)
        .replace("{{passage}}", passage)
    if (template.contains("{{passage}}")) return filled
    return filled.trimEnd() + "\n\n" + passage + "\n"
}

internal fun roundFileMarkdown(
    index: Int,
    sourceLanguage: String,
    model: String,
    command: String,
    response: String? = null,
): String = buildString {
    append("# ")
    append(index.toString().padStart(3, '0'))
    append("\n\nsource_language: ")
    append(sourceLanguage)
    append("\nmodel: ")
    append(model)
    append("\n\n## Command\n\n")
    append(command.trim())
    append('\n')
    if (response != null) {
        append("\n## Response\n\n")
        append(response.trim())
        append('\n')
    }
}

/** Text between `## Command` and `## Response`, which is the text actually sent. */
internal fun commandFromRoundFile(markdown: String): String {
    val marker = "## Command"
    val start = markdown.indexOf(marker)
    if (start < 0) return markdown.trim()
    var body = markdown.substring(start + marker.length).removePrefix("\r\n").removePrefix("\n")
    val response = body.indexOf("\n## Response")
    if (response >= 0) body = body.substring(0, response)
    return body.trim()
}

/** `notes.md` becomes `notes.zh-Hant.md`. A name without an extension becomes `name.zh-Hant.txt`. */
internal fun translationFileName(loadedName: String?): String {
    if (loadedName.isNullOrBlank()) return "translation.txt"
    val name = loadedName.substringAfterLast('/').substringAfterLast('\\').trim()
    if (name.isEmpty()) return "translation.txt"
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return "$name.zh-Hant.txt"
    return name.substring(0, dot) + ".zh-Hant" + name.substring(dot)
}

internal fun formatCount(count: Int): String {
    val digits = count.coerceAtLeast(0).toString()
    val grouped = StringBuilder()
    digits.reversed().forEachIndexed { index, ch ->
        if (index > 0 && index % 3 == 0) grouped.append(',')
        grouped.append(ch)
    }
    return grouped.reverse().toString()
}

/**
 * Strict UTF-8. A leading BOM is dropped. Malformed, overlong, and surrogate sequences return null.
 */
internal fun decodeUtf8(bytes: ByteArray): String? {
    var index = 0
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    ) {
        index = 3
    }
    val out = StringBuilder()
    while (index < bytes.size) {
        val b0 = bytes[index].toInt() and 0xFF
        when {
            b0 <= 0x7F -> {
                out.append(b0.toChar())
                index += 1
            }
            b0 in 0xC2..0xDF -> {
                if (!continuation(bytes, index + 1, 1)) return null
                val b1 = bytes[index + 1].toInt() and 0xFF
                out.append((((b0 and 0x1F) shl 6) or (b1 and 0x3F)).toChar())
                index += 2
            }
            b0 in 0xE0..0xEF -> {
                if (!continuation(bytes, index + 1, 2)) return null
                val b1 = bytes[index + 1].toInt() and 0xFF
                val b2 = bytes[index + 2].toInt() and 0xFF
                if (b0 == 0xE0 && b1 < 0xA0) return null
                if (b0 == 0xED && b1 >= 0xA0) return null
                val codePoint = ((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)
                out.append(codePoint.toChar())
                index += 3
            }
            b0 in 0xF0..0xF4 -> {
                if (!continuation(bytes, index + 1, 3)) return null
                val b1 = bytes[index + 1].toInt() and 0xFF
                val b2 = bytes[index + 2].toInt() and 0xFF
                val b3 = bytes[index + 3].toInt() and 0xFF
                if (b0 == 0xF0 && b1 < 0x90) return null
                if (b0 == 0xF4 && b1 >= 0x90) return null
                val codePoint = ((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or
                    ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                if (codePoint > 0x10FFFF) return null
                val shifted = codePoint - 0x10000
                out.append(((shifted ushr 10) or 0xD800).toChar())
                out.append(((shifted and 0x3FF) or 0xDC00).toChar())
                index += 4
            }
            else -> return null
        }
    }
    return out.toString()
}

internal fun ollamaTranslateBody(
    model: String,
    numCtx: Int,
    command: String,
    includeThink: Boolean,
): String = buildString {
    append("{\"model\":")
    append(jsonQuote(model))
    append(",\"stream\":false")
    if (includeThink) append(",\"think\":false")
    append(",\"keep_alive\":\"10m\",\"options\":{\"num_ctx\":")
    append(numCtx)
    append("},\"messages\":[{\"role\":\"user\",\"content\":")
    append(jsonQuote(command))
    append("}]}")
}

private val PARAGRAPH_GAP = Regex("""\n[ \t]*\n(?:[ \t]*\n)*""")
private val ENGLISH_SENTENCE = Regex("""(?<=[.!?])[ \t]+""")
private val JAPANESE_SENTENCE = Regex("""(?<=[。！？])\s*""")

private fun splitLongParagraph(paragraph: String, language: SourceLanguage, chunkLimit: Int): List<String> {
    val pattern = when (language) {
        SourceLanguage.English -> ENGLISH_SENTENCE
        SourceLanguage.Japanese -> JAPANESE_SENTENCE
    }
    val sentences = pattern.split(paragraph).map { it.trim() }.filter { it.isNotEmpty() }
    val chunks = mutableListOf<String>()
    for (sentence in sentences) {
        if (codePointCount(sentence) <= chunkLimit) {
            chunks += sentence
        } else {
            var rest = sentence
            while (codePointCount(rest) > chunkLimit) {
                chunks += takeCodePoints(rest, chunkLimit)
                rest = rest.substring(chunks.last().length)
            }
            if (rest.isNotEmpty()) chunks += rest
        }
    }
    return chunks
}

private fun continuation(bytes: ByteArray, start: Int, count: Int): Boolean {
    if (start + count > bytes.size) return false
    for (offset in 0 until count) {
        val value = bytes[start + offset].toInt() and 0xFF
        if (value !in 0x80..0xBF) return false
    }
    return true
}
