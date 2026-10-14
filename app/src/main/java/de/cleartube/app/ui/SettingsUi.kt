@file:OptIn(ExperimentalMaterial3Api::class)

package de.cleartube.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Store
import de.cleartube.app.download.Cleanup
import de.cleartube.app.download.DownloadHub
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, onInfo: () -> Unit, onCheckCleanup: () -> Unit) {
    val pickFolder = rememberFolderPicker(vm)
    val jobs by DownloadHub.jobs.collectAsState()
    val done = jobs.filter { it.state == JobState.DONE }
    val total = done.sumOf { it.fileSize }
    val version = rememberVersionText()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Einstellungen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))

        SectionCard("Speicherort für Downloads") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(prettyPath(vm.treeUri), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Hier werden neue Downloads gespeichert. Im Download-Fenster kannst du ihn auch pro Download ändern.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(onClick = pickFolder, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.treeUri == null) "Ordner wählen" else "Ordner ändern")
            }
        }

        SectionCard("Speicher aufräumen") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Große Dateien zum Löschen vorschlagen")
                    Text(
                        "Du bekommst einen Vorschlag, nie ein automatisches Löschen.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = vm.cleanupEnabled, onCheckedChange = { vm.setCleanupEnabled(it) })
            }
            if (vm.cleanupEnabled) {
                Text("Nach ${vm.cleanupDays} Tagen", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = vm.cleanupDays.toFloat(),
                    onValueChange = { vm.setCleanupDays(it.roundToInt().coerceIn(1, 90)) },
                    valueRange = 1f..90f
                )
                Text("Dateien ab ${vm.cleanupMb} MB", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = vm.cleanupMb.toFloat(),
                    onValueChange = { vm.setCleanupMb(((it / 50f).roundToInt() * 50).coerceIn(50, 1000)) },
                    valueRange = 50f..1000f
                )
            }
            Text(
                "In deiner Liste: ${done.size} Datei(en), zusammen ca. ${formatSize(total)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onCheckCleanup, modifier = Modifier.fillMaxWidth()) { Text("Jetzt prüfen") }
        }

        SectionCard("Info") {
            Row(
                Modifier.fillMaxWidth().clickable { onInfo() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Über ClearTube")
                    Text(
                        version, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Vorschlag zum Löschen großer, alter Dateien; alles ist vorausgewählt und abwählbar. */
@Composable
fun CleanupDialog(
    items: List<Cleanup.Candidate>,
    onDismiss: () -> Unit,
    onDelete: (List<Cleanup.Candidate>) -> Unit
) {
    val selected = remember { mutableStateListOf<Long>().apply { addAll(items.map { it.job.id }) } }
    val chosen = items.filter { it.job.id in selected }

    fun toggle(id: Long) {
        if (id in selected) selected.remove(id) else selected.add(id)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Speicher freigeben?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${items.size} Datei(en) sind größer als ${Store.cleanupMb} MB und älter als " +
                        "${Store.cleanupDays} Tage. Die markierten werden gelöscht:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.padding(top = 8.dp))
                items.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { toggle(c.job.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = c.job.id in selected, onCheckedChange = { toggle(c.job.id) })
                        Column(Modifier.weight(1f)) {
                            Text(c.job.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${formatSize(c.sizeBytes)} · vor ${c.ageDays} Tagen",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onDelete(chosen) }) {
                Text("Löschen (${formatSize(chosen.sumOf { it.sizeBytes })})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Später") } }
    )
}
