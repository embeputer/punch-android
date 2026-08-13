package com.punch.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle as SavedStateBundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import com.punch.android.data.Bundle
import com.punch.android.data.BundleMemory
import com.punch.android.data.ChatMessage
import com.punch.android.data.ChatThread
import com.punch.android.data.NamedText
import com.punch.android.data.OutboundPart
import com.punch.android.data.PromptComposer
import com.punch.android.data.PunchState
import com.punch.android.data.PunchStore
import com.punch.android.data.StoredFile
import com.punch.android.gateway.GatewayClient
import com.punch.android.gateway.GatewayCredentialsStore
import com.punch.android.gateway.GatewayErrors
import com.punch.android.gateway.GatewayException
import com.punch.android.gateway.GatewayUrl
import com.punch.android.input.AndroidSpeechTranscriber
import com.punch.android.input.PttController
import com.punch.android.input.PttHub
import com.punch.android.input.PttUiState
import com.punch.android.ui.BundleScreen
import com.punch.android.ui.ChatScreen
import com.punch.android.ui.SearchChatsScreen
import com.punch.android.ui.SettingsScreen
import com.punch.android.ui.theme.PunchTheme
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    private lateinit var pttController: PttController
    private lateinit var credentialsStore: GatewayCredentialsStore
    private lateinit var punchStore: PunchStore
    private val gatewayClient = GatewayClient()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicReference<Future<*>?>(null)
    private val probeBusy = AtomicBoolean(false)
    @Volatile private var lastProbeAtMs = 0L

    private val pttState = mutableStateOf(PttUiState.Idle)
    private val micGrantedState = mutableStateOf(false)
    private val inputState = mutableStateOf("")
    private val connectionStatusState = mutableStateOf(ConnectionStatus.Disconnected)
    private val gatewayMessageState = mutableStateOf("")
    private val gatewayUrlDraft = mutableStateOf("")
    private val gatewayUserDraft = mutableStateOf("")
    private val gatewayKeyDraft = mutableStateOf("")
    private val gatewayPairedState = mutableStateOf(false)
    private val punchState = mutableStateOf(PunchState())
    private val overlayState = mutableStateOf(Overlay.Chat)
    private val editingBundleId = mutableStateOf<String?>(null)
    private val searchQuery = mutableStateOf("")
    private val attachTarget = mutableStateOf(AttachTarget.Chat)

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGrantedState.value = granted
        Log.d(TAG, "RECORD_AUDIO granted=$granted")
        if (!granted) {
            pttController.cancel("mic_denied")
        }
    }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { importFile(it) }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                pttController.cancel("screen_off")
            }
        }
    }

    override fun onCreate(savedInstanceState: SavedStateBundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setBackgroundDrawable(Color.BLACK.toDrawable())

        credentialsStore = GatewayCredentialsStore(this)
        punchStore = PunchStore(this)
        punchState.value = punchStore.load().let { loaded ->
            if (loaded.activeChatId == null || loaded.chats.none { it.id == loaded.activeChatId }) {
                val chat = newChat()
                loaded.copy(chats = loaded.chats + chat, activeChatId = chat.id)
            } else {
                loaded
            }
        }
        persist()

        gatewayUrlDraft.value = credentialsStore.getOrigin()
        gatewayUserDraft.value = credentialsStore.getUsername()
        gatewayKeyDraft.value = credentialsStore.getPassword()
        gatewayPairedState.value = credentialsStore.isConfigured()
        gatewayClient.sessionId = punchState.value.activeChat()?.sessionId
        micGrantedState.value = hasMicPermission()

        val speech = AndroidSpeechTranscriber(this)
        pttController = PttController(
            speech = speech,
            executor = PttHub.mainExecutor(),
            onStateChanged = { state ->
                runOnUiThread {
                    pttState.value = state
                    if (state.pendingHermesSend && state.transcript.isNotBlank()) {
                        inputState.value = state.transcript
                    }
                }
            },
        )

        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        if (credentialsStore.isConfigured()) {
            testGateway(silent = true)
        }

        setContent {
            PunchTheme {
                val overlay by overlayState
                val state by punchState
                val input by inputState
                val connectionStatus by connectionStatusState
                val ptt by pttState
                val micGranted by micGrantedState
                var gatewayUrl by gatewayUrlDraft
                var gatewayUser by gatewayUserDraft
                var gatewayKey by gatewayKeyDraft
                val gatewayPaired by gatewayPairedState
                val gatewayMessage by gatewayMessageState
                val query by searchQuery
                val bundleId by editingBundleId

                when (overlay) {
                    Overlay.Settings -> SettingsScreen(
                        packageName = packageName,
                        microphoneGranted = micGranted,
                        gatewayUrl = gatewayUrl,
                        onGatewayUrlChange = { gatewayUrl = it },
                        gatewayUsername = gatewayUser,
                        onGatewayUsernameChange = { gatewayUser = it },
                        gatewayApiKey = gatewayKey,
                        onGatewayApiKeyChange = { gatewayKey = it },
                        gatewayPaired = gatewayPaired,
                        connectionStatus = connectionStatus,
                        gatewayMessage = gatewayMessage,
                        onSaveGateway = { saveGateway(gatewayUrl, gatewayUser, gatewayKey) },
                        onTestGateway = { testGateway(silent = false) },
                        onClearGateway = { clearGateway() },
                        onRequestMicrophone = { ensureMicPermission() },
                        onBack = { overlayState.value = Overlay.Chat },
                    )

                    Overlay.Search -> SearchChatsScreen(
                        query = query,
                        onQueryChange = { searchQuery.value = it },
                        chats = state.recents(),
                        onOpenChat = { openChat(it) },
                        onDeleteChat = { deleteChat(it.id) },
                        onBack = { overlayState.value = Overlay.Chat },
                    )

                    Overlay.Bundle -> {
                        val bundle = state.bundles.firstOrNull { it.id == bundleId }
                        if (bundle != null) {
                            BundleScreen(
                                bundle = bundle,
                                chats = state.chatsInBundle(bundle.id),
                                onNameChange = { name -> updateBundle(bundle.id) { it.copy(name = name) } },
                                onInstructionsChange = { text ->
                                    updateBundle(bundle.id) { it.copy(instructions = text) }
                                },
                                onAddFile = {
                                    attachTarget.value = AttachTarget.Bundle
                                    pickFiles.launch(arrayOf("*/*"))
                                },
                                onNewChat = { startChat(bundleId = bundle.id) },
                                onOpenChat = { openChat(it) },
                                onDeleteChat = { deleteChat(it.id) },
                                onBack = { overlayState.value = Overlay.Chat },
                            )
                        }
                    }

                    Overlay.Chat -> ChatScreen(
                        chat = state.activeChat(),
                        input = input,
                        onInputChange = { inputState.value = it },
                        connectionStatus = connectionStatus,
                        pttState = ptt,
                        bundles = state.bundles,
                        recents = state.recents(),
                        onSend = {
                            val prompt = inputState.value.trim()
                            if (prompt.isNotEmpty() || state.activeChat()?.pendingAttachments?.isNotEmpty() == true) {
                                sendToAgent(prompt)
                            }
                        },
                        onStop = {
                            pttController.cancel("stop_button")
                            cancelInFlight("user_stop")
                        },
                        onNewChat = { startChat() },
                        onSearch = { overlayState.value = Overlay.Search },
                        onNewBundle = { startBundle() },
                        onOpenBundle = {
                            editingBundleId.value = it.id
                            overlayState.value = Overlay.Bundle
                        },
                        onOpenChat = { openChat(it) },
                        onDeleteBundle = { deleteBundle(it.id) },
                        onDeleteChat = { deleteChat(it.id) },
                        onOpenSettings = { overlayState.value = Overlay.Settings },
                        onAttach = {
                            attachTarget.value = AttachTarget.Chat
                            pickFiles.launch(arrayOf("*/*"))
                        },
                        onUseBundle = { bundle ->
                            updateActiveChat { chat ->
                                chat.copy(crossRefBundleIds = (chat.crossRefBundleIds + bundle.id).distinct())
                            }
                        },
                        onMicDown = { beginPttFromUi() },
                        onMicUp = { pttController.onPressUp() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        micGrantedState.value = hasMicPermission()
    }

    override fun onPause() {
        if (::pttController.isInitialized) {
            pttController.cancel("focus_loss")
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        cancelInFlight("destroy")
        if (::pttController.isInitialized) {
            pttController.cancel("activity_destroy")
        }
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun persist() {
        punchStore.save(punchState.value)
    }

    private fun mutate(block: (PunchState) -> PunchState) {
        punchState.value = block(punchState.value)
        persist()
    }

    private fun newChat(bundleId: String? = null): ChatThread {
        val now = System.currentTimeMillis()
        return ChatThread(
            id = punchStore.newId(),
            title = "New chat",
            bundleId = bundleId,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun startChat(bundleId: String? = null) {
        val chat = newChat(bundleId)
        mutate { state ->
            state.copy(chats = listOf(chat) + state.chats, activeChatId = chat.id)
        }
        inputState.value = ""
        gatewayClient.sessionId = null
        overlayState.value = Overlay.Chat
    }

    private fun openChat(chat: ChatThread) {
        mutate { it.copy(activeChatId = chat.id) }
        gatewayClient.sessionId = chat.sessionId
        inputState.value = ""
        overlayState.value = Overlay.Chat
    }

    private fun deleteChat(id: String) {
        mutate { state ->
            val next = state.withoutChat(id)
            if (next.activeChatId != null) {
                next
            } else {
                val chat = newChat()
                next.copy(chats = listOf(chat), activeChatId = chat.id)
            }
        }
        gatewayClient.sessionId = punchState.value.activeChat()?.sessionId
        inputState.value = ""
    }

    private fun deleteBundle(id: String) {
        mutate { it.withoutBundle(id) }
        if (editingBundleId.value == id) {
            editingBundleId.value = null
            overlayState.value = Overlay.Chat
        }
    }

    private fun startBundle() {
        val bundle = Bundle(
            id = punchStore.newId(),
            name = "Untitled bundle",
            createdAt = System.currentTimeMillis(),
        )
        mutate { it.copy(bundles = listOf(bundle) + it.bundles) }
        editingBundleId.value = bundle.id
        overlayState.value = Overlay.Bundle
    }

    private fun updateActiveChat(block: (ChatThread) -> ChatThread) {
        mutate { state ->
            val id = state.activeChatId ?: return@mutate state
            state.copy(
                chats = state.chats.map { chat ->
                    if (chat.id == id) block(chat).copy(updatedAt = System.currentTimeMillis()) else chat
                },
            )
        }
    }

    private fun updateBundle(id: String, block: (Bundle) -> Bundle) {
        mutate { state ->
            state.copy(bundles = state.bundles.map { if (it.id == id) block(it) else it })
        }
    }

    private fun importFile(uri: Uri) {
        val id = punchStore.newId()
        val dest = punchStore.attachmentFile(id)
        val name = queryDisplayName(uri) ?: "file"
        val mime = contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: return
        val stored = StoredFile(id = id, name = name, mime = mime, path = dest.absolutePath)
        when (attachTarget.value) {
            AttachTarget.Chat -> updateActiveChat { it.copy(pendingAttachments = it.pendingAttachments + stored) }
            AttachTarget.Bundle -> {
                val bundleId = editingBundleId.value ?: return
                updateBundle(bundleId) { it.copy(files = it.files + stored) }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun memoriesFor(chat: ChatThread): List<BundleMemory> {
        val ids = buildList {
            chat.bundleId?.let { add(it) }
            addAll(chat.crossRefBundleIds)
        }.distinct()
        return ids.mapNotNull { id ->
            val bundle = punchState.value.bundles.firstOrNull { it.id == id } ?: return@mapNotNull null
            BundleMemory(
                name = bundle.name,
                instructions = bundle.instructions,
                files = bundle.files.map { file ->
                    NamedText(name = file.name, text = readTextIfPossible(file))
                },
            )
        }
    }

    private fun readTextIfPossible(file: StoredFile): String {
        if (!file.mime.startsWith("text/") && !file.name.endsWith(".md") && !file.name.endsWith(".txt")) {
            return ""
        }
        val disk = File(file.path)
        if (!disk.exists() || disk.length() > 200_000) return ""
        return runCatching { disk.readText() }.getOrDefault("")
    }

    private fun extraParts(files: List<StoredFile>): List<OutboundPart> {
        return files.mapNotNull { file ->
            val disk = File(file.path)
            if (!disk.exists() || disk.length() > 4_000_000L) return@mapNotNull null
            val data = Base64.encodeToString(disk.readBytes(), Base64.NO_WRAP)
            val type = if (file.mime.startsWith("image/")) "image" else "file"
            OutboundPart(
                type = type,
                mimeType = file.mime,
                filename = file.name,
                data = data,
            )
        }
    }

    private fun saveGateway(rawUrl: String, username: String, password: String) {
        val normalized = GatewayUrl.normalizeOrigin(rawUrl)
        if (normalized.isFailure) {
            gatewayMessageState.value = normalized.exceptionOrNull()?.message ?: "Invalid URL"
            gatewayPairedState.value = false
            connectionStatusState.value = ConnectionStatus.Disconnected
            return
        }
        val origin = normalized.getOrThrow()
        credentialsStore.save(origin, username.trim().ifBlank { GatewayErrors.DEFAULT_USERNAME }, password.trim())
        gatewayUrlDraft.value = origin
        gatewayUserDraft.value = username.trim().ifBlank { GatewayErrors.DEFAULT_USERNAME }
        gatewayKeyDraft.value = password.trim()
        gatewayPairedState.value = true
        gatewayClient.sessionId = punchState.value.activeChat()?.sessionId
        gatewayMessageState.value = "Saved. Testing connection…"
        testGateway(silent = false)
    }

    private fun clearGateway() {
        cancelInFlight("clear")
        credentialsStore.clear()
        gatewayClient.sessionId = null
        gatewayUrlDraft.value = ""
        gatewayUserDraft.value = ""
        gatewayKeyDraft.value = ""
        gatewayPairedState.value = false
        connectionStatusState.value = ConnectionStatus.Disconnected
        gatewayMessageState.value = "Pi pairing cleared"
    }

    private fun testGateway(silent: Boolean) {
        if (!credentialsStore.isConfigured()) {
            if (!silent) {
                gatewayMessageState.value = "Save a Pi gateway URL first"
            }
            connectionStatusState.value = ConnectionStatus.Disconnected
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastProbeAtMs < 2_000L) {
            if (!silent) {
                gatewayMessageState.value = "Slow down — wait a second before testing again"
            }
            return
        }
        if (!probeBusy.compareAndSet(false, true)) {
            if (!silent) {
                gatewayMessageState.value = "Already testing…"
            }
            return
        }
        lastProbeAtMs = now
        connectionStatusState.value = ConnectionStatus.Connecting
        if (!silent) {
            gatewayMessageState.value = "Testing…"
        }
        ioExecutor.submit {
            try {
                val health = gatewayClient.health(
                    credentialsStore.getOrigin(),
                    credentialsStore.getUsername(),
                    credentialsStore.getPassword(),
                )
                runOnUiThread {
                    if (health.ok) {
                        connectionStatusState.value = ConnectionStatus.Connected
                        gatewayPairedState.value = true
                        if (!silent) {
                            gatewayMessageState.value = "Connected (${health.detail})"
                        }
                    } else {
                        connectionStatusState.value = ConnectionStatus.Disconnected
                        if (!silent) {
                            gatewayMessageState.value = "Unreachable: ${health.detail}"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "probe ended: ${e.javaClass.simpleName}")
                runOnUiThread {
                    connectionStatusState.value = ConnectionStatus.Disconnected
                    if (!silent) {
                        gatewayMessageState.value = "Unreachable: ${e.message ?: "error"}"
                    }
                }
            } finally {
                probeBusy.set(false)
            }
        }
    }

    private fun sendToAgent(prompt: String) {
        if (!credentialsStore.isConfigured()) {
            updateActiveChat { chat ->
                chat.copy(
                    messages = chat.messages + ChatMessage(
                        id = punchStore.newId(),
                        role = "assistant",
                        text = "Not paired. Open Settings and save a Pi gateway URL.",
                    ),
                    workingLabel = "",
                )
            }
            connectionStatusState.value = ConnectionStatus.Disconnected
            return
        }
        val chat = punchState.value.activeChat() ?: return
        val display = prompt.ifBlank { chat.pendingAttachments.joinToString { it.name } }
        val composed = PromptComposer.compose(display, memoriesFor(chat))
        val attachments = chat.pendingAttachments
        val userMessage = ChatMessage(
            id = punchStore.newId(),
            role = "user",
            text = display,
            attachmentNames = attachments.map { it.name },
        )
        val title = if (chat.title == "New chat" && display.isNotBlank()) {
            display.take(42)
        } else {
            chat.title
        }
        updateActiveChat {
            it.copy(
                title = title,
                messages = it.messages + userMessage,
                pendingAttachments = emptyList(),
                workingLabel = "Waiting for Punch…",
            )
        }
        inputState.value = ""
        connectionStatusState.value = ConnectionStatus.Connecting
        gatewayClient.sessionId = chat.sessionId

        submitIo {
            try {
                val result = gatewayClient.prompt(
                    origin = credentialsStore.getOrigin(),
                    username = credentialsStore.getUsername(),
                    password = credentialsStore.getPassword(),
                    text = composed,
                    extraParts = extraParts(attachments),
                )
                runOnUiThread {
                    result.sessionId?.let { credentialsStore.saveSessionId(it) }
                    updateActiveChat { current ->
                        current.copy(
                            sessionId = result.sessionId ?: current.sessionId,
                            workingLabel = "",
                            messages = current.messages + ChatMessage(
                                id = punchStore.newId(),
                                role = "assistant",
                                text = result.text.ifBlank { "(empty response)" },
                            ),
                        )
                    }
                    connectionStatusState.value = ConnectionStatus.Connected
                    gatewayMessageState.value = "Connected"
                }
            } catch (e: Exception) {
                if (e is java.io.IOException && e.message?.contains("abort", true) == true) {
                    return@submitIo
                }
                runOnUiThread {
                    connectionStatusState.value = ConnectionStatus.Disconnected
                    val detail = when (e) {
                        is GatewayException -> e.message ?: "Pi error"
                        else -> e.message ?: "Request failed"
                    }
                    updateActiveChat { current ->
                        current.copy(
                            workingLabel = "",
                            messages = current.messages + ChatMessage(
                                id = punchStore.newId(),
                                role = "assistant",
                                text = "Error: $detail",
                            ),
                        )
                    }
                    gatewayMessageState.value = detail
                }
            }
        }
    }

    private fun submitIo(block: () -> Unit) {
        cancelInFlight("replace")
        val future = ioExecutor.submit {
            try {
                block()
            } catch (e: Exception) {
                Log.d(TAG, "io task ended: ${e.javaClass.simpleName}")
            }
        }
        inFlight.set(future)
    }

    private fun cancelInFlight(reason: String) {
        Log.d(TAG, "cancelInFlight reason=$reason")
        if (credentialsStore.isConfigured()) {
            gatewayClient.abort(
                credentialsStore.getOrigin(),
                credentialsStore.getUsername(),
                credentialsStore.getPassword(),
            )
        } else {
            gatewayClient.cancel()
        }
        inFlight.getAndSet(null)?.cancel(true)
        if (connectionStatusState.value == ConnectionStatus.Connecting) {
            connectionStatusState.value =
                if (credentialsStore.isConfigured()) {
                    ConnectionStatus.Connected
                } else {
                    ConnectionStatus.Disconnected
                }
            updateActiveChat { it.copy(workingLabel = "") }
        }
    }

    private fun beginPttFromUi() {
        if (!ensureMicPermission()) return
        pttController.onPressDown(isRepeat = false)
    }

    private fun ensureMicPermission(): Boolean {
        if (hasMicPermission()) {
            micGrantedState.value = true
            return true
        }
        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private enum class Overlay { Chat, Settings, Search, Bundle }

    private enum class AttachTarget { Chat, Bundle }

    companion object {
        private const val TAG = "PunchMain"
    }
}
