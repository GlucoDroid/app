@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.drivers.mq.MQAuthCredentials
import tk.glucodata.drivers.mq.MQCloudClient
import tk.glucodata.drivers.mq.MQRegistry
import tk.glucodata.drivers.mq.MQVerifyCodeAction
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel

@Composable
fun MQAccountSettingsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val initialAccountState = remember { MQRegistry.loadAccountState(context) }

    var phone by rememberSaveable { mutableStateOf(initialAccountState.phone) }
    var password by rememberSaveable { mutableStateOf(initialAccountState.password) }
    var apiBaseUrl by rememberSaveable { mutableStateOf(initialAccountState.apiBaseUrl) }
    var verificationCode by rememberSaveable { mutableStateOf("") }
    var cloudSyncEnabled by rememberSaveable {
        mutableStateOf(MQRegistry.isCloudSyncEnabled(context))
    }
    var authToken by rememberSaveable { mutableStateOf(initialAccountState.authToken.orEmpty()) }
    var statusText by rememberSaveable { mutableStateOf("") }
    var busyAction by remember { mutableStateOf<String?>(null) }

    val hasCredentials = phone.isNotBlank() && password.isNotBlank()
    val hasVerificationCode = verificationCode.isNotBlank()
    val hasAuthToken = authToken.isNotBlank()
    val isBusy = busyAction != null
    val signInLabel = stringResource(R.string.mq_account_sign_in_action)
    val checkSessionLabel = stringResource(R.string.mq_account_check_session_action)
    val signOutLabel = stringResource(R.string.mq_account_sign_out_action)
    val requestLoginCodeLabel = stringResource(R.string.mq_request_login_code_action)
    val requestSignupCodeLabel = stringResource(R.string.mq_request_signup_code_action)
    val requestResetCodeLabel = stringResource(R.string.mq_request_reset_code_action)
    val signInWithCodeLabel = stringResource(R.string.mq_account_sign_in_with_code_action)
    val createAccountLabel = stringResource(R.string.mq_account_register_action)
    val resetPasswordLabel = stringResource(R.string.mq_account_reset_password_action)
    val statusSummary = when {
        hasAuthToken -> stringResource(R.string.mq_account_status_signed_in)
        hasCredentials -> stringResource(R.string.mq_account_status_saved)
        else -> stringResource(R.string.mq_account_status_missing)
    }
    val secondarySummary = buildList {
        phone.trim().takeIf { it.isNotEmpty() }?.let(::add)
        add(
            if (cloudSyncEnabled) {
                stringResource(R.string.mq_sync_enabled_summary)
            } else {
                stringResource(R.string.mq_sync_disabled_summary)
            }
        )
    }.joinToString(" · ")

    fun persistAccountState() {
        MQRegistry.saveAccountState(
            context = context,
            phone = phone,
            password = password,
            apiBaseUrl = apiBaseUrl,
        )
        MQRegistry.saveCloudSyncEnabled(context, cloudSyncEnabled)
        authToken = MQRegistry.loadAccountState(context).authToken.orEmpty()
    }

    fun setResultMessage(message: String?, fallbackRes: Int) {
        statusText = message?.takeIf { it.isNotBlank() } ?: context.getString(fallbackRes)
    }

    suspend fun runCloudAction(
        actionLabel: String,
        block: suspend () -> Unit,
    ) {
        busyAction = actionLabel
        try {
            persistAccountState()
            block()
        } finally {
            busyAction = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { persistAccountState() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mq_account_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MasterSwitchCard(
                title = stringResource(R.string.mq_sync_title),
                subtitle = stringResource(R.string.mq_sync_desc),
                checked = cloudSyncEnabled,
                onCheckedChange = { enabled ->
                    cloudSyncEnabled = enabled
                    MQRegistry.saveCloudSyncEnabled(context, enabled)
                },
                icon = Icons.Default.Cloud,
            )

            MQAccountStatusCard(
                summary = statusSummary,
                secondarySummary = secondarySummary,
                statusText = statusText,
                busyAction = busyAction,
            )

            SectionLabel(
                text = stringResource(R.string.mq_account_title),
                topPadding = 0.dp,
            )

            MQAccountFields(
                phone = phone,
                onPhoneChange = { phone = it },
                password = password,
                onPasswordChange = { password = it },
                apiBaseUrl = apiBaseUrl,
                onApiBaseUrlChange = { apiBaseUrl = it },
                supportingText = stringResource(R.string.mq_account_desc),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    coroutineScope.launch {
                        runCloudAction(signInLabel) {
                            val result = withContext(Dispatchers.IO) {
                                MQCloudClient.signInWithPassword(
                                    context = context,
                                    credentials = MQAuthCredentials(
                                        account = phone.trim(),
                                        password = password,
                                    ),
                                )
                            }
                            if (result.success) {
                                MQRegistry.saveAuthToken(context, result.token)
                                authToken = result.token.orEmpty()
                            }
                            setResultMessage(result.message, R.string.mq_account_sign_in_action)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasCredentials && !isBusy,
            ) {
                Text(signInLabel)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            runCloudAction(checkSessionLabel) {
                                val result = withContext(Dispatchers.IO) {
                                    MQCloudClient.checkToken(
                                        context = context,
                                        authToken = authToken,
                                    )
                                }
                                setResultMessage(
                                    result.message,
                                    if (result.isValid) {
                                        R.string.mq_account_session_valid
                                    } else {
                                        R.string.mq_account_session_invalid
                                    },
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = hasAuthToken && !isBusy,
                ) {
                    Text(checkSessionLabel)
                }
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            runCloudAction(signOutLabel) {
                                val result = withContext(Dispatchers.IO) {
                                    if (hasAuthToken && phone.isNotBlank()) {
                                        MQCloudClient.logout(
                                            context = context,
                                            phone = phone.trim(),
                                            authToken = authToken,
                                        )
                                    } else {
                                        tk.glucodata.drivers.mq.MQCloudActionResult(success = true)
                                    }
                                }
                                MQRegistry.clearAuthToken(context)
                                authToken = ""
                                setResultMessage(result.message, R.string.mq_account_sign_out_action)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = (hasAuthToken || hasCredentials) && !isBusy,
                ) {
                    Text(signOutLabel)
                }
            }

            SectionLabel(
                text = stringResource(R.string.mq_sms_section_title),
                topPadding = 8.dp,
            )

            OutlinedTextField(
                value = verificationCode,
                onValueChange = { verificationCode = it.filter { c -> c.isLetterOrDigit() } },
                label = { Text(stringResource(R.string.mq_sms_code_label)) },
                supportingText = { Text(stringResource(R.string.mq_sms_code_desc)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        runCloudAction(requestLoginCodeLabel) {
                            val result = withContext(Dispatchers.IO) {
                                MQCloudClient.requestVerifyCode(
                                    context = context,
                                    phone = phone.trim(),
                                    action = MQVerifyCodeAction.LOGIN,
                                )
                            }
                            setResultMessage(result.message, R.string.mq_request_login_code_action)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = phone.isNotBlank() && !isBusy,
            ) {
                Text(requestLoginCodeLabel)
            }

            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        runCloudAction(requestSignupCodeLabel) {
                            val result = withContext(Dispatchers.IO) {
                                MQCloudClient.requestVerifyCode(
                                    context = context,
                                    phone = phone.trim(),
                                    action = MQVerifyCodeAction.REGISTER,
                                )
                            }
                            setResultMessage(result.message, R.string.mq_request_signup_code_action)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = phone.isNotBlank() && !isBusy,
            ) {
                Text(requestSignupCodeLabel)
            }

            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        runCloudAction(requestResetCodeLabel) {
                            val result = withContext(Dispatchers.IO) {
                                MQCloudClient.requestVerifyCode(
                                    context = context,
                                    phone = phone.trim(),
                                    action = MQVerifyCodeAction.RESET_PASSWORD,
                                )
                            }
                            setResultMessage(result.message, R.string.mq_request_reset_code_action)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = phone.isNotBlank() && !isBusy,
            ) {
                Text(requestResetCodeLabel)
            }

            FilledTonalButton(
                onClick = {
                    coroutineScope.launch {
                        runCloudAction(signInWithCodeLabel) {
                            val result = withContext(Dispatchers.IO) {
                                MQCloudClient.signInWithCode(
                                    context = context,
                                    phone = phone.trim(),
                                    captcha = verificationCode.trim(),
                                )
                            }
                            if (result.success) {
                                MQRegistry.saveAuthToken(context, result.token)
                                authToken = result.token.orEmpty()
                            }
                            setResultMessage(result.message, R.string.mq_account_sign_in_with_code_action)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = phone.isNotBlank() && hasVerificationCode && !isBusy,
            ) {
                Text(signInWithCodeLabel)
            }

            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        runCloudAction(createAccountLabel) {
                            val result = withContext(Dispatchers.IO) {
                                MQCloudClient.registerByCode(
                                    context = context,
                                    phone = phone.trim(),
                                    captcha = verificationCode.trim(),
                                    password = password,
                                )
                            }
                            if (result.success) {
                                MQRegistry.saveAuthToken(context, result.token)
                                authToken = result.token.orEmpty()
                            }
                            setResultMessage(result.message, R.string.mq_account_register_action)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = phone.isNotBlank() && hasVerificationCode && password.isNotBlank() && !isBusy,
            ) {
                Text(createAccountLabel)
            }

            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        runCloudAction(resetPasswordLabel) {
                            val result = withContext(Dispatchers.IO) {
                                MQCloudClient.resetPasswordByCode(
                                    context = context,
                                    phone = phone.trim(),
                                    captcha = verificationCode.trim(),
                                    newPassword = password,
                                )
                            }
                            setResultMessage(result.message, R.string.mq_account_reset_password_action)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = phone.isNotBlank() && hasVerificationCode && password.isNotBlank() && !isBusy,
            ) {
                Text(resetPasswordLabel)
            }
        }
    }
}

@Composable
private fun MQAccountStatusCard(
    summary: String,
    secondarySummary: String,
    statusText: String,
    busyAction: String?,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = secondarySummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busyAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = busyAction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (statusText.isNotBlank()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
