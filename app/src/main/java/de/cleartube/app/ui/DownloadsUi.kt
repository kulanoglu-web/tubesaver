@file:OptIn(ExperimentalMaterial3Api::class)

package de.cleartube.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import de.cleartube.app.download.DownloadService
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.cleartube.app.data.DownloadJob
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Kind
import de.cleartube.app.download.Cleanup
import de.cleartube.app.download.DownloadHub

@Composable
fun DownloadsScreen(jobs: List<DownloadJob>) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Downloads", style = MaterialTheme.typography.titleLarge)
            if (jobs.isNotEmpty()) {
                TextButton(onClick = { DownloadHub.clearFinished() }) { Text("Liste leeren") }
            }
        }
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Download, null,
                        Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                    Text("Noch keine Downloads", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(jobs.reversed(), key = { it.id }) { j -> JobCard(j) }
            }
        }
    }
}

@Composable
fun JobCard(j: DownloadJob) {
    val ctx = LocalContext.current
    val finished = j.state == JobState.DONE || j.state == JobState.FAILED || j.state == JobState.CANCELLED
    val canOpen = j.state == JobState.DONE && j.resultUri != null
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    val status = when (j.state) {
        JobState.QUEUED -> "Wartet…"
        JobState.RUNNING ->
            "Lädt… ${(j.progress * 100).toInt()} %" + (j.message?.let { " · $it" } ?: "")
        JobState.MUXING -> j.message ?: "Wird zusammengefügt…"
        JobState.DONE -> {
            val size = if (j.fileSize > 0) formatSize(j.fileSize) + " · " else ""
            size + (j.message?.let { "$it · " } ?: "") + (if (canOpen) (if (de.cleartube.app.data.Store.watched(j.id)) "gesehen · antippen zum Abspielen" else "antippen zum Abspielen") else "fertig")
        }
        JobState.FAILED -> "Fehler: ${j.message ?: "unbekannt"}"
        JobState.CANCELLED -> "Abgebrochen"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .then(if (canOpen) Modifier.clickable { openFile(ctx, j) } else Modifier)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = j.thumbnail, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                if (canOpen) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0x55000000)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.PlayArrow, null, tint = Color.White) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    j.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (j.option.kind == Kind.AUDIO) Icons.Default.MusicNote else Icons.Default.Movie,
                        null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        (if (j.channel.isNotEmpty()) j.channel + " · " else "") + j.option.label,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!finished) {
                    Spacer(Modifier.padding(top = 6.dp))
                    LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (j.state == JobState.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!finished) {
                IconButton(onClick = { DownloadHub.cancel(j.id) }) { Icon(Icons.Default.Close, "Abbrechen") }
            } else {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menü") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (j.state == JobState.FAILED || j.state == JobState.CANCELLED) {
                            DropdownMenuItem(
                                text = { Text("Erneut versuchen") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = {
                                    menu = false
                                    DownloadHub.retry(j.id)
                                    ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
                                }
                            )
                        }
                        if (canOpen) {
                            DropdownMenuItem(
                                text = { Text("Abspielen") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                onClick = { menu = false; openFile(ctx, j) }
                            )
                            DropdownMenuItem(
                                text = { Text("Mit VLC abspielen") },
                                onClick = { menu = false; openFile(ctx, j, "vlc") }
                            )
                            DropdownMenuItem(
                                text = { Text("Mit ExoPlayer abspielen") },
                                onClick = { menu = false; openFile(ctx, j, "exo") }
                            )
                            DropdownMenuItem(
                                text = { Text("In anderer App öffnen") },
                                onClick = { menu = false; openFile(ctx, j, "extern") }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Aus Liste entfernen") },
                            leadingIcon = { Icon(Icons.Default.Close, null) },
                            onClick = { menu = false; DownloadHub.remove(j.id) }
                        )
                        if (canOpen) {
                            DropdownMenuItem(
                                text = { Text("Datei löschen") },
                                leadingIcon = { Icon(Icons.Default.DeleteForever, null) },
                                onClick = { menu = false; confirm = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Datei löschen?") },
            text = { Text("„${j.title}“ wird endgültig vom Gerät gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val uri = j.resultUri
                    if (uri != null && Cleanup.delete(ctx, uri)) {
                        DownloadHub.remove(j.id)
                    } else {
                        Toast.makeText(ctx, "Löschen nicht möglich", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } }
        )
    }
}
