package dev.addblocker.adshield.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.addblocker.adshield.data.LogEntry
import dev.addblocker.adshield.data.QueryLog
import dev.addblocker.adshield.data.RulesRepository
import dev.addblocker.adshield.data.RulesState
import dev.addblocker.adshield.data.StatsRepository
import dev.addblocker.adshield.vpn.VpnStatus
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun HomeScreen(
    onStartProtection: () -> Unit,
    onStopProtection: () -> Unit
) {
    val running by VpnStatus.running.collectAsStateWithLifecycle()
    val error by VpnStatus.error.collectAsStateWithLifecycle()
    val queries by StatsRepository.queries.collectAsStateWithLifecycle()
    val blocked by StatsRepository.blocked.collectAsStateWithLifecycle()
    val rules by RulesRepository.state.collectAsStateWithLifecycle()

    var recent by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var showYoutubeHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            recent = QueryLog.snapshot().filter { it.blocked }.take(6)
            delay(1_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StatusCard(
                running = running,
                rules = rules,
                error = error,
                onStart = onStartProtection,
                onStop = onStopProtection
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Queries",
                    value = formatCount(queries),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Blocked",
                    value = formatCount(blocked),
                    modifier = Modifier.weight(1f),
                    accent = true
                )
                StatTile(
                    label = "Block rate",
                    value = if (queries == 0L) "—" else String.format(Locale.US, "%.0f%%", blocked * 100.0 / queries),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            SectionCard(
                title = "How it works",
                subtitle = "AdShield runs a local DNS filter. Only name lookups are inspected, so your traffic keeps full speed and never passes through a server."
            ) {
                Bullet("Ads and banners in free apps and games")
                Bullet("Trackers, analytics and profiling SDKs")
                Bullet("Ad servers used by mobile browsers")
            }
        }

        item {
            SectionCard(
                title = "YouTube ads are a special case",
                subtitle = "YouTube delivers ads from the same servers as the video itself, so blocking them by name would also block playback."
            ) {
                TextButton(onClick = { showYoutubeHelp = true }) {
                    Text("How to watch YouTube without ads")
                }
            }
        }

        if (recent.isNotEmpty()) {
            item {
                SectionCard(title = "Recently blocked") {
                    recent.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = entry.domain,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatTime(entry.time),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(4.dp)) }
    }

    if (showYoutubeHelp) {
        YoutubeHelpDialog(onDismiss = { showYoutubeHelp = false })
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    rules: RulesState,
    error: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val gradient = if (running) {
        listOf(colors.primary.copy(alpha = 0.32f), colors.surface)
    } else {
        listOf(colors.surfaceVariant, colors.surface)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(gradient))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ShieldBadge(active = running)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) "Protected" else "Protection is off",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        !rules.ready -> "Loading block lists…"
                        running -> "${formatCount(rules.activeRules.toLong())} rules active"
                        else -> "Tap below to start filtering"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }

        if (error != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }
        }

        Button(
            onClick = { if (running) onStop() else onStart() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (running) "Stop protection" else "Protect this device")
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun YoutubeHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
        title = { Text("Ad-free YouTube") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "YouTube asks for an ad before a video in the same request that fetches the video. " +
                        "The ad and the video come from the same hosts (googlevideo.com), so a DNS filter can " +
                        "only block both or neither. Any app that claims to remove YouTube ads with a VPN or " +
                        "hosts file alone is either blocking the whole app or not doing anything."
                )
                Text("What actually works:", fontWeight = FontWeight.SemiBold)
                Text("• YouTube Premium — official, supports creators.")
                Text("• SmartTube — for Android TV / Fire TV boxes.")
                Text("• NewPipe or ReVanced — community clients that patch YouTube itself. Sideloaded and not supported by Google, so decide for yourself whether to trust them.")
                Text(
                    "Meanwhile AdShield still removes the ads and trackers inside browsers, games " +
                        "and most free apps, which is where the bulk of the annoying mobile ads come from."
                )
            }
        }
    )
}
