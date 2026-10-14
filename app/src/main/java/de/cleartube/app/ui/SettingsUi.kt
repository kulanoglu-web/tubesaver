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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
                Switch(checked = vm.cleanupEnabled, onCheckedChange = { vm.updateCleanupEnabled(it) })
            }
            if (vm.cleanupEnabled) {
                Text("Nach ${vm.cleanupDays} Tagen", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = vm.cleanupDays.toFloat(),
                    onValueChange = { vm.updateCleanupDays(it.roundToInt().coerceIn(1, 90)) },
                    valueRange = 1f..90f
                )
                Text("Dateien ab ${vm.cleanupMb} MB", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = vm.cleanupMb.toFloat(),
                    onValueChange = { vm.updateCleanupMb(((it / 50f).roundToInt() * 50).coerceIn(50, 1000)) },
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

        SectionCard("Player") {
            var player by remember { mutableStateOf(Store.player) }
            Text(
                "Beide Player sind Open Source. Bei einem Fehler wechselt die App automatisch zum anderen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf(
                "vlc" to "VLC (libVLC) – spielt fast alles ab",
                "exo" to "ExoPlayer (Media3) – schlank und schnell",
                "extern" to "Andere App auf dem Gerät (z. B. VLC-App, MX Player)"
            ).forEach { (key, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { player = key; Store.player = key },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = player == key, onClick = { player = key; Store.player = key })
                    Text(label)
                }
            }
        }

        SectionCard("Serien") {
            var auto by remember { mutableStateOf(Store.seriesAutoDownload) }
            var del by remember { mutableStateOf(Store.seriesAutoDelete) }
            var days by remember { mutableStateOf(Store.seriesKeepDays) }
            var height by remember { mutableStateOf(Store.seriesHeight) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Neue Folgen automatisch laden")
                    Text(
                        "Alle 6 Stunden und beim Öffnen der App wird nach neuen Folgen gesucht.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = auto, onCheckedChange = { auto = it; Store.seriesAutoDownload = it })
            }
            var minMin by remember { mutableStateOf(Store.seriesMinMinutes) }
            Text("Ganze Folge ab $minMin Minuten", style = MaterialTheme.typography.labelLarge)
            Text(
                "Kürzere Videos (Vorschau, Ausschnitte) werden nicht automatisch geladen und standardmäßig ausgeblendet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = minMin.toFloat(),
                onValueChange = { minMin = ((it / 5f).roundToInt() * 5).coerceIn(10, 120); Store.seriesMinMinutes = minMin },
                valueRange = 10f..120f
            )
            Text("Qualität für automatische Downloads", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(480, 720, 1080).forEach { h ->
                    FilterChip(
                        selected = height == h,
                        onClick = { height = h; Store.seriesHeight = h },
                        label = { Text("${h}p") }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Folgen automatisch löschen")
                    Text(
                        "Gilt für Folgen aus abonnierten Serien. Du kannst jederzeit auch von Hand löschen.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = del, onCheckedChange = { del = it; Store.seriesAutoDelete = it })
            }
            if (del) {
                Text("Nach $days Tagen", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = days.toFloat(),
                    onValueChange = { days = it.roundToInt().coerceIn(1, 60); Store.seriesKeepDays = days },
                    valueRange = 1f..60f
                )
            }
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
