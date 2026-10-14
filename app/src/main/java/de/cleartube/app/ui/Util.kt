package de.cleartube.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    return ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
        ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
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

/** Liefert eine Funktion, die den Ordner-Auswahldialog öffnet und die Wahl dauerhaft speichert. */
@Composable
fun rememberFolderPicker(vm: MainViewModel): () -> Unit {
    val ctx = LocalContext.current
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
    return {
        val hasPicker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(ctx.packageManager) != null
        if (hasPicker) {
            launcher.launch(vm.treeUri?.takeIf { it.scheme != "file" })
        } else {
            // Viele Fernseher haben keine Ordner-Auswahl: dann in den App-Ordner speichern
            vm.setTree(appFolder(ctx))
            Toast.makeText(ctx, "Keine Ordner-Auswahl vorhanden – Speicherort ist der App-Ordner", Toast.LENGTH_LONG).show()
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
