package com.neojou.aitranslate

/**
 * Wasm has no AI Settings menu and does not call Ollama.
 * These actuals exist so the shared dialog still compiles.
 */
internal actual fun aiSettingsAvailable(): Boolean = false

internal actual fun loadAiSettings(): AiSettings = AiSettings.defaults

internal actual fun saveAiSettings(settings: AiSettings) = Unit

internal actual suspend fun checkInstalledModel(settings: AiSettings): AiSettingsNotice =
    AiSettingsNotice.Failure("Desktop only.")
