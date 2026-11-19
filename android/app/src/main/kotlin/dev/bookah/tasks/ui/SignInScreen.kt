package dev.bookah.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.bookah.tasks.R
import dev.bookah.tasks.UiText

/**
 * The owner types the address, user name and app password here. The password field is always hidden and is never saved
 * with the screen state (a plain remember, not rememberSaveable), so it cannot end up in a saved-state bundle.
 */
@Composable
fun SignInScreen(busy: Boolean, error: UiText?, onSignIn: (server: String, user: String, password: String) -> Unit) {
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.sign_in_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.sign_in_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            server, { server = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.field_server)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.field_user)) },
        )
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.field_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (error != null) Text(error.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Button({ onSignIn(server, user, password) }, Modifier.fillMaxWidth(), enabled = !busy) {
            Text(if (busy) stringResource(R.string.status_syncing) else stringResource(R.string.action_sign_in))
        }
    }
}
