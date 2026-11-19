package app.rkn.vpn.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import app.rkn.vpn.data.ProxyNode
import app.rkn.vpn.ui.components.EmptyState
import app.rkn.vpn.ui.components.HelpButton
import app.rkn.vpn.ui.components.LetterAvatar
import app.rkn.vpn.ui.components.PingChip
import app.rkn.vpn.ui.components.ProtocolBadge
import app.rkn.vpn.ui.components.ScreenHeader

@Composable
fun ServersScreen(
    state: AppState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onPingAll: () -> Unit,
    onBest: () -> Unit,
    onCheckHealth: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sortByPing by remember { mutableStateOf(false) }

    val filtered = remember(state.nodes, query, sortByPing, state.effectiveLatencies) {
        val base = state.nodes.filter { node ->
            query.isBlank() ||
                node.name.contains(query, ignoreCase = true) ||
                node.server.contains(query, ignoreCase = true) ||
                node.protocol.contains(query, ignoreCase = true)
        }
        if (sortByPing) {
            base.sortedWith(
                compareBy<ProxyNode> { state.effectiveLatencies[it.id] ?: Int.MAX_VALUE }
                    .thenBy { it.name.lowercase() }
            )
        } else {
            base
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.servers),
            subtitle = if (state.nodes.isEmpty()) {
                stringResource(R.string.servers_empty_load)
            } else {
                stringResource(R.string.nodes_count, state.nodes.size)
            },
            actions = {
                HelpButton(
                    title = stringResource(R.string.help_check_title),
                    text = stringResource(R.string.help_check_text)
                )
                IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                    if (state.refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_subscription_cd))
                    }
                }
            }
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            placeholder = { Text(stringResource(R.string.search_servers_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onPingAll, enabled = state.nodes.isNotEmpty()) {
                Text(stringResource(R.string.ping_all))
            }
            OutlinedButton(
                onClick = onCheckHealth,
                enabled = state.healthAvailable && !state.healthChecking && state.nodes.isNotEmpty()
            ) {
                Text(stringResource(if (state.healthChecking) R.string.checking else R.string.through_vpn))
            }
            Button(onClick = onBest, enabled = state.nodes.isNotEmpty()) {
                Text(stringResource(R.string.best))
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = sortByPing,
                onClick = { sortByPing = !sortByPing },
                label = { Text(stringResource(R.string.sort_by_ping)) }
            )
        }

        if (filtered.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize()) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_servers),
                    title = stringResource(if (state.nodes.isEmpty()) R.string.no_servers else R.string.nothing_found),
                    text = if (state.nodes.isEmpty()) {
                        stringResource(R.string.no_servers_text)
                    } else {
                        stringResource(R.string.change_search)
                    }
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { node ->
                    NodeRow(
                        node = node,
                        ping = state.effectiveLatencies[node.id],
                        selected = node.id == state.selectedId,
                        onClick = { onSelect(node.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: ProxyNode,
    ping: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LetterAvatar(text = node.name, seed = node.id.hashCode())
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolBadge(node.protocol)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${node.server}:${node.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            PingChip(ping)
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.selected_cd),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
