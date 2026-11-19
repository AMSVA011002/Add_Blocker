package dev.addblocker.adshield.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.addblocker.adshield.data.BlockResponse
import dev.addblocker.adshield.data.Prefs
import dev.addblocker.adshield.data.QueryLog
import dev.addblocker.adshield.data.RulesRepository
import dev.addblocker.adshield.data.StatsRepository
import dev.addblocker.adshield.data.Upstream
import dev.addblocker.adshield.vpn.AdBlockVpnService
import dev.addblocker.adshield.vpn.VpnStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onRequestNotificationPermission: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by VpnStatus.running.collectAsStateWithLifecycle()
    val rules by RulesRepository.state.collectAsStateWithLifecycle()

    var upstream by remember { mutableStateOf(Prefs.upstream) }
    var responseMode by remember { mutableStateOf(Prefs.blockResponse) }
    var interceptHardcoded by remember { mutableStateOf(Prefs.interceptHardcodedDns) }
    var logQueries by remember { mutableStateOf(Prefs.logQueries) }
    var pendingRestart by remember { mutableStateOf(false) }

    val notificationsGranted = remember {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            SectionCard(
                title = "Upstream resolver",
                subtitle = "Used for names that are not blocked. AdShield never sees your queries beyond this device except for this forward."
            ) {
                Upstream.values().forEach { option ->
                    RadioRow(
                        title = option.title,
                        detail = "${option.primary} · ${option.secondary}",
                        selected = upstream == option,
                        onClick = {
                            Prefs.upstream = option
                            upstream = option
                            pendingRestart = true
                        }
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "Blocked answers",
                subtitle = "How a filtered lookup is answered. NXDOMAIN is the safest default."
            ) {
                BlockResponse.values().forEach { option ->
                    RadioRow(
                        title = option.title,
                        detail = option.detail,
                        selected = responseMode == option,
                        onClick = {
                            Prefs.blockResponse = option
                            responseMode = option
                        }
                    )
                }
            }
        }

        item {
            SectionCard(title = "Engine") {
                SwitchRow(
                    title = "Catch hard-coded DNS servers",
                    subtitle = "Routes well known resolvers such as 8.8.8.8 into the filter, so apps cannot bypass it.",
                    checked = interceptHardcoded,
                    onCheckedChange = {
                        Prefs.interceptHardcodedDns = it
                        interceptHardcoded = it
                        pendingRestart = true
                    }
                )
                HorizontalDivider()
                SwitchRow(
                    title = "Keep a query log",
                    subtitle = "Recent names are held in memory only. Nothing is written to disk.",
                    checked = logQueries,
                    onCheckedChange = {
                        Prefs.logQueries = it
                        logQueries = it
                        if (!it) QueryLog.clear()
                    }
                )
            }
        }

        if (pendingRestart && running) {
            item {
                SectionCard(title = "Restart needed") {
                    Text(
                        "Some changes only apply to a fresh tunnel. Restarting protection takes a second and keeps your rules.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            AdBlockVpnService.stop(context)
                            scope.launch {
                                delay(700)
                                AdBlockVpnService.start(context)
                                pendingRestart = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restart protection")
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Keep it on",
                subtitle = "Android can stop any VPN to save power. These two settings keep protection alive."
            ) {
                Text(
                    "1. Turn on “Always-on VPN” for AdShield and enable “Block connections without VPN” only if you accept that a crash will cut your connection.\n" +
                        "2. Set Private DNS to Automatic (or Off). A Private DNS hostname uses encrypted DNS outside the filter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        val launched = runCatching {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        }.isSuccess
                        if (!launched) {
                            Toast.makeText(context, "Open Settings › Network › VPN", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open VPN settings")
                }
                OutlinedButton(
                    onClick = {
                        val launched = runCatching {
                            context.startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
                        }.isSuccess
                        if (!launched) {
                            Toast.makeText(context, "Open Settings › Network › Private DNS", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Private DNS settings")
                }
                if (!notificationsGranted) {
                    OutlinedButton(
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Allow notifications")
                    }
                }
            }
        }

        item {
            SectionCard(title = "Status") {
                InfoRow("Protection", if (running) "Running" else "Stopped")
                InfoRow("Rules loaded", formatCount(rules.activeRules.toLong()))
                InfoRow("Enabled lists", "${rules.lists.count { it.enabled }} of ${rules.lists.size}")
                InfoRow("Upstream", "${Prefs.upstream.title} (${Prefs.upstream.primary})")
                InfoRow("Blocked answer", Prefs.blockResponse.title)
                InfoRow("Version", versionName)
            }
        }

        item {
            SectionCard(title = "Data") {
                OutlinedButton(
                    onClick = {
                        StatsRepository.resetToday()
                        QueryLog.clear()
                        Toast.makeText(context, "Counters cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset today's counters")
                }
            }
        }

        item {
            SectionCard(title = "About") {
                Text(
                    "AdShield is an on-device DNS filter. It has no servers, no accounts and no analytics. " +
                        "Traffic other than DNS lookups is never touched. Source is MIT licensed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun RadioRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
