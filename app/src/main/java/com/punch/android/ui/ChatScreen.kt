package com.punch.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.punch.android.ConnectionStatus
import com.punch.android.data.Bundle
import com.punch.android.data.ChatMessage
import com.punch.android.data.ChatThread
import com.punch.android.input.PttUiState
import com.punch.android.ui.theme.PunchBlack
import com.punch.android.ui.theme.PunchBubble
import com.punch.android.ui.theme.PunchCharcoal
import com.punch.android.ui.theme.PunchInk
import com.punch.android.ui.theme.PunchIvory
import com.punch.android.ui.theme.PunchMint
import com.punch.android.ui.theme.PunchMuted
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    chat: ChatThread?,
    input: String,
    onInputChange: (String) -> Unit,
    connectionStatus: ConnectionStatus,
    pttState: PttUiState,
    bundles: List<Bundle>,
    recents: List<ChatThread>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    onNewBundle: () -> Unit,
    onOpenBundle: (Bundle) -> Unit,
    onOpenChat: (ChatThread) -> Unit,
    onDeleteBundle: (Bundle) -> Unit,
    onDeleteChat: (ChatThread) -> Unit,
    onOpenSettings: () -> Unit,
    onAttach: () -> Unit,
    onUseBundle: (Bundle) -> Unit,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val busy = connectionStatus == ConnectionStatus.Connecting
    val canSend = (input.trim().isNotEmpty() || (chat?.pendingAttachments?.isNotEmpty() == true)) && !busy

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = PunchBlack,
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            ) {
                PunchDrawer(
                    bundles = bundles,
                    recents = recents,
                    activeChatId = chat?.id,
                    onClose = { scope.launch { drawerState.close() } },
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        onNewChat()
                    },
                    onSearch = {
                        scope.launch { drawerState.close() }
                        onSearch()
                    },
                    onNewBundle = {
                        scope.launch { drawerState.close() }
                        onNewBundle()
                    },
                    onOpenBundle = {
                        scope.launch { drawerState.close() }
                        onOpenBundle(it)
                    },
                    onOpenChat = {
                        scope.launch { drawerState.close() }
                        onOpenChat(it)
                    },
                    onDeleteBundle = onDeleteBundle,
                    onDeleteChat = onDeleteChat,
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(PunchBlack)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(PunchMint.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.92f),
                        radius = size.minDimension * 0.85f,
                    ),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                TopBar(
                    onMenu = { scope.launch { drawerState.open() } },
                    onNewChat = onNewChat,
                    onSettings = onOpenSettings,
                )
                val messages = chat?.messages.orEmpty()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty() && !busy) {
                        EmptyHero(
                            listening = pttState.listeningVisible,
                            greeting = remember(chat?.id) {
                                val seed = chat?.id.hashCode().toLong()
                                emptyGreetings[kotlin.math.abs(seed % emptyGreetings.size).toInt()]
                            },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        MessageList(
                            messages = messages,
                            workingLabel = chat?.workingLabel.orEmpty().ifBlank {
                                if (busy) "Waiting for Punch…" else ""
                            },
                        )
                    }
                }
                if (chat?.pendingAttachments?.isNotEmpty() == true) {
                    Text(
                        text = chat.pendingAttachments.joinToString { it.name },
                        color = PunchMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                if (pttState.listeningVisible) {
                    Text(
                        text = "listening…",
                        color = PunchMint,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                            .testTag("ptt_listening"),
                    )
                }
                AskBar(
                    input = input,
                    onInputChange = onInputChange,
                    canSend = canSend,
                    busy = busy,
                    bundles = bundles,
                    onAttach = onAttach,
                    onUseBundle = onUseBundle,
                    onSend = onSend,
                    onStop = onStop,
                    onMicDown = onMicDown,
                    onMicUp = onMicUp,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun TopBar(
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onMenu,
            modifier = Modifier
                .testTag("menu_button")
                .semantics { contentDescription = "Menu" },
        ) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = PunchIvory)
        }
            Text(
                text = "Punch",
                color = PunchIvory,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .testTag("app_title"),
            )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF141414)),
        ) {
            Row {
                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier.semantics { contentDescription = "New chat" },
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "New chat", tint = PunchIvory)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = PunchIvory)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                menuOpen = false
                                onSettings()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHero(
    listening: Boolean,
    greeting: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .testTag("empty_hero"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PunchMark()
        Spacer(Modifier.height(18.dp))
        Text(
            text = if (listening) "Listening…" else greeting,
            color = PunchIvory,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp),
            modifier = Modifier.testTag("empty_greeting"),
        )
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    workingLabel: String,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, workingLabel) {
        val last = messages.lastIndex + if (workingLabel.isNotBlank()) 1 else 0
        if (last >= 0) listState.animateScrollToItem(last)
    }
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("response_area"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            if (message.role == "user") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = message.text,
                        color = PunchIvory,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(PunchBubble)
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    MarkdownText(text = message.text)
                    Row(Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) {
                            Text("Copy", color = PunchMuted)
                        }
                    }
                }
            }
        }
        if (workingLabel.isNotBlank()) {
            item("working") {
                Text(
                    text = "✦  $workingLabel",
                    color = PunchIvory,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("working_label"),
                )
            }
        }
        item("disclaimer") {
            Text(
                text = "Punch can make mistakes.",
                color = PunchMuted.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AskBar(
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    busy: Boolean,
    bundles: List<Bundle>,
    onAttach: () -> Unit,
    onUseBundle: (Bundle) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
) {
    var plusOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(50))
            .background(PunchCharcoal)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconButton(
                onClick = { plusOpen = true },
                modifier = Modifier
                    .testTag("attach_button")
                    .semantics { contentDescription = "Add" },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = PunchIvory)
            }
            DropdownMenu(expanded = plusOpen, onDismissRequest = { plusOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Attach file") },
                    onClick = {
                        plusOpen = false
                        onAttach()
                    },
                )
                bundles.forEach { bundle ->
                    DropdownMenuItem(
                        text = { Text("Use ${bundle.name}") },
                        onClick = {
                            plusOpen = false
                            onUseBundle(bundle)
                        },
                    )
                }
            }
        }
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .testTag("agent_input"),
            textStyle = TextStyle(color = PunchIvory, fontSize = 16.sp),
            cursorBrush = SolidColor(PunchMint),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            decorationBox = { inner ->
                Box {
                    if (input.isEmpty()) {
                        Text("Ask Punch", color = PunchMuted, fontSize = 16.sp)
                    }
                    inner()
                }
            },
        )
        IconButton(
            onClick = {},
            modifier = Modifier
                .testTag("mic_button")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onMicDown()
                        waitForUpOrCancellation()
                        onMicUp()
                    }
                }
                .semantics { contentDescription = "Hold to talk" },
        ) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val h = size.height
                drawRoundRect(
                    color = PunchIvory,
                    topLeft = Offset(w * 0.35f, h * 0.08f),
                    size = Size(w * 0.30f, h * 0.48f),
                    cornerRadius = CornerRadius(w * 0.15f),
                )
                drawArc(
                    color = PunchIvory,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.22f, h * 0.28f),
                    size = Size(w * 0.56f, h * 0.42f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                )
                drawLine(
                    color = PunchIvory,
                    start = Offset(w * 0.5f, h * 0.70f),
                    end = Offset(w * 0.5f, h * 0.86f),
                    strokeWidth = 2.dp.toPx(),
                )
                drawLine(
                    color = PunchIvory,
                    start = Offset(w * 0.32f, h * 0.86f),
                    end = Offset(w * 0.68f, h * 0.86f),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
        IconButton(
            onClick = { if (busy) onStop() else if (canSend) onSend() },
            enabled = busy || canSend,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (busy || canSend) PunchMint else Color(0xFF3A3A3A))
                .testTag(if (busy) "stop_button" else "send_button"),
        ) {
            if (busy) {
                Canvas(Modifier.size(16.dp)) {
                    val pad = size.minDimension * 0.18f
                    drawRoundRect(
                        color = PunchInk,
                        topLeft = Offset(pad, pad),
                        size = Size(size.width - pad * 2, size.height - pad * 2),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) PunchInk else PunchMuted.copy(alpha = 0.45f),
                )
            }
        }
    }
}
