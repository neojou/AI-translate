package com.neojou.aitranslate

/**
 * Desktop stores and checks Ollama. Wasm reports that the feature is absent,
 * and the menu stays hidden there.
 */
internal expect fun aiSettingsAvailable(): Boolean

internal expect fun loadAiSettings(): AiSettings

internal expect fun saveAiSettings(settings: AiSettings)

/**
 * GET `/api/tags`, then POST a short `/api/chat` with this `num_ctx`.
 * The caller has already rejected a bad URL, an empty model, and a bad `num_ctx`.
 */
internal expect suspend fun checkInstalledModel(settings: AiSettings): AiSettingsNotice
