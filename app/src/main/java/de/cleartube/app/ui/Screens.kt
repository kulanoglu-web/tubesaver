package de.cleartube.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.pm.PackageInfoCompat
import coil.compose.AsyncImage
import de.cleartube.app.data.DownloadJob
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Kind
import de.cleartube.app.data.Option
import de.cleartube.app.data.VideoDetails
import de.cleartube.app.data.VideoItem
import de.cleartube.app.download.DownloadHub

@Composable
fun ClearTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val jobs by DownloadHub.jobs.collectAsState()
    val active = jobs.count { it.state == JobState.QUEUED || it.state == JobState.RUNNING || it.state == JobState.MUXING }

    // Zurück: Downloads -> Suche, Ergebnisliste -> leere Suche, erst dann die App verlassen
    BackHandler(enabled = tab == 1) { tab = 0 }
    BackHandler(enabled = tab == 0 && vm.details == null && (vm.results.isNotEmpty() || vm.error != null)) {
        vm.clearResults()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Search, null) }, label = { Text("Suche") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Download, null) },
                    label = { Text(if (active > 0) "Downloads ($active)" else "Downloads") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (tab == 0) SearchScreen(vm) else DownloadsScreen(jobs)
            if (vm.detailsLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    vm.details?.let { d ->
        DetailsSheet(vm, d, onDownload = { vm.download(it); tab = 1 })
    }
}

// ------------------------------------------------------------------- Suche

@Composable
fun SearchScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            singleLine = true,
            placeholder = { Text("YouTube durchsuchen oder Link einfügen") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Clear, "Leeren") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        val ctx = LocalContext.current
        val versionText = remember {
            runCatching {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                "ClearTube Version ${pi.versionName} (Build ${PackageInfoCompat.getLongVersionCode(pi)})"
            }.getOrDefault("")
        }
        Text(
            versionText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (vm.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.results.size) { i ->
                VideoRow(vm.results[i]) { vm.open(vm.results[i]) }
                if (i == vm.results.lastIndex) LaunchedEffect(vm.results.size) { vm.loadMore() }
            }
        }
    }
}

@Composable
fun VideoRow(item: VideoItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = item.thumbnail, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(top = 4.dp))
            Text(item.uploader, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(formatDuration(item.durationSec), if (item.views >= 0) "${formatViews(item.views)} Aufrufe" else null)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDuration(s: Long): String? {
    if (s <= 0) return null
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun formatViews(v: Long): String = when {
    v >= 1_000_000 -> "%.1f Mio.".format(v / 1_000_000.0)
    v >= 1_000 -> "%.1f Tsd.".format(v / 1_000.0)
    else -> v.toString()
}

// --------------------------------------------- Qualität + Speicherort wählen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(vm: MainViewModel, d: VideoDetails, onDownload: (Option) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Option?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.setTree(uri)
        }
    }

    val videos = d.options.filter { it.kind == Kind.VIDEO }
    val audios = d.options.filter { it.kind == Kind.AUDIO }
    val shown = if (tab == 0) videos else audios

    ModalBottomSheet(onDismissRequest = { vm.closeDetails() }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(d.uploader, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.padding(top = 8.dp))

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0; selected = null }, text = { Text("Video") })
                Tab(selected = tab == 1, onClick = { tab = 1; selected = null }, text = { Text("Audio") })
            }

            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (shown.isEmpty()) {
                    item { Text("Keine Formate verfügbar.", Modifier.padding(16.dp)) }
                }
                items(shown) { o ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = o }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == o, onClick = { selected = o })
                        Text(o.label)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Speicherort", style = MaterialTheme.typography.labelSmall)
                    Text(
                        vm.treeUri?.let { Uri.decode(it.lastPathSegment ?: it.toString()) }
                            ?: "Noch nicht gewählt",
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { picker.launch(vm.treeUri) }) {
                    Text(if (vm.treeUri == null) "Wählen" else "Ändern")
                }
            }

            Spacer(Modifier.padding(top = 8.dp))
            Button(
                onClick = { selected?.let(onDownload) },
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
        }
    }
}

// ---------------------------------------------------------------- Downloads

@Composable
fun DownloadsScreen(jobs: List<DownloadJob>) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Downloads", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { DownloadHub.clearFinished() }) { Text("Liste leeren") }
        }
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Noch keine Downloads") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(jobs.reversed(), key = { it.id }) { j -> JobRow(j) }
        }
    }
}

/** Öffnet die fertige Datei im Player des Handys. */
private fun openFile(ctx: android.content.Context, j: DownloadJob) {
    val uri = j.resultUri ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(uri, j.openMime ?: "video/*")
        .addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        )
    try {
        ctx.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Keine App zum Abspielen gefunden", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
fun JobRow(j: DownloadJob) {
    val ctx = LocalContext.current
    val finished = j.state == JobState.DONE || j.state == JobState.FAILED || j.state == JobState.CANCELLED
    val canOpen = j.state == JobState.DONE && j.resultUri != null
    Row(
        Modifier.fillMaxWidth()
            .then(if (canOpen) Modifier.clickable { openFile(ctx, j) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(j.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(j.option.label, style = MaterialTheme.typography.bodySmall)
            if (!finished) {
                Spacer(Modifier.padding(top = 4.dp))
                LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                when (j.state) {
                    JobState.QUEUED -> "Wartet…"
                    JobState.RUNNING ->
                        "Lädt… ${(j.progress * 100).toInt()} %" + (j.message?.let { " · $it parallel" } ?: "")
                    JobState.MUXING -> "Wird zusammengefügt…"
                    JobState.DONE -> if (canOpen) "Fertig – zum Abspielen antippen" else "Fertig"
                    JobState.FAILED -> "Fehler: ${j.message ?: "unbekannt"}"
                    JobState.CANCELLED -> "Abgebrochen"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (j.state == JobState.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { if (finished) DownloadHub.remove(j.id) else DownloadHub.cancel(j.id) }) {
            Icon(if (finished) Icons.Default.Delete else Icons.Default.Close, if (finished) "Entfernen" else "Abbrechen")
        }
    }
}
