package dev.addblocker.adshield.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.addblocker.adshield.data.LogEntry
import dev.addblocker.adshield.data.Prefs
import dev.addblocker.adshield.data.QueryLog
import kotlinx.coroutines.delay

@Composable
fun LogScreen() {
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var blockedOnly by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(Prefs.logQueries) }

    LaunchedEffect(Unit) {
        while (true) {
            entries = QueryLog.snapshot()
            logging = Prefs.logQueries
            delay(1_000)
        }
    }

    val shown = if (blockedOnly) entries.filter { it.blocked } else entries

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !blockedOnly,
                onClick = { blockedOnly = false },
                label = { Text("All") }
            )
            FilterChip(
                selected = blockedOnly,
                onClick = { blockedOnly = true },
                label = { Text("Blocked") }
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { QueryLog.clear(); entries = emptyList() }) {
                Text("Clear")
            }
        }

        HorizontalDivider()

        when {
            !logging -> EmptyLog(
                title = "Query logging is off",
                subtitle = "Turn it back on in Settings if you want to see which names are being filtered. Blocking still works either way."
            )

            shown.isEmpty() -> EmptyLog(
                title = "Nothing here yet",
                subtitle = "DNS lookups from your apps appear here as soon as protection is running."
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(items = shown, key = { it.id }) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = formatTime(entry.time),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = entry.domain,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = entry.qType,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (entry.blocked) "Blocked" else "Allowed",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (entry.blocked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun EmptyLog(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
