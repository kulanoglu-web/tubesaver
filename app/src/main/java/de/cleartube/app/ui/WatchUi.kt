@file:OptIn(ExperimentalMaterial3Api::class)

package de.cleartube.app.ui

import android.widget.Toast
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import de.cleartube.app.data.DownloadJob
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Store
import de.cleartube.app.download.Cleanup
import de.cleartube.app.download.DownloadHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Ansehen": alle fertig geladenen Videos, ungesehene zuerst, neueste oben. */
@Composable
fun WatchScreen(jobs: List<DownloadJob>, onGoSeries: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Nach der Rückkehr aus dem Player Gesehen-Status und Position neu lesen
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val done = jobs.filter { it.state == JobState.DONE && it.resultUri != null }.sortedByDescending { it.finishedAt }
    // Dateien, die inzwischen gelöscht wurden, werden ausgeblendet
    val existing by produceState(done, done.map { it.id }) {
        value = withContext(Dispatchers.IO) { done.filter { Cleanup.exists(ctx, it.resultUri!!) } }
    }
    val unseen = remember(existing, tick) { existing.filter { !Store.watched(it.id) } }
    val seen = remember(existing, tick) { existing.filter { Store.watched(it.id) } }
    var deleting by remember { mutableStateOf<DownloadJob?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ansehen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (existing.isNotEmpty()) {
                Text(
                    "${unseen.size} neu · ${existing.size} insgesamt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
        }
        if (existing.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(
                        "Noch nichts zum Anschauen. Geladene Folgen und Videos erscheinen hier.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onGoSeries) { Text("Zu den Serien") }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (unseen.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Neu", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    items(unseen, key = { it.id }) { j -> WatchCard(j, tick, { tick++ }, { deleting = j }) }
                }
                if (seen.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Schon gesehen", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(seen, key = { it.id }) { j -> WatchCard(j, tick, { tick++ }, { deleting = j }) }
                }
            }
        }
    }

    deleting?.let { j ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Datei löschen?") },
            text = { Text("„${j.title}“ wird endgültig vom Gerät gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val uri = j.resultUri
                    if (uri != null && Cleanup.delete(ctx, uri)) {
                        DownloadHub.remove(j.id)
                    } else {
                        Toast.makeText(ctx, "Löschen nicht möglich", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } }
        )
    }
}

private fun ageText(finishedAt: Long): String {
    val days = ((System.currentTimeMillis() - finishedAt) / 86_400_000L).toInt()
    return when {
        finishedAt <= 0 -> ""
        days <= 0 -> "heute geladen"
        days == 1 -> "gestern geladen"
        else -> "vor $days Tagen geladen"
    }
}

@Composable
private fun WatchCard(j: DownloadJob, tick: Int, onChanged: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val seen = remember(tick, j.id) { Store.watched(j.id) }
    val pos = remember(tick, j.id) { Store.position(j.id) }

    Card(
        onClick = { openFile(ctx, j) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                AsyncImage(
                    model = j.thumbnail, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                Box(Modifier.fillMaxSize().background(Color(0x33000000)), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0x99000000)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.PlayArrow, "Abspielen", tint = Color.White, modifier = Modifier.size(34.dp)) }
                }
                if (!seen) {
                    Text(
                        "NEU", color = Color.White, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(10.dp).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                if (pos > 10_000 && !seen) {
                    LinearProgressIndicator(
                        progress = { 0.5f },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    )
                }
            }
            Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        j.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                    )
                    val sub = listOfNotNull(
                        if (j.fileSize > 0) formatSize(j.fileSize) else null,
                        ageText(j.finishedAt).takeIf { it.isNotEmpty() },
                        if (pos > 10_000 && !seen) "weiter bei " + (formatDuration(pos / 1000) ?: "0:00") else null
                    ).joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menü") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (seen) "Als ungesehen markieren" else "Als gesehen markieren") },
                            leadingIcon = { Icon(if (seen) Icons.Default.VisibilityOff else Icons.Default.Check, null) },
                            onClick = {
                                menu = false
                                Store.setWatched(j.id, !seen)
                                if (!seen) Store.setPosition(j.id, 0)
                                onChanged()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mit VLC abspielen") },
                            leadingIcon = { Icon(Icons.Default.Visibility, null) },
                            onClick = { menu = false; openFile(ctx, j, "vlc") }
                        )
                        DropdownMenuItem(
                            text = { Text("Mit ExoPlayer abspielen") },
                            onClick = { menu = false; openFile(ctx, j, "exo") }
                        )
                        DropdownMenuItem(
                            text = { Text("Datei löschen") },
                            leadingIcon = { Icon(Icons.Default.DeleteForever, null) },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}
