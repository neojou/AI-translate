package com.neojou.aitranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * Local model settings.
 *
 * Save writes the fields. Check uses the text on screen and does not write.
 * Dismissing the dialog cancels a check that is still running.
 * Click outside the card or press Escape to dismiss.
 */
@Composable
internal fun AiSettingsDialog(onDismiss: () -> Unit) {
    val loaded = remember { loadAiSettings() }
    var url by remember { mutableStateOf(loaded.ollamaBaseUrl) }
    var model by remember { mutableStateOf(loaded.ollamaModel) }
    var numCtx by remember { mutableStateOf(loaded.ollamaNumCtx.toString()) }
    var notice by remember { mutableStateOf<AiSettingsNotice?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(min = 420.dp, max = 520.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "AI Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ollama URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = numCtx,
                    onValueChange = { numCtx = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("num_ctx") },
                    singleLine = true,
                )
                Text(
                    text = "Check loads this model with num_ctx. A large value can use a lot of memory. The app does not lower it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            when (val outcome = saveAiSettingsFields(url, model, numCtx)) {
                                is AiSettingsNotice.Saved -> {
                                    url = outcome.settings.ollamaBaseUrl
                                    model = outcome.settings.ollamaModel
                                    numCtx = outcome.settings.ollamaNumCtx.toString()
                                    notice = outcome
                                }
                                else -> notice = outcome
                            }
                        },
                    ) {
                        Text("Save")
                    }
                    Button(
                        onClick = {
                            val urlNow = url
                            val modelNow = model
                            val numCtxNow = numCtx
                            checking = true
                            notice = AiSettingsNotice.Checking
                            scope.launch {
                                val outcome = checkAiSettingsFields(urlNow, modelNow, numCtxNow)
                                checking = false
                                notice = outcome
                            }
                        },
                        enabled = !checking,
                    ) {
                        Text("Check Ollama")
                    }
                }
                when (val current = notice) {
                    null -> Unit
                    AiSettingsNotice.Checking -> Text("Checking...")
                    is AiSettingsNotice.Saved -> Text("Saved.")
                    is AiSettingsNotice.Success -> Text(current.message)
                    is AiSettingsNotice.Failure -> Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
