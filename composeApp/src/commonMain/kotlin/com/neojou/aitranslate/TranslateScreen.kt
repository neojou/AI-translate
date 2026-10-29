package com.neojou.aitranslate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.neojou.tools.ui.menu.MyTopMenuBar
import com.neojou.tools.ui.menu.MyTopMenuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val RailWidth = 84.dp
private val MinPaneWidth = 240.dp

/**
 * Two-pane translation desk.
 *
 * The left pane is the source. The right pane is Traditional Chinese.
 * Desktop sends one piece at a time. Wasm can load, edit, and save, and leaves translation off.
 */
@Composable
internal fun TranslateScreen(
    onAbout: () -> Unit,
    onOpenAiSettings: (() -> Unit)?,
) {
    val canTranslate = aiSettingsAvailable()
    var language by remember { mutableStateOf(SourceLanguage.English) }
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var loadedName by remember { mutableStateOf<String?>(null) }
    var truncationNote by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var confirmLoad by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var pieces by remember { mutableStateOf<List<SourcePiece>>(emptyList()) }
    var nextIndex by remember { mutableStateOf(0) }
    var resumable by remember { mutableStateOf(false) }
    var runSource by remember { mutableStateOf("") }
    var runLanguage by remember { mutableStateOf(SourceLanguage.English) }
    var sessionStamp by remember { mutableStateOf("") }
    var lastTranslation by remember { mutableStateOf<String?>(null) }
    var fraction by remember { mutableFloatStateOf(0.5f) }
    val scope = rememberCoroutineScope()
    val job = remember { mutableStateOf<Job?>(null) }

    val continueReady = resumable &&
        !running &&
        source == runSource &&
        language == runLanguage &&
        pieces.isNotEmpty() &&
        nextIndex < pieces.size
    val showProgress = pieces.isNotEmpty() && source == runSource && language == runLanguage

    fun noteFor(limited: LimitedText) {
        truncationNote = limited.truncated || (truncationNote && codePointCount(limited.text) >= SOURCE_CHAR_LIMIT)
    }

    fun begin(from: Int, fresh: Boolean) {
        if (running || !canTranslate || source.isBlank()) return
        val textNow = source
        val languageNow = language
        val restart = fresh || textNow != runSource || languageNow != runLanguage || pieces.isEmpty()
        val runPieces = if (restart) splitPassage(textNow, languageNow) else pieces
        if (runPieces.isEmpty()) return
        val start = if (restart) 0 else from.coerceIn(0, runPieces.size)
        if (start >= runPieces.size) return
        if (start == 0) {
            target = ""
            lastTranslation = null
            sessionStamp = newSessionStamp()
        }
        pieces = runPieces
        runSource = textNow
        runLanguage = languageNow
        statusError = null
        running = true
        val stampNow = sessionStamp
        var carried = lastTranslation
        job.value = scope.launch {
            try {
                for (index in start until runPieces.size) {
                    ensureActive()
                    val previous = if (index == 0) null else runPieces[index - 1].text
                    val step = translatePassage(
                        language = languageNow,
                        reference = translationReference(previous, carried),
                        passage = runPieces[index].text,
                        index = index + 1,
                        stamp = stampNow,
                    )
                    ensureActive()
                    when (step) {
                        is TranslateStep.Failed -> {
                            statusError = step.message
                            nextIndex = index
                            resumable = true
                            return@launch
                        }
                        is TranslateStep.Ok -> {
                            val separator = if (target.isEmpty() || index == 0) {
                                ""
                            } else if (runPieces[index - 1].paragraphBreakAfter) {
                                "\n\n"
                            } else {
                                ""
                            }
                            val limited = limitText(target + separator + step.translation.trim())
                            target = limited.text
                            if (limited.truncated) truncationNote = true
                            carried = step.translation
                            lastTranslation = step.translation
                            nextIndex = index + 1
                            if (limited.truncated) {
                                resumable = false
                                return@launch
                            }
                        }
                    }
                }
                nextIndex = runPieces.size
                resumable = false
            } catch (error: CancellationException) {
                resumable = nextIndex < runPieces.size
                throw error
            } finally {
                running = false
            }
        }
    }

    fun loadNow() {
        scope.launch {
            when (val loaded = interpretPickedFile(pickSourceFile())) {
                LoadResult.Cancelled -> Unit
                LoadResult.NotUtf8 -> statusError = NOT_UTF8_NOTE
                is LoadResult.Failed -> statusError = loaded.message
                is LoadResult.Ready -> {
                    val limited = limitText(loaded.text)
                    source = limited.text
                    truncationNote = limited.truncated
                    loadedName = loaded.fileName
                    statusError = null
                    resumable = false
                    pieces = emptyList()
                    nextIndex = 0
                }
            }
        }
    }

    val menus = buildList {
        add(MyTopMenuItem(id = "about", label = "About", onClick = onAbout))
        add(
            MyTopMenuItem(
                id = "file",
                label = "檔案",
                children = listOf(
                    MyTopMenuItem(
                        id = "load",
                        label = "載入",
                        enabled = !running,
                        onClick = {
                            if (running) return@MyTopMenuItem
                            if (source.isNotEmpty()) confirmLoad = true else loadNow()
                        },
                    ),
                    MyTopMenuItem(
                        id = "save",
                        label = "儲存",
                        enabled = target.isNotBlank(),
                        onClick = {
                            val text = target
                            val name = translationFileName(loadedName)
                            scope.launch {
                                when (val saved = writeTranslation(name, text)) {
                                    SaveResult.Saved, SaveResult.Cancelled -> Unit
                                    is SaveResult.Failed -> statusError = saved.message.ifBlank { SAVE_FAILED_NOTE }
                                }
                            }
                        },
                    ),
                ),
            ),
        )
        if (onOpenAiSettings != null) {
            add(
                MyTopMenuItem(
                    id = "settings",
                    label = "Settings",
                    children = listOf(
                        MyTopMenuItem(
                            id = "ai-settings",
                            label = "AI Settings",
                            onClick = onOpenAiSettings,
                        ),
                    ),
                ),
            )
        }
    }

    Scaffold(
        containerColor = TranslatePalette.paper,
        topBar = { MyTopMenuBar(items = menus) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(TranslatePalette.paper),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TranslatePalette.line),
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val available = (maxWidth - RailWidth).coerceAtLeast(0.dp)
                val minEach = if (available >= MinPaneWidth * 2) MinPaneWidth else available / 2
                val minFraction = if (available.value == 0f) {
                    0.5f
                } else {
                    (minEach.value / available.value).coerceIn(0f, 0.5f)
                }
                val shown = fraction.coerceIn(minFraction, 1f - minFraction)
                val leftWidth = available * shown
                val density = LocalDensity.current
                val availablePx = with(density) { available.toPx() }
                Row(modifier = Modifier.fillMaxSize()) {
                    SourcePane(
                        modifier = Modifier.width(leftWidth).fillMaxHeight(),
                        language = language,
                        text = source,
                        readOnly = running,
                        onLanguage = { next -> if (!running) language = next },
                        onText = { raw ->
                            if (running) return@SourcePane
                            val limited = limitText(raw)
                            source = limited.text
                            noteFor(limited)
                        },
                    )
                    SplitRail(
                        running = running,
                        label = when {
                            running -> "停止"
                            continueReady -> "繼續"
                            else -> "翻譯"
                        },
                        enabled = canTranslate && (running || source.isNotBlank()),
                        availablePx = availablePx,
                        fraction = shown,
                        minFraction = minFraction,
                        onFraction = { fraction = it },
                        onClick = {
                            if (!canTranslate) return@SplitRail
                            if (running) {
                                job.value?.cancel()
                            } else if (continueReady) {
                                begin(nextIndex, fresh = false)
                            } else {
                                begin(0, fresh = true)
                            }
                        },
                    )
                    TargetPane(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        text = target,
                        readOnly = running,
                        onText = { raw ->
                            if (running) return@TargetPane
                            val limited = limitText(raw)
                            target = limited.text
                            if (limited.truncated) truncationNote = true
                        },
                    )
                }
            }
            StatusLine(
                language = language,
                sourceCount = codePointCount(source),
                error = statusError,
                truncationNote = truncationNote,
                desktopOnly = !canTranslate,
                progress = if (showProgress) {
                    val shownIndex = if (running) nextIndex + 1 else nextIndex
                    "$shownIndex / ${pieces.size}"
                } else {
                    null
                },
            )
        }
    }

    if (confirmLoad) {
        ReplaceSourceDialog(
            onDismiss = { confirmLoad = false },
            onReplace = {
                confirmLoad = false
                loadNow()
            },
        )
    }
}

@Composable
private fun SourcePane(
    modifier: Modifier,
    language: SourceLanguage,
    text: String,
    readOnly: Boolean,
    onLanguage: (SourceLanguage) -> Unit,
    onText: (String) -> Unit,
) {
    Column(modifier = modifier.background(TranslatePalette.page)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceLanguage.entries.forEach { entry ->
                LanguageTab(
                    label = entry.label,
                    selected = entry == language,
                    onClick = { onLanguage(entry) },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatCount(codePointCount(text)),
                color = TranslatePalette.muted,
                fontSize = 12.sp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TranslatePalette.line),
        )
        Editor(
            text = text,
            readOnly = readOnly,
            placeholder = "貼上或載入原文",
            onText = onText,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun TargetPane(
    modifier: Modifier,
    text: String,
    readOnly: Boolean,
    onText: (String) -> Unit,
) {
    Column(modifier = modifier.background(TranslatePalette.page)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "繁體中文",
                color = TranslatePalette.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatCount(codePointCount(text)),
                color = TranslatePalette.muted,
                fontSize = 12.sp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TranslatePalette.line),
        )
        Editor(
            text = text,
            readOnly = readOnly,
            placeholder = "譯文會出現在這裡",
            onText = onText,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun LanguageTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = if (selected) TranslatePalette.accent else TranslatePalette.muted,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(28.dp)
                .background(if (selected) TranslatePalette.accent else TranslatePalette.page),
        )
    }
}

@Composable
private fun Editor(
    text: String,
    readOnly: Boolean,
    placeholder: String,
    onText: (String) -> Unit,
    modifier: Modifier,
) {
    val fontFamily = appFontFamily()
    val scroll = rememberScrollState()
    val style = TextStyle(
        fontFamily = fontFamily,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        color = TranslatePalette.ink,
    )
    BoxWithConstraints(modifier = modifier) {
        val paneHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            BasicTextField(
                value = text,
                onValueChange = onText,
                readOnly = readOnly,
                textStyle = style,
                cursorBrush = SolidColor(TranslatePalette.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = paneHeight)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(text = placeholder, style = style.copy(color = TranslatePalette.muted))
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun SplitRail(
    running: Boolean,
    label: String,
    enabled: Boolean,
    availablePx: Float,
    fraction: Float,
    minFraction: Float,
    onFraction: (Float) -> Unit,
    onClick: () -> Unit,
) {
    val latestFraction = rememberUpdatedState(fraction)
    val latestAvailable = rememberUpdatedState(availablePx)
    val latestMin = rememberUpdatedState(minFraction)
    val latestOnFraction = rememberUpdatedState(onFraction)
    Row(modifier = Modifier.fillMaxHeight().width(RailWidth)) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(TranslatePalette.line),
        )
        Column(
            modifier = Modifier
                .width(RailWidth - 2.dp)
                .fillMaxHeight()
                .background(TranslatePalette.paper)
                .pointerHoverIcon(PointerIcon.Crosshair)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, amount ->
                        change.consume()
                        val available = latestAvailable.value
                        if (available <= 0f) return@detectHorizontalDragGestures
                        val min = latestMin.value
                        val next = (latestFraction.value + amount / available).coerceIn(min, 1f - min)
                        latestOnFraction.value(next)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .height(34.dp)
                    .fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) TranslatePalette.clay else TranslatePalette.accent,
                    contentColor = TranslatePalette.page,
                    disabledContainerColor = TranslatePalette.line,
                    disabledContentColor = TranslatePalette.muted,
                ),
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(2.dp)
                            .background(TranslatePalette.line, RoundedCornerShape(1.dp)),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(TranslatePalette.line),
        )
    }
}

@Composable
private fun StatusLine(
    language: SourceLanguage,
    sourceCount: Int,
    error: String?,
    truncationNote: Boolean,
    desktopOnly: Boolean,
    progress: String?,
) {
    val detail = error ?: listOfNotNull(
        progress,
        TRUNCATED_NOTE.takeIf { truncationNote },
        DESKTOP_ONLY_NOTE.takeIf { desktopOnly },
    ).joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TranslatePalette.paper),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TranslatePalette.line),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${language.label} · ${formatCount(sourceCount)} 字",
                color = TranslatePalette.muted,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    color = if (error != null) TranslatePalette.clay else TranslatePalette.muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ReplaceSourceDialog(
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = TranslatePalette.page,
            modifier = Modifier.widthIn(min = 320.dp, max = 420.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "取代左欄？",
                    style = MaterialTheme.typography.titleMedium,
                    color = TranslatePalette.ink,
                )
                Text(
                    text = "左欄已有文字。載入會把現在的內容換掉。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TranslatePalette.ink,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = onReplace) {
                        Text("取代", color = TranslatePalette.accent)
                    }
                }
            }
        }
    }
}
