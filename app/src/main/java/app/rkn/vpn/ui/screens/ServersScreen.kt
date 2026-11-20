package app.rkn.vpn.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.LaunchedEffect
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
import app.rkn.vpn.ui.components.SingleLineLabel
import app.rkn.vpn.vpn.VpnController

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
    var protocolFilter by remember { mutableStateOf<String?>(null) }
    var countryFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        VpnController.autoPingServersIfNeeded()
    }

    val protocols = remember(state.nodes) {
        state.nodes.map { it.protocol }.distinct().sorted()
    }
    val countries = remember(state.nodes) {
        state.nodes.mapNotNull { leadingFlagEmoji(it.name) }.distinct()
    }

    val filtered = remember(
        state.nodes, query, sortByPing, protocolFilter, countryFilter, state.effectiveLatencies, state.favoriteIds
    ) {
        val base = state.nodes.filter { node ->
            val matchesQuery = query.isBlank() ||
                node.name.contains(query, ignoreCase = true) ||
                node.server.contains(query, ignoreCase = true) ||
                node.protocol.contains(query, ignoreCase = true)
            val matchesProtocol = protocolFilter == null || node.protocol.equals(protocolFilter, ignoreCase = true)
            val matchesCountry = countryFilter == null || leadingFlagEmoji(node.name) == countryFilter
            matchesQuery && matchesProtocol && matchesCountry
        }
        val sorted = if (sortByPing) {
            base.sortedWith(
                compareBy<ProxyNode> { state.effectiveLatencies[it.id] ?: Int.MAX_VALUE }
                    .thenBy { it.name.lowercase() }
            )
        } else {
            base
        }
        sorted.sortedByDescending { it.id in state.favoriteIds }
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

        if (protocols.isNotEmpty() || countries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (countries.isNotEmpty()) {
                    FilterChip(
                        selected = countryFilter == null,
                        onClick = { countryFilter = null },
                        label = { SingleLineLabel(stringResource(R.string.filter_all)) }
                    )
                    countries.forEach { flag ->
                        FilterChip(
                            selected = countryFilter == flag,
                            onClick = { countryFilter = if (countryFilter == flag) null else flag },
                            label = { Text(flag) }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (protocols.isNotEmpty()) {
                    FilterChip(
                        selected = protocolFilter == null,
                        onClick = { protocolFilter = null },
                        label = { SingleLineLabel(stringResource(R.string.filter_all)) }
                    )
                    protocols.forEach { proto ->
                        FilterChip(
                            selected = protocolFilter?.equals(proto, ignoreCase = true) == true,
                            onClick = {
                                protocolFilter = if (protocolFilter?.equals(proto, ignoreCase = true) == true) null else proto
                            },
                            label = { SingleLineLabel(proto.uppercase()) }
                        )
                    }
                }
            }
        }

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
                label = { SingleLineLabel(text = stringResource(R.string.sort_by_ping)) }
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
                        favorite = node.id in state.favoriteIds,
                        onClick = { onSelect(node.id) },
                        onToggleFavorite = { VpnController.toggleFavorite(node.id) }
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
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
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
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                if (favorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.favorite_remove),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "☆",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            PingChip(ping)
            if (selected) {
                Spacer(Modifier.width(4.dp))
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

internal fun leadingFlagEmoji(name: String): String? {
    val trimmed = name.trimStart()
    if (trimmed.length < 4) return null
    val first = Character.codePointAt(trimmed, 0)
    val firstChars = Character.charCount(first)
    if (trimmed.length < firstChars + 2) return null
    val second = Character.codePointAt(trimmed, firstChars)
    val secondChars = Character.charCount(second)
    if (!isRegionalIndicator(first) || !isRegionalIndicator(second)) return null
    return trimmed.substring(0, firstChars + secondChars)
}

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF
