package app.rkn.vpn.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rkn.vpn.R
import app.rkn.vpn.data.AppState
import app.rkn.vpn.data.RoutingRule
import app.rkn.vpn.data.RuleTarget
import app.rkn.vpn.ui.components.EmptyState
import app.rkn.vpn.ui.components.HelpButton
import app.rkn.vpn.ui.components.LabeledSwitch
import app.rkn.vpn.ui.components.ScreenHeader
import app.rkn.vpn.ui.components.SectionCard
import app.rkn.vpn.ui.components.SingleLineLabel
import java.util.UUID

private val RULE_TYPES = listOf(
    "DOMAIN-SUFFIX",
    "DOMAIN",
    "DOMAIN-KEYWORD",
    "IP-CIDR",
    "IP-CIDR6"
)

@Composable
fun RoutingScreen(
    state: AppState,
    onModeChange: (String) -> Unit,
    onLanDirectChange: (Boolean) -> Unit,
    onAddRule: (RoutingRule) -> Unit,
    onDeleteRule: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.routing_title),
            subtitle = stringResource(R.string.routing_subtitle),
            actions = {
                HelpButton(
                    title = stringResource(R.string.help_rules_title),
                    text = stringResource(R.string.help_rules_text)
                )
            }
        )

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard(title = stringResource(R.string.mode)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.routingMode == "rules",
                            onClick = { onModeChange("rules") },
                            label = { SingleLineLabel(text = stringResource(R.string.mode_rules)) }
                        )
                        FilterChip(
                            selected = state.routingMode == "global",
                            onClick = { onModeChange("global") },
                            label = { SingleLineLabel(text = stringResource(R.string.mode_global)) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.routingMode == "global") {
                            stringResource(R.string.mode_global_desc)
                        } else {
                            stringResource(R.string.mode_rules_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard {
                    LabeledSwitch(
                        title = stringResource(R.string.lan_direct_title),
                        subtitle = stringResource(R.string.lan_direct_subtitle),
                        checked = state.lanDirect,
                        onChange = onLanDirectChange
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.custom_rules),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.rules_count, state.rules.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add))
                    }
                }
            }

            if (state.rules.isEmpty()) {
                item {
                    EmptyState(
                        icon = painterResource(R.drawable.ic_route),
                        title = stringResource(R.string.no_rules),
                        text = stringResource(R.string.no_rules_text)
                    )
                }
            } else {
                items(state.rules, key = { it.id }) { rule ->
                    RuleRow(rule = rule, onDelete = { onDeleteRule(rule.id) })
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.rules_note),
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showDialog) {
        AddRuleDialog(
            onDismiss = { showDialog = false },
            onAdd = { type, value, target ->
                onAddRule(
                    RoutingRule(
                        id = UUID.randomUUID().toString(),
                        type = type,
                        value = value.trim(),
                        target = target
                    )
                )
                showDialog = false
            }
        )
    }
}

@Composable
private fun RuleRow(rule: RoutingRule, onDelete: () -> Unit) {
    val direct = rule.target == RuleTarget.DIRECT
    val accent = if (direct) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.value,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = rule.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = 0.15f)) {
                Text(
                    text = stringResource(if (direct) R.string.direct else R.string.through_vpn),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_rule_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, RuleTarget) -> Unit
) {
    var type by remember { mutableStateOf(RULE_TYPES.first()) }
    var value by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(RuleTarget.DIRECT) }

    val isIp = type.startsWith("IP-CIDR")
    val valid = value.isNotBlank() && (!isIp || value.contains("/"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_rule)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.rule_type),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RULE_TYPES.chunked(3).forEach { rowTypes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowTypes.forEach { item ->
                                FilterChip(
                                    selected = type == item,
                                    onClick = { type = item },
                                    label = {
                                        SingleLineLabel(
                                            text = item,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(if (isIp) R.string.subnet_hint else R.string.domain_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isIp) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.ip_rule_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.route_to),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = target == RuleTarget.DIRECT,
                        onClick = { target = RuleTarget.DIRECT },
                        label = { SingleLineLabel(text = stringResource(R.string.direct)) }
                    )
                    FilterChip(
                        selected = target == RuleTarget.PROXY,
                        onClick = { target = RuleTarget.PROXY },
                        label = { SingleLineLabel(text = stringResource(R.string.through_vpn)) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(type, value, target) },
                enabled = valid
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
