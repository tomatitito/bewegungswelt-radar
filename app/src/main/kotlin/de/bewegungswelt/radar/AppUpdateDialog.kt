package de.bewegungswelt.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState,
    installedVersion: String,
    canInstall: Boolean,
    installError: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = state.checking || state.downloading
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_update)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.update_installed, installedVersion))
                Text(stringResource(R.string.update_source))
                when {
                    state.checking -> Text(stringResource(R.string.update_checking))
                    state.downloading -> Text(stringResource(R.string.update_downloading))
                    state.error -> Text(stringResource(R.string.update_failed))
                    state.apk != null -> Text(stringResource(R.string.update_ready))
                    state.release != null -> Text(stringResource(R.string.update_available, state.release.version))
                    state.checked -> Text(stringResource(R.string.update_none))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.error && state.release != null) TextButton(onClick = onCheck) {
                    Text(stringResource(R.string.update_check))
                }
                if (state.apk != null && !canInstall) Text(stringResource(R.string.update_permission))
                if (installError) {
                    Text(stringResource(R.string.update_install_failed))
                    if (!busy) TextButton(onClick = onDownload) { Text(stringResource(R.string.update_download_again)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = when {
                    state.apk != null -> onInstall
                    state.release != null -> onDownload
                    else -> onCheck
                },
            ) {
                Text(stringResource(when {
                    state.apk != null && !canInstall -> R.string.update_allow_install
                    state.apk != null -> R.string.update_install
                    state.release != null -> R.string.update_download
                    else -> R.string.update_check
                }))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
