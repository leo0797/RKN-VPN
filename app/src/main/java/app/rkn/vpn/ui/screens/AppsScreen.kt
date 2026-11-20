package app.rkn.vpn.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rkn.vpn.R
import app.rkn.vpn.data.AppState
import app.rkn.vpn.ui.components.HelpButton
import app.rkn.vpn.ui.components.LabeledSwitch
import app.rkn.vpn.ui.components.LetterAvatar
import app.rkn.vpn.ui.components.ScreenHeader
import app.rkn.vpn.ui.components.SectionCard
import app.rkn.vpn.ui.components.SingleLineLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

private data class InstalledApp(val packageName: String, val label: String)

@Composable
fun AppsScreen(
    state: AppState,
    onToggleSplit: (Boolean) -> Unit,
    onSetSplitMode: (String) -> Unit,
    onSetApps: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var includeSystem by remember { mutableStateOf(false) }
    var onlySelected by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    val appCollator = remember { Collator.getInstance(Locale("ru", "RU")) }

    LaunchedEffect(includeSystem) {
        apps = null
        apps = withContext(Dispatchers.IO) { loadApps(context, includeSystem) }
    }

    val list = apps
    val filtered = remember(list, query, onlySelected, state.splitApps, appCollator) {
        (list ?: emptyList())
            .filter { app ->
                (!onlySelected || app.packageName in state.splitApps) &&
                    (query.isBlank() ||
                        app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true))
            }
            // Сначала отмеченные приложения, внутри каждой группы — по алфавиту.
            .sortedWith(
                compareByDescending<InstalledApp> { it.packageName in state.splitApps }
                    .thenBy(appCollator) { it.label }
            )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.apps),
            subtitle = stringResource(R.string.split_tunneling),
            actions = {
                HelpButton(
                    title = stringResource(R.string.split_tunneling),
                    text = stringResource(R.string.help_split_text)
                )
            }
        )

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            SectionCard {
                LabeledSwitch(
                    title = stringResource(R.string.selected_apps_vpn),
                    subtitle = stringResource(R.string.selected_apps_subtitle),
                    checked = state.splitEnabled,
                    onChange = onToggleSplit
                )
                if (state.splitEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.splitMode == "include",
                            onClick = { onSetSplitMode("include") },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    text = stringResource(R.string.split_mode_excluded),
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        hyphens = androidx.compose.ui.text.style.Hyphens.None
                                    ),
                                    softWrap = true,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        FilterChip(
                            selected = state.splitMode == "exclude",
                            onClick = { onSetSplitMode("exclude") },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    text = stringResource(R.string.split_mode_all),
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        hyphens = androidx.compose.ui.text.style.Hyphens.None
                                    ),
                                    softWrap = true,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.splitMode == "exclude") {
                            stringResource(R.string.split_mode_all_desc)
                        } else {
                            stringResource(R.string.split_mode_excluded_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.selected_apps_count, state.splitApps.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.splitApps.isEmpty()) {
                        Text(
                            text = if (state.splitMode == "exclude") {
                                stringResource(R.string.split_empty_warning_excluded)
                            } else {
                                stringResource(R.string.split_empty_warning)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_apps_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.clear_search_cd)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = onlySelected,
                    onClick = { onlySelected = !onlySelected },
                    label = { SingleLineLabel(text = stringResource(R.string.only_selected)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = includeSystem,
                    onClick = { includeSystem = !includeSystem },
                    label = { SingleLineLabel(text = stringResource(R.string.system_apps)) }
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onSetApps(emptySet()) },
                    enabled = state.splitApps.isNotEmpty()
                ) {
                    Text(stringResource(R.string.reset))
                }
            }
            Text(
                text = stringResource(R.string.apps_sort_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (query.isNotBlank() || onlySelected) {
                Text(
                    text = stringResource(R.string.apps_found_count, filtered.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        when {
            list == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            filtered.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(if (onlySelected) R.string.nothing_selected else R.string.apps_not_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in state.splitApps
                        AppRow(
                            app = app,
                            checked = checked,
                            onToggle = {
                                onSetApps(
                                    if (checked) state.splitApps - app.packageName
                                    else state.splitApps + app.packageName
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    val clickLabel = stringResource(
        if (checked) R.string.app_action_unselect else R.string.app_action_select,
        app.label
    )
    val stateLabel = stringResource(
        if (checked) R.string.app_state_selected else R.string.app_state_not_selected
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle, onClickLabel = clickLabel, role = Role.Checkbox)
            // Одна озвучиваемая сущность на строку: имя, пакет и состояние.
            // Иначе TalkBack читает отдельно букву аватара, имя, пакет и чекбокс.
            .semantics(mergeDescendants = true) {
                contentDescription = "${app.label}, ${app.packageName}"
                stateDescription = stateLabel
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.clearAndSetSemantics {}) {
            LetterAvatar(
                text = app.label,
                seed = app.packageName.hashCode(),
                size = 38
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

private fun loadApps(context: Context, includeSystem: Boolean): List<InstalledApp> {
    val pm = context.packageManager
    // getInstalledApplications, а не queryIntentActivities(ACTION_MAIN):
    // у части приложений (особенно системных) нет launcher-активности, но
    // сетевой трафик у них есть, и их тоже нужно уметь направлять в VPN.
    val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    }
    val seen = HashSet<String>()
    val result = ArrayList<InstalledApp>()
    installed.forEach { appInfo ->
        val pkg = appInfo.packageName
        if (pkg == context.packageName) return@forEach
        if (!seen.add(pkg)) return@forEach
        val system = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (system && !includeSystem) return@forEach
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrDefault(pkg)
        result.add(InstalledApp(pkg, label))
    }
    val collator = Collator.getInstance(Locale("ru", "RU"))
    return result.sortedWith(compareBy(collator) { it.label })
}
