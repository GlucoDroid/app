@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.OutboundApi
import tk.glucodata.OutboundApiSettings
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.cardShape
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OutboundApiSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val initial = remember { OutboundApiSettings.load(context) }

    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var provider by rememberSaveable { mutableStateOf(initial.normalizedProvider()) }
    var url by rememberSaveable { mutableStateOf(initial.url) }
    var token by rememberSaveable { mutableStateOf(initial.token) }
    var chatId by rememberSaveable { mutableStateOf(initial.chatId) }
    var apiVersion by rememberSaveable { mutableStateOf(initial.apiVersion) }
    var headers by rememberSaveable { mutableStateOf(initial.headers) }
    var template by rememberSaveable { mutableStateOf(initial.messageTemplate) }
    var minInterval by rememberSaveable { mutableStateOf(initial.minIntervalMinutes.toString()) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf(OutboundApiSettings.status(context)) }

    fun currentConfig() = OutboundApiSettings.Config(
        enabled = enabled,
        provider = provider,
        url = url,
        token = token,
        chatId = chatId,
        apiVersion = apiVersion,
        headers = headers,
        messageTemplate = template,
        minIntervalMinutes = minInterval.toIntOrNull()?.coerceAtLeast(0)
            ?: OutboundApiSettings.DEFAULT_MIN_INTERVAL_MINUTES
    )

    fun persist() {
        OutboundApiSettings.save(context, currentConfig())
    }

    LaunchedEffect(enabled, provider, url, token, chatId, apiVersion, headers, template, minInterval) {
        persist()
        status = OutboundApiSettings.status(context)
    }

    fun setProvider(next: String) {
        if (next == provider) return
        val oldProvider = provider
        val oldDefaultUrl = OutboundApiSettings.defaultUrl(oldProvider)
        val oldDefaultTemplate = OutboundApiSettings.defaultTemplate(oldProvider)
        provider = next
        if (url.isBlank() || url == oldDefaultUrl) {
            url = OutboundApiSettings.defaultUrl(next)
        }
        if (template.isBlank() || template == oldDefaultTemplate) {
            template = OutboundApiSettings.defaultTemplate(next)
        }
    }

    fun sendTest() {
        persist()
        val result = OutboundApi.enqueueCurrentTest(context)
        val message = when (result) {
            OutboundApi.TEST_QUEUED -> R.string.outbound_api_test_queued
            OutboundApi.TEST_NO_CURRENT_READING -> R.string.outbound_api_no_current_reading
            else -> R.string.outbound_api_not_configured
        }
        Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
        status = OutboundApiSettings.status(context)
    }

    val childAlpha = if (enabled) 1f else 0.58f
    val isVk = provider == OutboundApiSettings.PROVIDER_VK

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.outbound_api_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { persist(); navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("outbound_master") {
                MasterSwitchCard(
                    title = stringResource(R.string.active),
                    subtitle = stringResource(R.string.outbound_api_master_desc),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    icon = Icons.Filled.CloudUpload
                )
            }

            item("outbound_provider") {
                SectionLabel(stringResource(R.string.outbound_api_provider), topPadding = 0.dp)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = cardShape(CardPosition.SINGLE),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = provider == OutboundApiSettings.PROVIDER_WEBHOOK_JSON,
                            onClick = { setProvider(OutboundApiSettings.PROVIDER_WEBHOOK_JSON) },
                            label = { Text(stringResource(R.string.outbound_api_provider_webhook)) },
                            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = isVk,
                            onClick = { setProvider(OutboundApiSettings.PROVIDER_VK) },
                            label = { Text(stringResource(R.string.outbound_api_provider_vk)) },
                            leadingIcon = { Icon(Icons.Filled.Send, contentDescription = null) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item("outbound_connection") {
                SectionLabel(stringResource(R.string.outbound_api_connection), topPadding = 0.dp)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(childAlpha),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = cardShape(CardPosition.SINGLE),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.outbound_api_url_label)) },
                            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next
                            )
                        )

                        if (isVk) {
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it },
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.outbound_api_vk_token)) },
                                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                                visualTransformation = if (showToken) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showToken = !showToken }) {
                                        Icon(
                                            if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                )
                            )
                            OutlinedTextField(
                                value = chatId,
                                onValueChange = { chatId = it },
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.outbound_api_chat_id)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                )
                            )
                            OutlinedTextField(
                                value = apiVersion,
                                onValueChange = { apiVersion = it },
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.outbound_api_vk_version)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Done
                                )
                            )
                        } else {
                            OutlinedTextField(
                                value = headers,
                                onValueChange = { headers = it },
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                label = { Text(stringResource(R.string.outbound_api_headers)) },
                                placeholder = { Text(stringResource(R.string.outbound_api_headers_placeholder)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Default
                                )
                            )
                        }
                    }
                }
            }

            item("outbound_delivery") {
                SectionLabel(stringResource(R.string.outbound_api_delivery), topPadding = 0.dp)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(childAlpha),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = cardShape(CardPosition.SINGLE),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = minInterval,
                            onValueChange = { minInterval = it.filter { ch -> ch.isDigit() } },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.outbound_api_min_interval)) },
                            leadingIcon = { Icon(Icons.Filled.AccessTime, contentDescription = null) },
                            supportingText = { Text(stringResource(R.string.outbound_api_min_interval_desc)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            )
                        )
                        OutlinedTextField(
                            value = template,
                            onValueChange = { template = it },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            label = { Text(stringResource(R.string.outbound_api_message_template)) },
                            supportingText = { Text(stringResource(R.string.outbound_api_template_desc)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Default
                            )
                        )
                    }
                }
            }

            item("outbound_status") {
                StatusCard(status = status)
            }

            item("outbound_actions") {
                Button(
                    onClick = { sendTest() },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.outbound_api_send_test))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: OutboundApiSettings.Status) {
    val context = LocalContext.current
    fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return context.getString(R.string.outbound_api_status_never)
        return DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(epochMillis))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = cardShape(CardPosition.SINGLE),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.status),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(
                    R.string.outbound_api_last_success_format,
                    formatTime(status.lastSuccessAtMs)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    R.string.outbound_api_last_attempt_format,
                    formatTime(status.lastAttemptAtMs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status.lastResponseCode != 0) {
                Text(
                    text = stringResource(
                        R.string.outbound_api_last_code_format,
                        status.lastResponseCode
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            status.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = stringResource(R.string.outbound_api_last_error_format, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
