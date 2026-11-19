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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.rkn.vpn.BuildConfig
import app.rkn.vpn.R
import app.rkn.vpn.core.ApkInstaller
import app.rkn.vpn.core.Strings
import app.rkn.vpn.core.UpdateChecker
import app.rkn.vpn.core.UpdateChecker.Release
import app.rkn.vpn.data.AppState
import app.rkn.vpn.ui.QrScanActivity
import app.rkn.vpn.ui.components.HelpButton
import app.rkn.vpn.ui.components.ScreenHeader
import app.rkn.vpn.ui.components.SectionCard
import app.rkn.vpn.ui.components.SingleLineLabel
import app.rkn.vpn.vpn.VpnController
import kotlinx.coroutines.launch

private const val IMPORT_SUBSCRIPTION = "subscription"
private const val IMPORT_CONFIG = "config"

@Composable
fun SettingsScreen(
    state: AppState,
    onSaveSubscription: (String) -> Unit,
    onRefresh: () -> Unit,
    onThemeChange: (String) -> Unit
) {
    var url by remember(state.subscriptionUrl) { mutableStateOf(state.subscriptionUrl) }
    var checkUrl by remember(state.checkUrl) { mutableStateOf(state.checkUrl) }
    var logs by remember { mutableStateOf<String?>(null) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var subscriptionMessage by remember { mutableStateOf<String?>(null) }
    var importMode by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var confirmBackupExport by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun isSubscriptionUrl(value: String): Boolean =
        value.startsWith("http://", true) || value.startsWith("https://", true)

    fun openImport(mode: String) {
        importMode = mode
        importText = ""
        importError = null
    }

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
        val trimmed = value.trim()
        if (isSubscriptionUrl(trimmed)) {
            subscriptionMessage = null
            onSaveSubscription(trimmed)
        } else {
            subscriptionMessage = context.getString(R.string.error_qr_not_subscription)
        }
    }

    var update by remember { mutableStateOf<Release?>(null) }
    var updateChecked by remember { mutableStateOf(false) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<Int?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    val updateScope = rememberCoroutineScope()

    fun runUpdateCheck() {
        if (updateChecking) return
        updateChecking = true
        updateChecked = true
        update = null
        updateMessage = null
        updateScope.launch {
            when (val result = UpdateChecker.check(VpnController.localProxy())) {
                is UpdateChecker.Result.Available -> update = result.release
                UpdateChecker.Result.UpToDate ->
                    updateMessage = context.getString(R.string.up_to_date)
                is UpdateChecker.Result.Failed -> updateMessage = context.getString(
                    when (result.reason) {
                        UpdateChecker.Result.Failure.NETWORK ->
                            R.string.update_check_failed_network
                        UpdateChecker.Result.Failure.SERVER ->
                            R.string.update_check_failed_server
                        UpdateChecker.Result.Failure.MALFORMED ->
                            R.string.update_check_failed_malformed
                    }
                )
            }
            updateChecking = false
        }
    }

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
                        SingleLineLabel(
                            text = stringResource(R.string.save_and_refresh),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !state.refreshing) {
                        SingleLineLabel(
                            text = stringResource(R.string.refresh),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { openImport(IMPORT_SUBSCRIPTION) },
                        modifier = Modifier.weight(1f)
                    ) {
                        SingleLineLabel(
                            text = stringResource(R.string.paste),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedButton(
                        onClick = { qrLauncher.launch(Intent(context, QrScanActivity::class.java)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        SingleLineLabel(
                            text = stringResource(R.string.qr_subscription),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (!subscriptionMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = subscriptionMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
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
                            onClick = { openImport(IMPORT_CONFIG) },
                            modifier = Modifier.weight(1f)
                        ) {
                            SingleLineLabel(
                                text = stringResource(R.string.import_config),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            SingleLineLabel(
                                text = stringResource(R.string.from_file),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { confirmBackupExport = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SingleLineLabel(
                            text = stringResource(R.string.save_backup),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
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
                            modifier = Modifier.weight(1f).height(40.dp),
                            label = {
                                SingleLineLabel(
                                    text = label,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
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
                            modifier = Modifier.weight(1f).height(40.dp),
                            label = {
                                SingleLineLabel(
                                    text = label,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
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
                OutlinedTextField(
                    value = checkUrl,
                    onValueChange = { checkUrl = it },
                    label = { Text(stringResource(R.string.check_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.check_url_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { VpnController.setCheckUrl(checkUrl.trim()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        SingleLineLabel(
                            text = stringResource(R.string.save),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedButton(
                        onClick = { checkUrl = state.checkUrl },
                        modifier = Modifier.weight(1f)
                    ) {
                        SingleLineLabel(
                            text = stringResource(R.string.reset),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
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
                    onClick = { runUpdateCheck() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (updateChecking) R.string.checking_updates else R.string.check_updates))
                }
                if (updateChecked && !updateChecking && update == null && updateMessage != null) {
                    Text(
                        text = updateMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { runUpdateCheck() }) {
                        Text(stringResource(R.string.retry))
                    }
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

    val mode = importMode
    if (mode != null) {
        val subscriptionMode = mode == IMPORT_SUBSCRIPTION
        AlertDialog(
            onDismissRequest = { importMode = null },
            title = {
                Text(
                    stringResource(
                        if (subscriptionMode) R.string.subscription_paste_title
                        else R.string.import_title
                    )
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            if (subscriptionMode) R.string.subscription_paste_text
                            else R.string.import_dialog_text
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (subscriptionMode) 56.dp else 140.dp),
                        label = {
                            Text(
                                stringResource(
                                    if (subscriptionMode) R.string.subscription_paste_label
                                    else R.string.import_link_label
                                )
                            )
                        },
                        singleLine = subscriptionMode,
                        shape = RoundedCornerShape(14.dp)
                    )
                    if (!importError.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = importError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = importText.trim()
                        if (subscriptionMode) {
                            if (!isSubscriptionUrl(value)) {
                                importError = context.getString(R.string.error_not_subscription_link)
                                return@TextButton
                            }
                            subscriptionMessage = null
                            onSaveSubscription(value)
                        } else if (!VpnController.importText(value)) {
                            return@TextButton
                        }
                        importMode = null
                        importText = ""
                        importError = null
                    },
                    enabled = importText.isNotBlank()
                ) {
                    Text(
                        stringResource(
                            if (subscriptionMode) R.string.subscription_paste_action
                            else R.string.import_action
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { importMode = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val release = update
    if (release != null) {
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text(stringResource(R.string.update_available, release.version)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = release.notes.ifBlank { stringResource(R.string.no_release_notes) },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val apkUrl = release.apkUrl
                    if (apkUrl == null) {
                        // Редкий случай: релиз без APK — показываем страницу в браузере.
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
                        }
                        update = null
                        return@TextButton
                    }
                    update = null
                    downloadError = null
                    downloadProgress = 0
                    updateScope.launch {
                        runCatching {
                            val file = ApkInstaller.download(
                                context, apkUrl, release.version,
                                { downloadProgress = it },
                                VpnController.localProxy(),
                                Strings { id, args -> context.getString(id, *args) }
                            )
                            ApkInstaller.install(context, file)
                        }.onFailure { e ->
                            downloadError = e.message?.takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.error_download_failed)
                        }
                        downloadProgress = null
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

    val progress = downloadProgress
    val failure = downloadError
    if (progress != null || failure != null) {
        AlertDialog(
            onDismissRequest = { downloadError = null },
            title = { Text(stringResource(R.string.updating_title)) },
            text = {
                Column {
                    if (failure != null) {
                        Text(
                            text = failure,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.downloading_apk),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (progress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "$progress%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (failure != null) {
                    TextButton(onClick = { downloadError = null }) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        )
    }

    if (confirmBackupExport) {
        AlertDialog(
            onDismissRequest = { confirmBackupExport = false },
            title = { Text(stringResource(R.string.backup_export_warning_title)) },
            text = { Text(stringResource(R.string.backup_export_warning_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBackupExport = false
                    exportLauncher.launch("rkn-vpn-backup.json")
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBackupExport = false }) {
                    Text(stringResource(R.string.cancel))
                }
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
