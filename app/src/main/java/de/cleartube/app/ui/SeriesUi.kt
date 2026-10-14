@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.cleartube.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import de.cleartube.app.data.ChannelHit
import de.cleartube.app.data.Episode
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Series
import de.cleartube.app.data.SeriesHit
import de.cleartube.app.data.SeriesRepo
import de.cleartube.app.data.Store
import de.cleartube.app.download.DownloadHub

@Composable
fun SeriesScreen(vm: SeriesViewModel = viewModel(), onPick: (Episode, SeriesHit?) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    BackHandler(enabled = vm.view != SView.HOME) { vm.back() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }
    vm.pendingUnverified?.let { h ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.pendingUnverified = null },
            title = { Text("Nicht verifizierter Kanal") },
            text = {
                Text(
                    "„${h.channelName.ifEmpty { h.name }}“ hat keinen Verifizierungshaken von YouTube. " +
                        "Prüfe, dass es der echte Kanal der Serie oder des Senders ist (Name, Logo, Abonnentenzahl), " +
                        "sonst können es Fälschungen oder Fremd-Uploads sein. Trotzdem hinzufügen?"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.pendingUnverified = null; vm.subscribe(h, true) }) {
                    Text("Trotzdem hinzufügen")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.pendingUnverified = null }) { Text("Abbrechen") }
            }
        )
    }
    when (vm.view) {
        SView.HOME -> HomeView(vm)
        SView.DISCOVER -> DiscoverView(vm, onPick)
        SView.CHANNEL -> ChannelView(vm)
        SView.DETAIL -> DetailView(vm, onPick)
    }
}

@Composable
private fun TopRow(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Zurück") }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title, style = MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        actions()
    }
}

