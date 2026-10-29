package com.neojou.aitranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Primary application shell.
 *
 * The desk is shared. About opens the shared about dialog.
 * On Desktop, Settings → AI Settings opens the local model dialog. Wasm does not show Settings.
 */
@Composable
fun HomeScreen(about: AboutRequest) {
    var aiSettingsOpen by remember { mutableStateOf(false) }
    TranslateScreen(
        onAbout = about::show,
        onOpenAiSettings = if (aiSettingsAvailable()) {
            { aiSettingsOpen = true }
        } else {
            null
        },
    )
    if (aiSettingsOpen) {
        AiSettingsDialog(onDismiss = { aiSettingsOpen = false })
    }
}

/**
 * About content shared by the in-app menu and the macOS application menu.
 *
 * Two lines only: product name, then `Version` plus [AppVersion.NAME].
 * Click outside the card or press Escape to dismiss.
 */
@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(min = 320.dp, max = 480.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 168.dp)
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = AppVersion.APP_NAME,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Version ${AppVersion.NAME}",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

