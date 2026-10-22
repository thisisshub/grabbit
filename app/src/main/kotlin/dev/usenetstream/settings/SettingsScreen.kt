package dev.usenetstream.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.usenetstream.engine.newznab.IndexerConfig
import dev.usenetstream.engine.nntp.NntpConfig

@Composable
fun SettingsScreen(initial: NntpConfig?, initialIndexer: IndexerConfig?, onSave: (NntpConfig, IndexerConfig?) -> Unit) {
    var host by rememberSaveable { mutableStateOf(initial?.host ?: "news.newsdemon.com") }
    var port by rememberSaveable { mutableStateOf((initial?.port ?: 563).toString()) }
    var tls by rememberSaveable { mutableStateOf(initial?.useTls ?: true) }
    var username by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(initial?.password.orEmpty()) }
    var connections by rememberSaveable { mutableStateOf((initial?.connections ?: 10).toString()) }
    var indexerUrl by rememberSaveable { mutableStateOf(initialIndexer?.baseUrl ?: "https://api.nzbgeek.info/") }
    var apiKey by rememberSaveable { mutableStateOf(initialIndexer?.apiKey.orEmpty()) }

    val portNum = port.toIntOrNull()
    val connNum = connections.toIntOrNull()
    val valid = host.isNotBlank() && username.isNotBlank() && password.isNotEmpty() &&
        portNum in 1..65535 && connNum in 1..100

    Column(
        Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Usenet server", style = MaterialTheme.typography.headlineSmall)
        Text(
            "NewsDemon: host and connection limit are on your account page.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Host") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f),
                label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("TLS")
            Switch(tls, { tls = it; if (it && port == "119") port = "563" else if (!it && port == "563") port = "119" })
        }
        OutlinedTextField(username, { username = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(),
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            connections, { connections = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
            label = { Text("Connections") }, singleLine = true,
            supportingText = { Text("More = faster buffering. Stay under your plan's limit.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text("Indexer (optional)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Any Newznab indexer. Leave the API key empty to skip.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(indexerUrl, { indexerUrl = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Indexer URL") }, singleLine = true)
        OutlinedTextField(
            apiKey, { apiKey = it.trim() }, Modifier.fillMaxWidth(),
            label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        if (!tls) Text("Without TLS your password is sent in plain text.", color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                val indexer = if (apiKey.isNotEmpty() && indexerUrl.isNotBlank()) IndexerConfig(indexerUrl, apiKey) else null
                onSave(NntpConfig(host, portNum!!, tls, username, password, connNum!!), indexer)
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}
