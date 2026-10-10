package com.hippo.ehviewer.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.dialog
import com.hippo.ehviewer.updater.GitHubSessionCredentials
import kotlin.coroutines.resume

context(_: DialogState)
suspend fun awaitGitHubSessionToken(): Unit = dialog { continuation ->
    // Deliberately use remember rather than rememberSaveable for a secret.
    var token by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { continuation.cancel() },
        title = { Text(stringResource(R.string.github_session_token)) },
        text = {
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    invalid = false
                },
                isError = invalid,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text(stringResource(if (invalid) R.string.invalid_github_token else R.string.github_session_token_summary)) },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    GitHubSessionCredentials.set(token)
                    continuation.resume(Unit)
                } catch (_: IllegalArgumentException) {
                    invalid = true
                }
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = { continuation.cancel() }) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
