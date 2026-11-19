package app.rkn.vpn.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rkn.vpn.R
import app.rkn.vpn.data.AppState
import app.rkn.vpn.ui.components.HelpButton
import app.rkn.vpn.ui.components.PingChip
import app.rkn.vpn.ui.components.ProtocolBadge
import app.rkn.vpn.ui.components.SectionCard
import app.rkn.vpn.ui.components.SingleLineLabel
import app.rkn.vpn.ui.components.StatTile
import app.rkn.vpn.util.Format
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    state: AppState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onBest: () -> Unit,
    onOpenServers: () -> Unit,
    onAddSubscription: () -> Unit
) {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.connected, state.startedAt) {
        while (state.connected) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = if (state.connected && state.startedAt > 0) tick - state.startedAt else 0L
    val subscription = state.subscription
    val node = state.selectedNode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "RKN VPN",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        state.reconnecting -> stringResource(R.string.status_reconnecting)
                        state.connected && state.nodeReachable == false ->
                            stringResource(R.string.status_node_unreachable)
                        state.connected -> stringResource(R.string.status_connected)
                        state.connecting -> stringResource(R.string.status_connecting)
                        else -> stringResource(R.string.status_disconnected)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.connected && state.nodeReachable != false) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_subscription_cd))
                }
            }
            HelpButton(
                title = stringResource(R.string.help_ping_title),
                text = stringResource(R.string.help_ping_text)
            )
        }

        Spacer(Modifier.height(10.dp))
        ConnectArea(
            connected = state.connected,
            connecting = state.connecting,
            onClick = { if (state.connected) onDisconnect() else onConnect() }
        )
        Spacer(Modifier.height(18.dp))

        if (!state.corePresent) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.core_missing_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        stringResource(R.string.core_missing_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // -------- выбранный сервер --------
        SectionCard(
            title = stringResource(R.string.server),
            trailing = {
                if (state.nodes.size > 1) {
                    Text(
                        text = stringResource(R.string.best),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onBest)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenServers)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node?.name ?: stringResource(R.string.no_server_selected),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (node != null) {
                            ProtocolBadge(node.protocol)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${node.server}:${node.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.add_subscription_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                PingChip(node?.let { state.effectiveLatencies[it.id] })
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // -------- статистика --------
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = stringResource(R.string.downloaded),
                value = Format.bytes(state.download),
                modifier = Modifier.weight(1f),
                accent = MaterialTheme.colorScheme.secondary
            )
            StatTile(
                label = stringResource(R.string.uploaded),
                value = Format.bytes(state.upload),
                modifier = Modifier.weight(1f),
                accent = MaterialTheme.colorScheme.tertiary
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = stringResource(R.string.speed_down),
                value = Format.speed(state.downSpeed),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.speed_up),
                value = Format.speed(state.upSpeed),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.time),
                value = Format.duration(elapsed),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // -------- подписка --------
        if (state.subscriptionUrl.isBlank()) {
            SectionCard(title = stringResource(R.string.subscription)) {
                Text(
                    text = stringResource(R.string.subscription_add_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAddSubscription, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.add_subscription))
                }
            }
        } else {
            SectionCard(
                title = if (subscription.title.isBlank()) stringResource(R.string.subscription) else subscription.title,
                trailing = {
                    Text(
                        text = Format.bytes(subscription.used),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            ) {
                if (subscription.hasTraffic) {
                    val progress =
                        (subscription.used.toFloat() / subscription.total.toFloat())
                            .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (progress > 0.9f) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text(
                            text = stringResource(R.string.subscription_remaining, Format.bytes(subscription.remaining)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.subscription_used_percent, (progress * 100).roundToInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.traffic_unlimited),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (subscription.hasExpire) {
                            stringResource(R.string.subscription_valid_until, Format.date(subscription.expire))
                        } else {
                            stringResource(R.string.subscription_no_expiry)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (subscription.hasExpire) {
                        val days = Format.daysLeft(subscription.expire)
                        Text(
                            text = stringResource(R.string.duration_days, days),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (days <= 3) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                }
            }
        }

        if (subscription.hwidLimitReached) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.hwid_limit_text),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun ConnectArea(connected: Boolean, connecting: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (connected) 1.16f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(240.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            primary.copy(alpha = if (connected) 0.28f else 0.10f),
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(190.dp),
                contentAlignment = Alignment.Center
            ) {
                if (connected) {
                    Box(
                        modifier = Modifier
                            .size(168.dp)
                            .scale(pulse)
                            .border(2.dp, primary.copy(alpha = 0.35f), CircleShape)
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = if (connected) primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(154.dp)
                        .clickable(enabled = !connecting, onClick = onClick)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_power),
                                contentDescription = null,
                                modifier = Modifier.size(42.dp),
                                tint = if (connected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        SingleLineLabel(
                            text = when {
                                connecting -> stringResource(R.string.connecting)
                                connected -> stringResource(R.string.disconnect)
                                else -> stringResource(R.string.connect)
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            ),
                            color = if (connected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
