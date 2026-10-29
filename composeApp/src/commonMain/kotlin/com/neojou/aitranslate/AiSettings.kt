package com.neojou.aitranslate

/**
 * Local Ollama settings edited from Settings → AI Settings.
 *
 * Defaults match the values used by mystudy. Only Desktop persists them.
 */
internal data class AiSettings(
    val ollamaBaseUrl: String,
    val ollamaModel: String,
    val ollamaNumCtx: Int,
) {
    companion object {
        const val DEFAULT_OLLAMA_URL: String = "http://127.0.0.1:11434/v1"
        const val DEFAULT_OLLAMA_MODEL: String = "gemma4-64k"
        const val DEFAULT_NUM_CTX: Int = 65536

        val defaults: AiSettings = AiSettings(
            ollamaBaseUrl = DEFAULT_OLLAMA_URL,
            ollamaModel = DEFAULT_OLLAMA_MODEL,
            ollamaNumCtx = DEFAULT_NUM_CTX,
        )
    }
}

internal const val AI_KEY_URL: String = "ollamaBaseUrl"
internal const val AI_KEY_MODEL: String = "ollamaModel"
internal const val AI_KEY_NUM_CTX: String = "ollamaNumCtx"

/**
 * Java Preferences node for this template.
 *
 * `./configure.sh proj_name` rewrites `com/neojou/aitranslate` inside this literal
 * when the package changes, so a copied project does not share this node.
 */
internal const val AI_PREFS_NODE: String = "com/neojou/aitranslate/ai"

internal const val AI_CHECK_LOADED: String = "Ollama is reachable. Model loaded."
internal const val AI_NUM_CTX_ERROR: String = "num_ctx must be a positive number."
internal const val AI_MODEL_EMPTY: String = "Model is empty."
internal const val AI_URL_INVALID: String = "Ollama URL is invalid."
internal const val AI_HOST_LOOPBACK: String = "Ollama host must be loopback."

internal sealed interface AiSettingsNotice {
    data object Checking : AiSettingsNotice

    data class Saved(val settings: AiSettings) : AiSettingsNotice

    data class Success(val message: String) : AiSettingsNotice

    data class Failure(val message: String) : AiSettingsNotice
}

internal interface AiSettingsStore {
    fun get(key: String, default: String): String
    fun put(key: String, value: String)
}

/** In-memory store for tests. Desktop uses Java Preferences. */
internal class MemoryAiSettingsStore(
    initial: Map<String, String> = emptyMap(),
) : AiSettingsStore {
    private val values = initial.toMutableMap()

    override fun get(key: String, default: String): String = values[key] ?: default

    override fun put(key: String, value: String) {
        values[key] = value
    }
}

internal fun loadAiSettingsFrom(store: AiSettingsStore): AiSettings {
    val url = store.get(AI_KEY_URL, AiSettings.DEFAULT_OLLAMA_URL).trim().ifBlank {
        AiSettings.DEFAULT_OLLAMA_URL
    }
    val model = store.get(AI_KEY_MODEL, AiSettings.DEFAULT_OLLAMA_MODEL).trim().ifBlank {
        AiSettings.DEFAULT_OLLAMA_MODEL
    }
    val numCtx = store.get(AI_KEY_NUM_CTX, AiSettings.DEFAULT_NUM_CTX.toString())
        .trim()
        .toIntOrNull()
        ?.takeIf { it > 0 }
        ?: AiSettings.DEFAULT_NUM_CTX
    return AiSettings(url, model, numCtx)
}

internal fun saveAiSettingsTo(store: AiSettingsStore, settings: AiSettings) {
    store.put(AI_KEY_URL, settings.ollamaBaseUrl.trim())
    store.put(AI_KEY_MODEL, settings.ollamaModel.trim())
    store.put(AI_KEY_NUM_CTX, settings.ollamaNumCtx.toString())
}

internal sealed interface PreparedSettings {
    data class Ok(val settings: AiSettings) : PreparedSettings

    data class Rejected(val message: String) : PreparedSettings
}

/**
 * Blank URL or model becomes the default. A non-positive [numCtxText] is rejected.
 * The URL must be loopback `http` or `https`.
 */
internal fun prepareSave(url: String, model: String, numCtxText: String): PreparedSettings {
    val numCtx = parseNumCtx(numCtxText) ?: return PreparedSettings.Rejected(AI_NUM_CTX_ERROR)
    val urlValue = url.trim().ifBlank { AiSettings.DEFAULT_OLLAMA_URL }
    val modelValue = model.trim().ifBlank { AiSettings.DEFAULT_OLLAMA_MODEL }
    return when (val origin = ollamaOrigin(urlValue)) {
        is OllamaOrigin.Invalid -> PreparedSettings.Rejected(origin.message)
        is OllamaOrigin.Ok -> PreparedSettings.Ok(AiSettings(urlValue, modelValue, numCtx))
    }
}

/**
 * Check uses the text on screen. Blank fields are errors, not defaults.
 */
internal fun prepareCheck(url: String, model: String, numCtxText: String): PreparedSettings {
    val numCtx = parseNumCtx(numCtxText) ?: return PreparedSettings.Rejected(AI_NUM_CTX_ERROR)
    val modelValue = model.trim()
    if (modelValue.isEmpty()) return PreparedSettings.Rejected(AI_MODEL_EMPTY)
    val urlValue = url.trim()
    if (urlValue.isEmpty()) return PreparedSettings.Rejected(AI_URL_INVALID)
    return when (val origin = ollamaOrigin(urlValue)) {
        is OllamaOrigin.Invalid -> PreparedSettings.Rejected(origin.message)
        is OllamaOrigin.Ok -> PreparedSettings.Ok(AiSettings(urlValue, modelValue, numCtx))
    }
}

internal suspend fun checkAiSettingsFields(
    url: String,
    model: String,
    numCtxText: String,
): AiSettingsNotice {
    return when (val prepared = prepareCheck(url, model, numCtxText)) {
        is PreparedSettings.Rejected -> AiSettingsNotice.Failure(prepared.message)
        is PreparedSettings.Ok -> checkInstalledModel(prepared.settings)
    }
}

internal fun saveAiSettingsFields(url: String, model: String, numCtxText: String): AiSettingsNotice {
    return when (val prepared = prepareSave(url, model, numCtxText)) {
        is PreparedSettings.Rejected -> AiSettingsNotice.Failure(prepared.message)
        is PreparedSettings.Ok -> {
            saveAiSettings(prepared.settings)
            AiSettingsNotice.Saved(prepared.settings)
        }
    }
}

internal sealed interface OllamaOrigin {
    data class Ok(val origin: String) : OllamaOrigin

    data class Invalid(val message: String) : OllamaOrigin
}

/**
 * `scheme://host[:port]` for a loopback Ollama URL.
 *
 * The path, including a trailing `/v1`, is dropped. Native `/api/tags` and `/api/chat`
 * are rooted at this origin. Query, fragment, and userinfo are rejected.
 */
internal fun ollamaOrigin(baseUrl: String): OllamaOrigin {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return OllamaOrigin.Invalid(AI_URL_INVALID)
    val schemeSep = trimmed.indexOf("://")
    if (schemeSep <= 0) return OllamaOrigin.Invalid(AI_URL_INVALID)
    val scheme = trimmed.substring(0, schemeSep).lowercase()
    if (scheme != "http" && scheme != "https") return OllamaOrigin.Invalid(AI_URL_INVALID)
    var rest = trimmed.substring(schemeSep + 3)
    if (rest.isEmpty() || '?' in rest || '#' in rest) return OllamaOrigin.Invalid(AI_URL_INVALID)
    val slash = rest.indexOf('/')
    if (slash >= 0) rest = rest.substring(0, slash)
    if (rest.isEmpty() || '@' in rest) return OllamaOrigin.Invalid(AI_URL_INVALID)

    val host: String
    val port: String
    if (rest.startsWith("[")) {
        val end = rest.indexOf(']')
        if (end <= 1) return OllamaOrigin.Invalid(AI_URL_INVALID)
        host = rest.substring(1, end)
        val after = rest.substring(end + 1)
        if (after.isEmpty()) {
            port = ""
        } else if (after.startsWith(":") && after.length > 1) {
            port = after.substring(1)
        } else {
            return OllamaOrigin.Invalid(AI_URL_INVALID)
        }
    } else {
        val colon = rest.lastIndexOf(':')
        if (colon >= 0) {
            host = rest.substring(0, colon)
            port = rest.substring(colon + 1)
            if (port.isEmpty()) return OllamaOrigin.Invalid(AI_URL_INVALID)
        } else {
            host = rest
            port = ""
        }
    }
    if (host.isEmpty()) return OllamaOrigin.Invalid(AI_URL_INVALID)
    val hostLower = host.lowercase()
    if (hostLower != "127.0.0.1" && hostLower != "localhost") {
        return OllamaOrigin.Invalid(AI_HOST_LOOPBACK)
    }
    if (port.isEmpty()) return OllamaOrigin.Ok("$scheme://$hostLower")
    val number = port.toIntOrNull() ?: return OllamaOrigin.Invalid(AI_URL_INVALID)
    if (number !in 1..65535) return OllamaOrigin.Invalid(AI_URL_INVALID)
    return OllamaOrigin.Ok("$scheme://$hostLower:$number")
}

