@file:OptIn(ExperimentalMaterial3Api::class)

package de.cleartube.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Kind
import de.cleartube.app.data.Option
import de.cleartube.app.data.Store
import de.cleartube.app.data.VideoDetails
import de.cleartube.app.data.VideoItem
import de.cleartube.app.download.Cleanup
import de.cleartube.app.download.DownloadHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAB_SEARCH = 0
private const val TAB_SERIES = 1
private const val TAB_DOWNLOADS = 2
private const val TAB_SETTINGS = 3
private const val TAB_WATCH = 4

@Composable
fun AppRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = remember { isTelevision(ctx) }
    val seriesVm: SeriesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val hasWatchable = remember { DownloadHub.jobs.value.any { it.state == JobState.DONE && it.resultUri != null } }
    var tab by rememberSaveable { mutableIntStateOf(if (hasWatchable || tv) TAB_WATCH else TAB_SEARCH) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var cleanup by remember { mutableStateOf<List<Cleanup.Candidate>?>(null) }
    val jobs by DownloadHub.jobs.collectAsState()
    val active = jobs.count {
        it.state == JobState.QUEUED || it.state == JobState.RUNNING || it.state == JobState.MUXING
    }

    // Zurück: erst innerhalb eines Reiters, dann zur Suche, erst dann die App verlassen
    BackHandler(enabled = tab != TAB_WATCH && !(tab == TAB_SERIES && seriesVm.view != SView.HOME) &&
        !(tab == TAB_SEARCH && vm.details == null && (vm.results.isNotEmpty() || vm.error != null))) { tab = TAB_WATCH }
    BackHandler(enabled = tab == TAB_SEARCH && vm.details == null && (vm.results.isNotEmpty() || vm.error != null)) {
        vm.clearResults()
    }

    // Beim Start: wartende Downloads fortsetzen, alte Folgen aufräumen, nach neuen Folgen suchen
    LaunchedEffect(Unit) {
        if (DownloadHub.nextQueued() != null) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    ctx, android.content.Intent(ctx, de.cleartube.app.download.DownloadService::class.java)
                )
            }
        }
        withContext(Dispatchers.IO) { de.cleartube.app.download.SeriesSync.autoDelete(ctx) }
        if (Store.seriesAutoDownload && Store.loadSeries().isNotEmpty()) {
            runCatching { de.cleartube.app.download.SeriesSync.check(ctx) }
            seriesVm.refreshMine()
        }
        val dayPassed = System.currentTimeMillis() - Store.cleanupLastPrompt > 24L * 3600 * 1000
        if (Store.cleanupEnabled && dayPassed) {
            val found = withContext(Dispatchers.IO) { Cleanup.find(ctx) }
            if (found.isNotEmpty()) cleanup = found
        }
    }

    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                TAB_WATCH -> WatchScreen(jobs, onGoSeries = { tab = TAB_SERIES })
                TAB_SEARCH -> SearchScreen(vm)
                TAB_SERIES -> SeriesScreen(seriesVm, onPick = { ep, hit -> vm.openFromSeries(ep.url, hit) })
                TAB_DOWNLOADS -> DownloadsScreen(jobs)
                else -> SettingsScreen(
                    vm = vm,
                    onInfo = { showInfo = true },
                    onCheckCleanup = {
                        scope.launch {
                            val found = withContext(Dispatchers.IO) { Cleanup.find(ctx) }
                            if (found.isEmpty()) {
                                Toast.makeText(
                                    ctx,
                                    "Keine Dateien über ${Store.cleanupMb} MB, die älter als ${Store.cleanupDays} Tage sind",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                cleanup = found
                            }
                        }
                    }
                )
            }
            if (vm.detailsLoading) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        }
    }

    val downloadsIcon: @Composable () -> Unit = {
        if (active > 0) {
            BadgedBox(badge = { Badge { Text("$active") } }) { Icon(Icons.Default.Download, null) }
        } else {
            Icon(Icons.Default.Download, null)
        }
    }

    if (tv) {
        // Fernseher: Navigationsleiste links, große Fläche rechts, alles per Fernbedienung erreichbar
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Spacer(Modifier.weight(1f))
                NavigationRailItem(
                    selected = tab == TAB_WATCH, onClick = { tab = TAB_WATCH },
                    icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Ansehen") }
                )
                NavigationRailItem(
                    selected = tab == TAB_SERIES, onClick = { tab = TAB_SERIES },
                    icon = { Icon(Icons.Default.Tv, null) }, label = { Text("Serien") }
                )
                NavigationRailItem(
                    selected = tab == TAB_SEARCH, onClick = { tab = TAB_SEARCH },
                    icon = { Icon(Icons.Default.Search, null) }, label = { Text("Suche") }
                )
                NavigationRailItem(
                    selected = tab == TAB_DOWNLOADS, onClick = { tab = TAB_DOWNLOADS },
                    icon = downloadsIcon, label = { Text("Downloads") }
                )
                NavigationRailItem(
                    selected = tab == TAB_SETTINGS, onClick = { tab = TAB_SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Einstellungen") }
                )
                Spacer(Modifier.weight(1f))
            }
            Box(Modifier.weight(1f).fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) { content() }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("ClearTube", fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, "Info") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = tab == TAB_WATCH, onClick = { tab = TAB_WATCH },
                        icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Ansehen") }
                    )
                    NavigationBarItem(
                        selected = tab == TAB_SERIES, onClick = { tab = TAB_SERIES },
                        icon = { Icon(Icons.Default.Tv, null) }, label = { Text("Serien") }
                    )
                    NavigationBarItem(
                        selected = tab == TAB_SEARCH, onClick = { tab = TAB_SEARCH },
                        icon = { Icon(Icons.Default.Search, null) }, label = { Text("Suche") }
                    )
                    NavigationBarItem(
                        selected = tab == TAB_DOWNLOADS, onClick = { tab = TAB_DOWNLOADS },
                        icon = downloadsIcon, label = { Text("Downloads") }
                    )
                    NavigationBarItem(
                        selected = tab == TAB_SETTINGS, onClick = { tab = TAB_SETTINGS },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Einstellungen") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) { content() }
        }
    }

    vm.details?.let { d ->
        DetailsSheet(vm, d, onDownload = { opt, keep ->
            val hit = vm.seriesContext
            var sid = ""
            if (hit != null) {
                if (keep) {
                    seriesVm.rememberChoice(hit, opt, d.thumbnail)
                    sid = hit.url
                } else if (seriesVm.isSubscribed(hit.url)) {
                    sid = hit.url
                }
            }
            vm.download(opt, sid)
            tab = TAB_DOWNLOADS
        })
    }

    if (showInfo) InfoDialog(onDismiss = { showInfo = false })

    cleanup?.let { items ->
        CleanupDialog(
            items = items,
            onDismiss = {
                Store.cleanupLastPrompt = System.currentTimeMillis()
                cleanup = null
            },
            onDelete = { chosen ->
                var deleted = 0
                chosen.forEach { c ->
                    val uri = c.job.resultUri
                    if (uri != null && Cleanup.delete(ctx, uri)) {
                        DownloadHub.remove(c.job.id)
                        deleted++
                    }
                }
                Store.cleanupLastPrompt = System.currentTimeMillis()
                cleanup = null
                Toast.makeText(ctx, "$deleted Datei(en) gelöscht", Toast.LENGTH_LONG).show()
            }
        )
    }
}

