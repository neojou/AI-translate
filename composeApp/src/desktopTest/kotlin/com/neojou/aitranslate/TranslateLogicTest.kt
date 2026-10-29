package com.neojou.aitranslate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslateLogicTest {
    @Test
    fun limitDropsTheTailAndCountsAScalarAsOne() {
        assertEquals(LimitedText("abc", truncated = false), limitText("abc", 10))
        assertEquals(LimitedText("ab", truncated = true), limitText("abcdef", 2))
        assertEquals("😀", limitText("😀A", 1).text)
        assertEquals(1, codePointCount("😀"))
        assertTrue(limitText("😀A", 1).truncated)
    }

    @Test
    fun shortParagraphStaysWhole() {
        val pieces = splitPassage("Hello. World.", SourceLanguage.English, chunkLimit = 1_500)
        assertEquals(listOf(SourcePiece("Hello. World.", paragraphBreakAfter = false)), pieces)
    }

    @Test
    fun blankLinesSeparateParagraphsAndCrlfIsNormalized() {
        val pieces = splitPassage("Short one.\r\n\r\nShort two.", SourceLanguage.English)
        assertEquals("Short one.", pieces[0].text)
        assertEquals("Short two.", pieces[1].text)
        assertTrue(pieces[0].paragraphBreakAfter)
        assertFalse(pieces[1].paragraphBreakAfter)
    }

    @Test
    fun longEnglishParagraphSplitsOnSentencesThenSize() {
        val paragraph = "Hello world. " + "x".repeat(25)
        val pieces = splitPassage(paragraph, SourceLanguage.English, chunkLimit = 20)
        assertEquals("Hello world.", pieces[0].text)
        assertEquals("x".repeat(20), pieces[1].text)
        assertEquals("x".repeat(5), pieces[2].text)
        assertTrue(pieces.none { it.paragraphBreakAfter })
    }

    @Test
    fun longJapaneseParagraphSplitsOnSentenceMarks() {
        val paragraph = "これは文です。次の文です。"
        val pieces = splitPassage(paragraph, SourceLanguage.Japanese, chunkLimit = 8)
        assertEquals(listOf("これは文です。", "次の文です。"), pieces.map { it.text })
    }

    @Test
    fun scalarCutDoesNotSplitAnEmoji() {
        val pieces = splitPassage("😀😀AB", SourceLanguage.English, chunkLimit = 2)
        assertEquals(listOf("😀😀", "AB"), pieces.map { it.text })
    }

    @Test
    fun missingPassagePlaceholderIsAppended() {
        val filled = fillTemplate("Translate {{source_language}}.\n", "English", "(none)", "Hello.")
        assertTrue(filled.trimEnd().endsWith("Hello."))
        assertFalse(filled.contains("{{passage}}"))
    }

    @Test
    fun templateFillAndRoundFileRoundTrip() {
        val command = fillTemplate(TRANSLATE_TEMPLATE, "Japanese", "(none)", "こんにちは。")
        assertTrue(command.contains("Translate Japanese into Traditional Chinese"))
        assertTrue(command.contains("こんにちは。"))
        assertFalse(command.contains("{{passage}}"))
        val file = roundFileMarkdown(3, "Japanese", "gemma4-64k", command)
        assertEquals(command.trim(), commandFromRoundFile(file))
        val answered = roundFileMarkdown(3, "Japanese", "gemma4-64k", command, "你好。")
        assertEquals(command.trim(), commandFromRoundFile(answered))
        assertTrue(answered.contains("## Response"))
        assertTrue(answered.contains("你好。"))
    }

    @Test
    fun messageContentIgnoresThinking() {
        val json = """{"message":{"thinking":"see \"content\": later","content":"你好。"}}"""
        assertEquals("你好。", jsonStringField(json, "content"))
        assertNull(jsonStringField("{}", "content"))
    }

    @Test
    fun translateBodyKeepsTheModelLoaded() {
        val body = ollamaTranslateBody("gemma4-64k", 2048, "Hello.", includeThink = true)
        assertTrue(body.contains("\"keep_alive\":\"10m\""))
        assertTrue(body.contains("\"num_ctx\":2048"))
        assertTrue(body.contains("\"think\":false"))
        assertFalse(body.contains("num_predict"))
        assertEquals("Hello.", jsonStringField(body, "content"))
    }

    @Test
    fun namesAndUtf8() {
        assertEquals("translation.txt", translationFileName(null))
        assertEquals("notes.zh-Hant.md", translationFileName("dir/notes.md"))
        assertEquals("README.zh-Hant.txt", translationFileName("README"))
        assertEquals("12,480", formatCount(12480))
        assertEquals("你好", decodeUtf8("你好".encodeToByteArray()))
        assertEquals("hi", decodeUtf8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte(), 'i'.code.toByte())))
        assertNull(decodeUtf8(byteArrayOf(0xFF.toByte())))
        assertEquals("Hello.", (interpretPickedFile(PickedFile.Bytes("a.txt", "Hello.".encodeToByteArray())) as LoadResult.Ready).text)
        assertIs<LoadResult.NotUtf8>(interpretPickedFile(PickedFile.Bytes("a.txt", byteArrayOf(0xFF.toByte()))))
        assertIs<LoadResult.Cancelled>(interpretPickedFile(PickedFile.Cancelled))
    }
}