@Composable
private fun Thumb(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CardRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun OfficialBadgeNamed(name: String, official: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (official) Icons.Default.Verified else Icons.Default.Warning, null, Modifier.size(14.dp),
            tint = if (official) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(4.dp))
        Text(
            name + if (official) "" else " (nicht verifiziert)",
            style = MaterialTheme.typography.labelSmall,
            color = if (official) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OfficialBadge(official: Boolean) {
    if (official) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text("Offizieller Kanal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(4.dp))
            Text("Nicht verifiziert", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ------------------------------------------------------------------ Meine Serien

@Composable
private fun HomeView(vm: SeriesViewModel) {
    val jobs by DownloadHub.jobs.collectAsState()
    LaunchedEffect(Unit) { vm.refreshMine() }
    Column(Modifier.fillMaxSize()) {
        TopRow("Serien")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.view = SView.DISCOVER }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Serie hinzufügen")
                    }
                    OutlinedButton(onClick = { vm.checkNow() }, enabled = !vm.checking) {
                        if (vm.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Neue Folgen suchen")
                    }
                }
            }
            if (vm.mine.isEmpty()) {
                item {
                    Text(
                        "Noch keine Serie. Tippe auf „Serie hinzufügen“, wähle einen Sender oder suche den Namen. " +
                            "Neue Folgen werden danach automatisch geladen und nach ${Store.seriesKeepDays} Tagen wieder gelöscht.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
            items(vm.mine, key = { it.id }) { s ->
                val loaded = jobs.count { it.seriesId == s.id && it.state == JobState.DONE }
                val unseen = jobs.count { it.seriesId == s.id && it.state == JobState.DONE && !Store.watched(it.id) }
                SeriesRow(s, loaded, unseen, onOpen = { vm.openMine(s) }, onRemove = { vm.unsubscribe(s.id) })
            }
        }
    }
}

@Composable
private fun SeriesRow(s: Series, loaded: Int, unseen: Int, onOpen: () -> Unit, onRemove: () -> Unit) {
    CardRow(onClick = onOpen) {
        Thumb(s.thumbnail, Modifier.width(120.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "Quelle: " + s.channelName.ifEmpty { "unbekannt" } + if (s.pref.isNotEmpty()) " · ${s.pref}" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (loaded == 0) "Keine Folge geladen"
                else "$loaded geladen" + if (unseen > 0) " · $unseen ungesehen" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (unseen > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Serie entfernen") }
    }
}

// ------------------------------------------------------------------ Entdecken

@Composable
private fun DiscoverView(vm: SeriesViewModel, onPick: (Episode, SeriesHit?) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopRow("Serie hinzufügen", onBack = { vm.back() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                singleLine = true,
                placeholder = { Text("Serie oder Sender suchen") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(vm.query) }),
                modifier = Modifier.fillMaxWidth()
            )
            Text("Sender", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeriesRepo.BROADCASTERS.forEach { b ->
                    FilterChip(selected = vm.query == b, onClick = { vm.search(b, asBroadcaster = true) }, label = { Text(b) })
                }
            }
        }
        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                vm.error?.let { e -> item { Text("Fehler: $e", color = MaterialTheme.colorScheme.error) } }
                if (vm.searched && vm.channels.isEmpty() && vm.playlists.isEmpty() && vm.videos.isEmpty() && vm.error == null) {
                    item { Text("Nichts gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (vm.channels.isNotEmpty()) {
                    item { Text("Kanäle (Serie oder Sender)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(vm.channels, key = { "c" + it.url }) { c -> ChannelRow(c) { vm.openChannel(c) } }
                }
                if (vm.videos.isNotEmpty()) {
                    item { Text("Ganze Folgen", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(vm.videos, key = { "v" + it.ep.url }) { v ->
                        val ctx = LocalContext.current
                        val jobs by DownloadHub.jobs.collectAsState()
                        val job = jobs.lastOrNull { it.videoUrl == v.ep.url && it.state != JobState.FAILED && it.state != JobState.CANCELLED }
                        EpisodeRow(
                            v.ep, job, v.ep.url in vm.busy,
                            onDownload = {
                                onPick(
                                    v.ep,
                                    v.channelUrl?.let { u -> SeriesHit(u, v.channelName, v.ep.thumbnail, v.channelName, v.verified, -1) }
                                )
                            },
                            onPlay = { job?.let { openFile(ctx, it) } },
                            channel = v.channelName, verified = v.verified
                        )
                    }
                }
                if (vm.playlists.isNotEmpty()) {
                    item { Text("Serien", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(vm.playlists, key = { "p" + it.url }) { h ->
                        HitRow(h, vm.isSubscribed(h.url)) { vm.openSeries(h, SView.DISCOVER) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(c: ChannelHit, onClick: () -> Unit) {
    CardRow(onClick = onClick) {
        AsyncImage(
            model = c.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(28.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OfficialBadge(c.verified)
        }
        Text("Serien ansehen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun HitRow(h: SeriesHit, subscribed: Boolean, onClick: () -> Unit) {
    CardRow(onClick = onClick) {
        Thumb(h.thumbnail, Modifier.width(120.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                h.channelName + if (h.episodes > 0) " · ${h.episodes} Folgen" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            OfficialBadge(h.verified)
        }
        if (subscribed) Icon(Icons.Default.Check, "Hinzugefügt", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ChannelView(vm: SeriesViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopRow(vm.channelName, onBack = { vm.back() })
        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                vm.error?.let { e -> item { Text("Fehler: $e", color = MaterialTheme.colorScheme.error) } }
                vm.channelSelf?.let { self ->
                    item {
                        HitRow(self.copy(name = "Alle Folgen dieses Kanals"), vm.isSubscribed(self.url)) {
                            vm.openSeries(self, SView.CHANNEL)
                        }
                    }
                }
                if (vm.channelSeries.isNotEmpty()) {
                    item { Text("Playlists des Kanals", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                }
                items(vm.channelSeries, key = { it.url }) { h ->
                    HitRow(h, vm.isSubscribed(h.url)) { vm.openSeries(h, SView.CHANNEL) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Folgen einer Serie

@Composable
private fun DetailView(vm: SeriesViewModel, onPick: (Episode, SeriesHit?) -> Unit) {
    val ctx = LocalContext.current
    val hit = vm.detail ?: return
    val jobs by DownloadHub.jobs.collectAsState()
    val subscribed = vm.isSubscribed(hit.url)

    Column(Modifier.fillMaxSize()) {
        TopRow(hit.name, onBack = { vm.back() })
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(hit.channelName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OfficialBadge(hit.verified)
            if (subscribed) {
                FilledTonalButton(onClick = { vm.unsubscribe(hit.url) }) { Text("Abonniert – entfernen") }
            } else {
                Button(onClick = { vm.subscribe(hit) }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (hit.verified) "Serie hinzufügen (neue Folgen automatisch)" else "Hinzufügen (nicht verifiziert)")
                }
            }
        }
        if (!vm.loading) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !vm.showAll, onClick = { vm.showAll = false },
                    label = { Text("Ganze Folgen (ab ${Store.seriesMinMinutes} Min.)") }
                )
                FilterChip(
                    selected = vm.showAll, onClick = { vm.showAll = true },
                    label = { Text("Alle" + if (vm.hiddenCount > 0) " (+${vm.hiddenCount} Vorschau)" else "") }
                )
            }
        }
        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                vm.error?.let { e -> item { Text("Fehler: $e", color = MaterialTheme.colorScheme.error) } }
                if (vm.visible.isEmpty() && vm.error == null) {
                    item {
                        Text(
                            "Keine ganze Folge gefunden – nur Vorschauen oder Ausschnitte. Tippe auf „Alle“, um sie zu sehen.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(vm.visible, key = { it.url }) { ep ->
                    val job = jobs.lastOrNull { it.videoUrl == ep.url && it.state != JobState.FAILED && it.state != JobState.CANCELLED }
                    EpisodeRow(
                        ep, job, ep.url in vm.busy,
                        onDownload = { onPick(ep, hit) },
                        onPlay = { job?.let { openFile(ctx, it) } },
                        foreign = if (vm.isForeign(ep)) ep.uploaderName.ifEmpty { "unbekannt" } else null
                    )
                }
                if (vm.hasMore) {
                    item {
                        OutlinedButton(onClick = { vm.loadMore() }, modifier = Modifier.fillMaxWidth()) { Text("Mehr Folgen laden") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: Episode,
    job: de.cleartube.app.data.DownloadJob?,
    busy: Boolean,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    channel: String? = null,
    verified: Boolean = true,
    foreign: String? = null
) {
    val done = job?.state == JobState.DONE && job.resultUri != null
    CardRow(onClick = if (done) onPlay else if (job == null && !busy) onDownload else ({})) {
        Thumb(ep.thumbnail, Modifier.width(120.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (channel != null) OfficialBadgeNamed(channel, verified)
            val sub = listOfNotNull(formatDuration(ep.durationSec), ep.uploadedText).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (foreign != null) {
                Text(
                    "Fremder Kanal: $foreign – wird nicht automatisch geladen",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                )
            }
            if (!ep.isFull()) {
                Text("Vorschau / Ausschnitt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            val status = when {
                done -> if (Store.watched(job!!.id)) "Geladen · gesehen" else "Geladen · antippen zum Abspielen"
                job != null -> "Lädt… ${(job.progress * 100).toInt()} %"
                busy -> "Wird vorbereitet…"
                else -> null
            }
            status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
        when {
            done -> Icon(Icons.Default.PlayArrow, "Abspielen", tint = MaterialTheme.colorScheme.primary)
            job != null || busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else -> Icon(Icons.Default.Download, "Laden")
        }
    }
}