/**
 * True when [model] is listed as itself or as `model:tag`.
 * `gemma4-64k` matches `gemma4-64k:latest` and does not match `gemma4-64k-extra`.
 */
internal fun modelInstalled(names: List<String>, model: String): Boolean {
    val wanted = model.trim()
    if (wanted.isEmpty()) return false
    val tagged = "$wanted:"
    return names.any { name -> name == wanted || name.startsWith(tagged) }
}

/** Names from an Ollama `/api/tags` body. A body without `name` fields yields an empty list. */
internal fun ollamaModelNames(tagsJson: String): List<String> {
    val names = mutableListOf<String>()
    for (match in NAME_FIELD.findAll(tagsJson)) {
        names.add(jsonUnescape(match.groupValues[1]))
    }
    return names
}

/**
 * Short `/api/chat` body. [includeThink] sends `think: false`.
 * `keep_alive` is 0 so a successful check does not leave the context loaded.
 * `num_predict` is 8 so the call only proves the model can generate.
 */
internal fun ollamaChatBody(model: String, numCtx: Int, includeThink: Boolean): String = buildString {
    append("{\"model\":")
    append(jsonQuote(model))
    append(",\"stream\":false")
    if (includeThink) append(",\"think\":false")
    append(",\"keep_alive\":0,\"options\":{\"num_ctx\":")
    append(numCtx)
    append(",\"num_predict\":8},\"messages\":[{\"role\":\"user\",\"content\":\"Reply with ok.\"}]}")
}

private fun parseNumCtx(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it > 0 }

private val NAME_FIELD = Regex(""""name"\s*:\s*"((?:\\.|[^"\\])*)"""")

private fun hex4(code: Int): String {
    val digits = "0123456789abcdef"
    return buildString(4) {
        for (shift in intArrayOf(12, 8, 4, 0)) {
            append(digits[(code shr shift) and 0xF])
        }
    }
}

internal fun jsonQuote(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u").append(hex4(ch.code)) else append(ch)
        }
    }
    append('"')
}

/**
 * Value of the first `"key": "..."` string in [json].
 * A key mentioned inside another string does not count unless it is followed by a colon.
 * Returns null when the key is missing or the string is cut off.
 */
internal fun jsonStringField(json: String, key: String): String? {
    val needle = "\"$key\""
    var searchFrom = 0
    while (searchFrom < json.length) {
        val found = json.indexOf(needle, searchFrom)
        if (found < 0) return null
        var index = found + needle.length
        while (index < json.length && json[index].isWhitespace()) index++
        if (index < json.length && json[index] == ':') {
            index++
            while (index < json.length && json[index].isWhitespace()) index++
            if (index < json.length && json[index] == '"') {
                return readJsonString(json, index)
            }
        }
        searchFrom = found + needle.length
    }
    return null
}

private fun readJsonString(json: String, quoteIndex: Int): String? {
    val raw = StringBuilder()
    var index = quoteIndex + 1
    while (index < json.length) {
        val ch = json[index]
        if (ch == '"') return jsonUnescape(raw.toString())
        if (ch == '\\') {
            if (index + 1 >= json.length) return null
            raw.append(ch).append(json[index + 1])
            if (json[index + 1] == 'u') {
                if (index + 5 >= json.length) return null
                raw.append(json.substring(index + 2, index + 6))
                index += 6
                continue
            }
            index += 2
            continue
        }
        raw.append(ch)
        index++
    }
    return null
}

private fun jsonUnescape(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch != '\\' || index + 1 >= value.length) {
            append(ch)
            index++
            continue
        }
        when (val next = value[index + 1]) {
            '\\', '"', '/' -> {
                append(next)
                index += 2
            }
            'n' -> {
                append('\n')
                index += 2
            }
            'r' -> {
                append('\r')
                index += 2
            }
            't' -> {
                append('\t')
                index += 2
            }
            'u' -> {
                if (index + 6 <= value.length) {
                    val code = value.substring(index + 2, index + 6).toIntOrNull(16)
                    if (code != null) {
                        append(code.toChar())
                        index += 6
                        continue
                    }
                }
                append(ch)
                index++
            }
            else -> {
                append(ch)
                index++
            }
        }
    }
}
