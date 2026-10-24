package de.cleartube.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import de.cleartube.app.data.DownloadJob
import de.cleartube.app.download.Cleanup

fun formatDuration(s: Long): String? {
    if (s <= 0) return null
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatViews(v: Long): String = when {
    v >= 1_000_000 -> "%.1f Mio.".format(v / 1_000_000.0)
    v >= 1_000 -> "%.1f Tsd.".format(v / 1_000.0)
    else -> v.toString()
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.0f MB".format(b / 1048576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

/** Macht aus einer Ordner-Adresse einen lesbaren Pfad, z. B. "Interner Speicher/Download/ClearTube". */
fun prettyPath(uri: Uri?): String {
    if (uri == null) return "Noch nicht gewählt"
    if (uri.scheme == "file") return "App-Ordner (${uri.path})"
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
    val parts = id.split(":", limit = 2)
    val volume = if (parts[0] == "primary") "Interner Speicher" else "SD-Karte/USB"
    val path = parts.getOrElse(1) { "" }
    return if (path.isEmpty()) volume else "$volume/$path"
}

/** Läuft die App auf einem Fernseher (Android TV)? */
fun isTelevision(ctx: Context): Boolean {
    val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
    val pm = ctx.packageManager
    return ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
        !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
}

/** Eigener Ordner der App; braucht keine Berechtigung und funktioniert auch ohne Ordner-Auswahl. */
fun appFolder(ctx: Context): Uri {
    val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
    dir.mkdirs()
    return Uri.fromFile(dir)
}

@Composable
fun rememberVersionText(): String {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "Version ${pi.versionName} (Build ${PackageInfoCompat.getLongVersionCode(pi)})"
        }.getOrDefault("")
    }
}

/** Speicher laut System (zur Fehlersuche, wenn eine externe Festplatte nicht auftaucht). */
fun storageInfo(ctx: Context): String = runCatching {
    val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java)
    sm.storageVolumes.joinToString("\n") { v ->
        "${v.getDescription(ctx)}: ${v.state}" + if (v.isPrimary) " (intern)" else if (v.isRemovable) " (wechselbar)" else ""
    }
}.getOrDefault("")

/** Fehlersuche: was /storage enthält und ob dort geschrieben werden darf. */
fun storageDiagnostics(ctx: Context): String = runCatching {
    val sb = StringBuilder()
    val root = java.io.File("/storage")
    val list = root.list()
    sb.append("/storage: ").append(list?.joinToString(", ") ?: "nicht lesbar").append('\n')
    list?.filter { it != "emulated" && it != "self" }?.forEach { n ->
        val d = java.io.File(root, "$n/Android/data/${ctx.packageName}/files/Movies")
        val made = runCatching { d.mkdirs() }.getOrDefault(false)
        sb.append(n).append(": anlegen=").append(made || d.isDirectory).append(", schreibbar=").append(d.canWrite()).append('\n')
    }
    ctx.getExternalFilesDirs(null).forEachIndexed { i, f -> sb.append("ExtDir[$i]=").append(f?.absolutePath ?: "null").append('\n') }
    sb.toString()
}.getOrDefault("")

/**
 * Ziele ohne Ordner-Auswahl: der App-Ordner auf dem internen Speicher und auf jedem eingehängten
 * externen Speicher (USB-Festplatte, USB-Stick, SD-Karte). Brauchen keine Berechtigung.
 */
