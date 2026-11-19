package dev.addblocker.adshield.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.addblocker.adshield.data.BlockList
import dev.addblocker.adshield.data.ListKind
import dev.addblocker.adshield.data.RulesRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen() {
    val rules by RulesRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var domainInput by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = RulesRepository.importFromUri(uri)
                if (result.isSuccess) {
                    Toast.makeText(context, "List imported: ${result.getOrNull()?.ruleCount} entries", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, result.exceptionOrNull()?.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val busy = rules.busyId != null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Filtering rules",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Text(
                text = "${formatCount(rules.activeRules.toLong())} names blocked · " +
                    "${rules.customBlocked.size} custom blocks · ${rules.customAllowed.size} exceptions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (rules.error != null) {
            item {
                Text(
                    text = rules.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            SectionCard(
                title = "Rule sets",
                subtitle = "Every enabled set is merged into one list. Enabled lists are re-read immediately."
            ) {
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Working…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                rules.lists.forEach { list ->
                    ListRow(
                        list = list,
                        onToggle = { enabled -> RulesRepository.setListEnabled(list.id, enabled) },
                        onDelete = { RulesRepository.deleteList(list.id) },
                        onRefresh = {
                            scope.launch {
                                val result = RulesRepository.refreshList(list.id)
                                if (result.isFailure) {
                                    Toast.makeText(
                                        context,
                                        result.exceptionOrNull()?.message ?: "Refresh failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        busy = rules.busyId == list.id
                    )
                    HorizontalDivider()
                }
            }
        }

        item {
            SectionCard(
                title = "Add a list",
                subtitle = "Paste the URL of a hosts file or domain list. It is downloaded, normalised and stored on the device."
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("https://example.com/hosts.txt") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val result = RulesRepository.addRemoteList(url)
                                if (result.isSuccess) {
                                    url = ""
                                    Toast.makeText(context, "List added", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        result.exceptionOrNull()?.message ?: "Could not add this list",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = !busy && url.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Add list")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = !busy
                    ) {
                        Text("Import file")
                    }
                }

                Text(
                    text = "Suggested lists",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RulesRepository.presets.forEach { preset ->
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    val result = RulesRepository.addRemoteList(preset.url)
                                    if (result.isSuccess) {
                                        Toast.makeText(context, "${preset.title} added", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            result.exceptionOrNull()?.message ?: "Download failed",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            enabled = !busy,
                            label = { Text(preset.title) }
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Your rules",
                subtitle = "Exceptions win over every list, so add one here if a site or app stops working."
            ) {
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    label = { Text("ads.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val result = RulesRepository.addCustomBlocked(domainInput)
                                if (result.isSuccess) domainInput = "" else {
                                    Toast.makeText(
                                        context,
                                        result.exceptionOrNull()?.message ?: "Invalid domain",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        enabled = domainInput.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Block")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = RulesRepository.addCustomAllowed(domainInput)
                                if (result.isSuccess) domainInput = "" else {
                                    Toast.makeText(
                                        context,
                                        result.exceptionOrNull()?.message ?: "Invalid domain",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        enabled = domainInput.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Allow")
                    }
                }

                if (rules.customBlocked.isNotEmpty()) {
                    Text("Blocked by you", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rules.customBlocked.forEach { domain ->
                            AssistChip(
                                onClick = {
                                    scope.launch { RulesRepository.removeCustom(domain, blockedRule = true) }
                                },
                                label = { Text(domain) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                if (rules.customAllowed.isNotEmpty()) {
                    Text("Always allowed", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rules.customAllowed.forEach { domain ->
                            AssistChip(
                                onClick = {
                                    scope.launch { RulesRepository.removeCustom(domain, blockedRule = false) }
                                },
                                label = { Text(domain) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(
    list: BlockList,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    busy: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = list.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(formatCount(list.ruleCount.toLong()))
                    append(" entries")
                    if (list.kind != ListKind.BUNDLED) {
                        append(" · ")
                        append(if (list.kind == ListKind.REMOTE) "downloaded" else "imported")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            if (list.kind == ListKind.REMOTE) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh list")
                }
            }
            if (list.kind != ListKind.BUNDLED) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete list")
                }
            }
            Switch(checked = list.enabled, onCheckedChange = onToggle)
        }
    }
}
