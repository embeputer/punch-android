package com.punch.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.punch.android.ConnectionStatus
import com.punch.android.ui.theme.PunchInk
import com.punch.android.ui.theme.PunchIvory
import com.punch.android.ui.theme.PunchMint
import com.punch.android.ui.theme.PunchMuted
import com.punch.android.ui.theme.PunchStroke

@Composable
fun SettingsScreen(
    packageName: String,
    microphoneGranted: Boolean,
    gatewayUrl: String,
    onGatewayUrlChange: (String) -> Unit,
    gatewayUsername: String,
    onGatewayUsernameChange: (String) -> Unit,
    gatewayApiKey: String,
    onGatewayApiKeyChange: (String) -> Unit,
    gatewayPaired: Boolean,
    connectionStatus: ConnectionStatus,
    gatewayMessage: String,
    onSaveGateway: () -> Unit,
    onTestGateway: () -> Unit,
    onClearGateway: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = PunchIvory,
        unfocusedTextColor = PunchIvory,
        focusedBorderColor = PunchMint.copy(alpha = 0.7f),
        unfocusedBorderColor = PunchStroke,
        focusedLabelColor = PunchMint,
        unfocusedLabelColor = PunchMuted,
        cursorColor = PunchMint,
        focusedContainerColor = Color.White.copy(alpha = 0.06f),
        unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = PunchIvory)
            TextButton(onClick = onBack) { Text("Back", color = PunchMint) }
        }

        Text("ABOUT", style = MaterialTheme.typography.titleSmall, color = PunchMuted)
        Text(
            text = "Package: $packageName",
            color = PunchIvory,
            modifier = Modifier.testTag("package_name"),
        )

        Text("PI", style = MaterialTheme.typography.titleSmall, color = PunchMuted)
        Text(
            text = "This app sends HTTP Basic (username opencode). Use http:// even over Tailscale — port 4096 is plain HTTP. Password is OPENCODE_SERVER_PASSWORD.",
            style = MaterialTheme.typography.bodySmall,
            color = PunchMuted,
        )
        Text(
            text = "Status: ${connectionStatus.name}" +
                if (gatewayPaired) " (paired)" else " (not paired)",
            color = PunchIvory,
            modifier = Modifier.testTag("gateway_connection_status"),
        )
        if (gatewayMessage.isNotBlank()) {
            Text(
                text = gatewayMessage,
                style = MaterialTheme.typography.bodySmall,
                color = PunchMuted,
                modifier = Modifier.testTag("gateway_message"),
            )
        }
        OutlinedTextField(
            value = gatewayUrl,
            onValueChange = onGatewayUrlChange,
            modifier = Modifier.fillMaxWidth().testTag("gateway_url_field"),
            label = { Text("Pi URL") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = gatewayUsername,
            onValueChange = onGatewayUsernameChange,
            modifier = Modifier.fillMaxWidth().testTag("gateway_username_field"),
            label = { Text("Username") },
            placeholder = { Text("opencode") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors,
        )
        OutlinedTextField(
            value = gatewayApiKey,
            onValueChange = onGatewayApiKeyChange,
            modifier = Modifier.fillMaxWidth().testTag("gateway_api_key_field"),
            label = { Text("Password") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val busy = connectionStatus == ConnectionStatus.Connecting
            Button(
                onClick = onSaveGateway,
                enabled = !busy,
                modifier = Modifier.testTag("gateway_save_button"),
                colors = ButtonDefaults.buttonColors(containerColor = PunchMint, contentColor = PunchInk),
            ) { Text("Save") }
            OutlinedButton(
                onClick = onTestGateway,
                enabled = !busy,
                modifier = Modifier.testTag("gateway_test_button"),
            ) {
                Text(if (busy) "Testing…" else "Test", color = PunchIvory)
            }
            OutlinedButton(onClick = onClearGateway, modifier = Modifier.testTag("gateway_clear_button")) {
                Text("Clear", color = PunchIvory)
            }
        }

        Text("MICROPHONE", style = MaterialTheme.typography.titleSmall, color = PunchMuted)
        Text(
            text = "Microphone: ${if (microphoneGranted) "granted" else "not granted"}",
            color = PunchIvory,
            modifier = Modifier.testTag("mic_permission_status"),
        )
        if (!microphoneGranted) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestMicrophone,
                modifier = Modifier.testTag("request_mic_button"),
            ) {
                Text("Request microphone", color = PunchIvory)
            }
        }
    }
}