// ------------------------------------------------------------------- Suche

@Composable
fun SearchScreen(vm: MainViewModel) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Suchen oder YouTube-Link einfügen") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Clear, "Leeren") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.submit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (vm.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        vm.error?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        msg,
                        Modifier.weight(1f).padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = { vm.error = null }) { Icon(Icons.Default.Close, "Schließen") }
                }
            }
        }

        if (vm.results.isEmpty() && !vm.loading) {
            EmptySearch(vm)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(vm.results) { i, item ->
                    VideoCard(
                        item = item,
                        onOpen = { vm.open(item) },
                        onAudio = { vm.open(item, audio = true) }
                    )
                    if (i == vm.results.lastIndex) LaunchedEffect(vm.results.size) { vm.loadMore() }
                }
            }
        }
    }
}

@Composable
private fun EmptySearch(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (vm.recents.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zuletzt gesucht", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { vm.clearRecents() }) { Text("Löschen") }
            }
            vm.recents.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.searchFor(q) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Text(q, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Text(
                "Suche nach Videos oder Musik – oder füge einen YouTube-Link ein.\n\n" +
                    "Tippe auf ein Ergebnis für Video-Qualitäten, oder auf die Note für nur den Ton.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 32.dp)
            )
        }
    }
}

@Composable
fun VideoCard(item: VideoItem, onOpen: () -> Unit, onAudio: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                AsyncImage(
                    model = item.thumbnail, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                formatDuration(item.durationSec)?.let { d ->
                    Text(
                        d,
                        Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    item.uploader, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.views >= 0) {
                    Text(
                        "${formatViews(item.views)} Aufrufe",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            FilledTonalIconButton(onClick = onAudio) {
                Icon(Icons.Default.MusicNote, "Nur Audio laden")
            }
        }
    }
}

// --------------------------------------------- Qualität + Speicherort wählen

@Composable
fun DetailsSheet(vm: MainViewModel, d: VideoDetails, onDownload: (Option, Boolean) -> Unit) {
    val ctxHit = vm.seriesContext
    var keep by remember { mutableStateOf(ctxHit?.verified == true) }
    var tab by remember { mutableIntStateOf(if (vm.startAudio) 1 else 0) }
    var selected by remember { mutableStateOf<Option?>(null) }
    val pickFolder = rememberFolderPicker(vm)

    val videos = d.options.filter { it.kind == Kind.VIDEO }
    val audios = d.options.filter { it.kind == Kind.AUDIO }
    val shown = if (tab == 0) videos else audios

    ModalBottomSheet(
        onDismissRequest = { vm.closeDetails() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                    AsyncImage(
                        model = d.thumbnail, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        d.uploader, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.padding(top = 10.dp))
            Button(
                onClick = { selected?.let { onDownload(it, keep) } },
                enabled = selected != null && vm.treeUri != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        selected == null -> "Qualität wählen"
                        vm.treeUri == null -> "Speicherort wählen"
                        else -> "Download starten"
                    }
                )
            }
            Spacer(Modifier.padding(top = 12.dp))

            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(
                    selected = tab == 0, onClick = { tab = 0; selected = null },
                    text = { Text("Video (${videos.size})") }
                )
                Tab(
                    selected = tab == 1, onClick = { tab = 1; selected = null },
                    text = { Text("Nur Audio (${audios.size})") }
                )
            }

            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                if (shown.isEmpty()) {
                    item { Text("Keine Formate verfügbar.", Modifier.padding(16.dp)) }
                }
                itemsIndexed(shown) { i, o ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = o }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == o, onClick = { selected = o })
                        Column(Modifier.weight(1f)) {
                            Text(o.label)
                            if (i == 0) {
                                Text(
                                    if (tab == 1) "Empfohlen · beste Tonqualität" else "Höchste Auflösung",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text("Speicherort", style = MaterialTheme.typography.labelSmall)
                        Text(prettyPath(vm.treeUri), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = pickFolder) {
                        Text(if (vm.treeUri == null) "Wählen" else "Ändern")
                    }
                }
            }

            if (ctxHit != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { keep = !keep }.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(checked = keep, onCheckedChange = { keep = it })
                    Column {
                        Text("Serie merken", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Neue Folgen von „${ctxHit.channelName.ifEmpty { ctxHit.name }}“ automatisch in dieser Qualität laden" +
                                if (!ctxHit.verified) " (Kanal nicht verifiziert)" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

        }
    }
}
