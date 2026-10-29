package com.neojou.aitranslate

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/**
 * Browser / Wasm entry — mounts shared [App] into the page body.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = AppVersion.APP_NAME
    ComposeViewport(document.body!!) {
        App()
    }
}
