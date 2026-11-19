package app.rkn.vpn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.rkn.vpn.BuildConfig
import app.rkn.vpn.R
import app.rkn.vpn.core.UpdateChecker
import app.rkn.vpn.core.UpdateChecker.Release
import app.rkn.vpn.data.AppState
import app.rkn.vpn.ui.QrScanActivity
import app.rkn.vpn.ui.components.HelpButton
import app.rkn.vpn.ui.components.ScreenHeader
import app.rkn.vpn.ui.components.SectionCard
import app.rkn.vpn.vpn.VpnController
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    state: AppState,
    onSaveSubscription: (String) -> Unit,
    onRefresh: () -> Unit,
    onThemeChange: (String) -> Unit
) {
    var url by remember(state.subscriptionUrl) { mutableStateOf(state.subscriptionUrl) }
    var logs by remember { mutableStateOf<String?>(null) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val json = VpnController.exportSettings()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: error(context.getString(R.string.cannot_open_file))
        }
        fileMessage = if (result.isSuccess) {
            context.getString(R.string.backup_saved)
        } else {
            context.getString(R.string.backup_save_failed, result.exceptionOrNull()?.message ?: "")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error(context.getString(R.string.cannot_read_file))
        }.fold(
            onSuccess = { text ->
                // Файл может быть как копией настроек, так и конфигом/списком узлов.
                val isBackup = text.contains("\"format\"") &&
                    text.contains("rkn-vpn-backup")
                if (isBackup) VpnController.importSettings(text)
                else VpnController.importText(text)
            },
            onFailure = { fileMessage = context.getString(R.string.file_read_failed, it.message ?: "") }
        )
    }

    val qrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val value = result.data?.getStringExtra(QrScanActivity.EXTRA_SCAN_RESULT)
        if (value.isNullOrBlank()) return@rememberLauncherForActivityResult
        val isUrl = value.startsWith("http://", true) || value.startsWith("https://", true)
        if (isUrl) onSaveSubscription(value.trim()) else VpnController.importText(value.trim())
    }

    var update by remember { mutableStateOf<Release?>(null) }
    var updateChecked by remember { mutableStateOf(false) }
    var updateChecking by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle)
        )

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(
                title = stringResource(R.string.remnawave_subscription),
                trailing = {
                    HelpButton(
                        title = stringResource(R.string.help_link_title),
                        text = stringResource(R.string.help_link_text)
                    )
                }
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subscription_url_label)) },
                    placeholder = { Text("https://panel.example.com/sub/…") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSaveSubscription(url.trim()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.save_and_refresh))
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !state.refreshing) {
                        Text(stringResource(R.string.refresh))
                    }
                }
                if (state.subscription.updatedAt > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.last_updated, app.rkn.vpn.util.Format.date(state.subscription.updatedAt / 1000)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(
                title = stringResource(R.string.import_backup_title),
                trailing = {
                    HelpButton(
                        title = stringResource(R.string.help_import_title),
                        text = stringResource(R.string.help_import_text)
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showImport = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.paste))
                        }
                        OutlinedButton(
                            onClick = { qrLauncher.launch(Intent(context, QrScanActivity::class.java)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.qr_code))
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.from_file))
                        }
                    }
                    OutlinedButton(
                        onClick = { exportLauncher.launch("rkn-vpn-backup.json") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.save_backup))
                    }
                }
                if (!fileMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = fileMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(title = stringResource(R.string.appearance)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "system" to stringResource(R.string.theme_system),
                        "light" to stringResource(R.string.theme_light),
                        "dark" to stringResource(R.string.theme_dark)
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = state.theme == value,
                            onClick = { onThemeChange(value) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "system" to stringResource(R.string.language_system),
                        "ru" to stringResource(R.string.language_russian),
                        "en" to stringResource(R.string.language_english)
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = state.language == value,
                            onClick = { VpnController.setLanguage(value) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            SectionCard(title = stringResource(R.string.security)) {
                Text(
                    text = stringResource(R.string.security_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.open_android_vpn_settings))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.socks_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(title = stringResource(R.string.diagnostics)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { logs = VpnController.readLogs().ifBlank { context.getString(R.string.log_empty) } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.core_log))
                    }
                    OutlinedButton(onClick = { VpnController.clearLogs() }) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }

            SectionCard(title = stringResource(R.string.about)) {
                Text(
                    text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.about_author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.about_core_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (updateChecking) return@OutlinedButton
                        updateChecking = true
                        updateChecked = true
                        updateScope.launch {
                            update = UpdateChecker.check()
                            updateChecking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (updateChecking) R.string.checking_updates else R.string.check_updates))
                }
                if (updateChecked && !updateChecking && update == null) {
                    Text(
                        text = stringResource(R.string.up_to_date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.vpn_visibility_note),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text(stringResource(R.string.import_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.import_dialog_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp),
                        label = { Text(stringResource(R.string.import_link_label)) },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = importText.trim()
                        val isUrl = value.startsWith("http://", true) ||
                            value.startsWith("https://", true)
                        val ok = if (isUrl) {
                            onSaveSubscription(value)
                            true
                        } else {
                            VpnController.importText(value)
                        }
                        if (ok) {
                            showImport = false
                            importText = ""
                        }
                    },
                    enabled = importText.isNotBlank()
                ) {
                    Text(stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val release = update
    if (release != null) {
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text(stringResource(R.string.update_available, release.version)) },
            text = {
                Text(
                    text = release.notes.take(800).ifBlank { stringResource(R.string.no_release_notes) },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = release.apkUrl ?: release.pageUrl
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }) {
                    Text(stringResource(if (release.apkUrl != null) R.string.download_apk else R.string.open_release))
                }
            },
            dismissButton = {
                TextButton(onClick = { update = null }) { Text(stringResource(R.string.later)) }
            }
        )
    }

    val logText = logs
    if (logText != null) {
        AlertDialog(
            onDismissRequest = { logs = null },
            title = { Text(stringResource(R.string.core_log)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = logText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { logs = null }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}
