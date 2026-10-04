package dev.agentle.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The chat tab. Until ChatGPT is connected it explains what the chat does and offers the connection screen. */
@Composable
fun ChatScreen(viewModel: ChatViewModel, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val sharingAllowed by viewModel.sharingAllowed.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().imePadding().padding(16.dp)) {
        if (!connected) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Talk to ChatGPT about your data", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Connect your ChatGPT account to ask questions about your phone and health data, and to have " +
                            "ChatGPT build dashboards that appear in the sidebar.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onConnect) { Text("Connect ChatGPT") }
                }
            }
        }
        if (connected && !sharingAllowed) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Share your phone usage with ChatGPT?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "To answer questions about your phone, Agentle sends ChatGPT summaries from the last 4 weeks: " +
                            "daily screen time, unlocks and your most used apps (by name). Nothing else is sent, and " +
                            "requests are linked to your ChatGPT account.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = viewModel::allowSharing) { Text("Allow") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message -> Bubble(message) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                enabled = connected,
                placeholder = { Text(if (connected) "Ask about your data, or \"make a sleep dashboard\"" else "Connect ChatGPT to chat") },
                shape = RoundedCornerShape(24.dp),
            )
            Button(
                onClick = {
                    viewModel.send(draft)
                    draft = ""
                },
                enabled = connected && !sending && draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        val colors = MaterialTheme.colorScheme
        Text(
            message.text,
            color = if (message.fromUser) colors.onPrimary else colors.onSurface,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(if (message.fromUser) colors.primary else colors.surfaceContainerHigh, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
