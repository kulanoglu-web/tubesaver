package de.cleartube.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object AppInfo {
    const val DESCRIPTION =
        "ClearTube ist ein werbefreier Downloader für YouTube. Du suchst direkt in der App oder fügst " +
            "einen Link ein, wählst vor dem Download Qualität und Speicherort und speicherst das Video " +
            "als MP4/WEBM oder nur den Ton als M4A in hoher Qualität. Geladen wird über mehrere " +
            "Verbindungen und, soweit möglich, von mehreren YouTube-Servern gleichzeitig."

    val CHANGES = listOf(
        "Tippen auf die Download-Benachrichtigung öffnet die App",
        "Schnelleres Zusammenfügen, Anzeige \"Wird im Zielordner gespeichert\"",
        "Version 1.3: Fehler 403 beim Download behoben – Android-VR-Quelle zuerst, neue Adressen bei Fehlern, " +
            "Schonmodus, \"Erneut versuchen\" im Menü, genauere Fehlermeldung",
        "Neues Design mit hellem und dunklem Modus",
        "Downloadliste und letzte Suche bleiben nach dem Neustart erhalten",
        "Audio in bester Qualität (M4A) mit einem Tipp aus den Suchergebnissen",
        "Einstellungen: Speicherort jederzeit änderbar",
        "Aufräum-Vorschlag für große Dateien (ab 150 MB) nach einstellbarer Zeit",
        "Downloads abspielen, aus der Liste entfernen oder die Datei löschen"
    )

    const val NOTE =
        "Bitte nur eigene oder frei lizenzierte Inhalte laden. Das Herunterladen kann gegen die " +
            "YouTube-Nutzungsbedingungen und Urheberrechte verstoßen."
}

@Composable
fun InfoDialog(onDismiss: () -> Unit) {
    val version = rememberVersionText()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("ClearTube") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(version, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(AppInfo.DESCRIPTION, style = MaterialTheme.typography.bodyMedium)
                Text("Neu in dieser Version", style = MaterialTheme.typography.titleSmall)
                AppInfo.CHANGES.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    AppInfo.NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}