fun appFolders(ctx: Context): List<Pair<String, Uri>> {
    val found = LinkedHashMap<String, java.io.File>()
    ctx.getExternalFilesDirs(android.os.Environment.DIRECTORY_MOVIES).filterNotNull().forEach {
        found[it.absolutePath] = it
    }
    // Zusätzlich alle eingehängten Speicher abfragen; nicht jedes Gerät meldet USB-Festplatten über den ersten Weg
    runCatching {
        val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java)
        sm.storageVolumes.forEach { v ->
            if (v.state != android.os.Environment.MEDIA_MOUNTED || v.isPrimary) return@forEach
            val root: java.io.File? = if (android.os.Build.VERSION.SDK_INT >= 30) {
                v.directory
            } else {
                runCatching { (v.javaClass.getMethod("getPath").invoke(v) as String).let { java.io.File(it) } }.getOrNull()
            }
            if (root != null) {
                val d = java.io.File(root, "Android/data/${ctx.packageName}/files/Movies")
                found.putIfAbsent(d.absolutePath, d)
            }
        }
    }
    // Dritter Weg: /storage direkt durchsehen (USB-Festplatten erscheinen dort als /storage/XXXX-XXXX)
    runCatching {
        java.io.File("/storage").listFiles()?.forEach { vol ->
            if (vol.name == "emulated" || vol.name == "self" || !vol.isDirectory) return@forEach
            val d = java.io.File(vol, "Android/data/${ctx.packageName}/files/Movies")
            found.putIfAbsent(d.absolutePath, d)
        }
    }
    val internalRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
    val dirs = found.values.filter { d ->
        runCatching { d.mkdirs() }
        d.isDirectory && d.canWrite()
    }
    if (dirs.isEmpty()) return listOf("Interner Speicher" to Uri.fromFile(ctx.filesDir))
    return dirs.map { d ->
        val internal = d.absolutePath.startsWith(internalRoot)
        val name = if (internal) "Interner Speicher" else "Externer Speicher (" + d.absolutePath.split('/').getOrElse(2) { "USB" } + ")"
        "$name · ${formatSize(d.freeSpace)} frei" to Uri.fromFile(d)
    }
}

/**
 * Liefert eine Funktion, die die Ordner-Auswahl öffnet und die Wahl dauerhaft speichert.
 * Auf Fernsehern (ohne Ordner-Auswahl des Systems) erscheint stattdessen eine Liste der Speicher.
 */
@Composable
fun rememberFolderPicker(vm: MainViewModel): () -> Unit {
    val ctx = LocalContext.current
    var showList by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.setTree(uri)
        }
    }
    if (showList) {
        val options = remember { appFolders(ctx) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showList = false },
            title = { Text("Speicherort wählen") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        "Dieses Gerät hat keine Ordner-Auswahl. Die Downloads werden im App-Ordner des gewählten Speichers abgelegt.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                    if (options.size < 2) {
                        Text(
                            "Keine externe Festplatte gefunden. Laut System sind diese Speicher vorhanden:\n" +
                                storageInfo(ctx).ifEmpty { "(keine Angabe)" } + "\n" + storageDiagnostics(ctx) +
                                "\nTipp: Die Festplatte muss eingehängt sein; manche Fernseher binden sie nur nach " +
                                "Formatieren als Gerätespeicher ein.",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                        )
                    }
                    options.forEach { (label, uri) ->
                        androidx.compose.material3.TextButton(
                            onClick = { vm.setTree(uri); showList = false },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                        ) {
                            androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                                Text(label)
                                Text(
                                    uri.path.orEmpty(),
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showList = false }) { Text("Abbrechen") } }
        )
    }
    return {
        val hasPicker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(ctx.packageManager) != null
        if (isTelevision(ctx) || !hasPicker) {
            showList = true
        } else {
            try {
                launcher.launch(vm.treeUri?.takeIf { it.scheme != "file" })
            } catch (e: Exception) {
                showList = true // kein Ordner-Auswahldialog vorhanden
            }
        }
    }
}

/**
 * Spielt die fertige Datei ab. engine: "vlc", "exo", "extern" oder null (Wahl aus den Einstellungen).
 */
fun openFile(ctx: Context, j: DownloadJob, engine: String? = null) {
    val uri = j.resultUri ?: return
    if (!Cleanup.exists(ctx, uri)) {
        Toast.makeText(ctx, "Die Datei ist nicht mehr vorhanden", Toast.LENGTH_LONG).show()
        return
    }
    val choice = engine ?: de.cleartube.app.data.Store.player
    if (choice == "vlc" || choice == "exo") {
        de.cleartube.app.player.PlayerActivity.start(ctx, uri, j.id, j.title, choice)
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, j.openMime ?: "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(ctx, "Keine App zum Abspielen gefunden", Toast.LENGTH_LONG).show()
    }
}
