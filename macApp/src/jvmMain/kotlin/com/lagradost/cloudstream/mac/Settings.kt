package com.lagradost.cloudstream.mac

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import java.awt.Desktop
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** App settings, saved with the app's other preferences; observable so changes apply straight away. */
object AppSettings {
    private val prefs get() = Preferences.named("cloudstream_mac")
    /** A saved value the UI observes: read once at start, written through on every change. */
    private class Pref<T>(read: () -> T, private val write: (T) -> Unit) : ReadWriteProperty<Any?, T> {
        private var state by mutableStateOf(read())
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { state = value; write(value) }
    }
    private fun <T> pref(read: () -> T, write: (T) -> Unit) = Pref(read, write)

    var themeMode by pref({ runCatching { CloudStreamThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(CloudStreamThemeMode.Dark) },
        { prefs.edit().putString("theme", it.name).apply() })
    var primaryColor by pref({ runCatching { CloudStreamPrimaryColor.valueOf(prefs.getString("accent", null)!!) }.getOrDefault(CloudStreamPrimaryColor.NORMAL) },
        { prefs.edit().putString("accent", it.name).apply() })
    var autoPlayNext by pref({ prefs.getBoolean("autoplay_next", true) }, { prefs.edit().putBoolean("autoplay_next", it).apply() })
    /** Null means ~/Movies/CloudStream. */
    var downloadFolder by pref({ prefs.getString("download_folder", null) }, { prefs.edit().putString("download_folder", it).apply() })
    var parallelDownloads by pref({ prefs.getInt("parallel_downloads", 2) }, { prefs.edit().putInt("parallel_downloads", it).apply() })
    var downloadConnections by pref({ prefs.getInt("download_connections", 4) }, { prefs.edit().putInt("download_connections", it).apply() })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    var confirmClear by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(QualityPreference.max) }
    var subtitleSize by remember { mutableStateOf(SubtitlePreference.size) }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear watch history?") },
        text = { Text("Progress and Up Next are removed. Bookmarks stay.") },
        confirmButton = { TextButton(onClick = { Library.clearHistory(); confirmClear = false }, modifier = Modifier.handCursor()) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.handCursor()) { Text("Cancel") } },
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LargeTitle("Settings")
        Column(Modifier.padding(start = 32.dp, end = 32.dp, bottom = 32.dp).widthIn(max = 760.dp)) {
            Section("Appearance")
            Label("Theme")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudStreamThemeMode.entries.filter { it != CloudStreamThemeMode.Dynamic }.forEach { mode ->
                    FilterChip(AppSettings.themeMode == mode, { AppSettings.themeMode = mode },
                        label = { Text(if (mode == CloudStreamThemeMode.FollowSystem) "System" else mode.name) }, modifier = Modifier.handCursor())
                }
            }
            Label("Accent color")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CloudStreamPrimaryColor.entries.forEach { color ->
                    Box(Modifier.size(32.dp).background(color.color, CircleShape)
                        .border(if (AppSettings.primaryColor == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                        .handCursor().clickable { AppSettings.primaryColor = color })
                }
            }

            Section("Player")
            Toggle("Play next episode automatically", AppSettings.autoPlayNext) { AppSettings.autoPlayNext = it }
            Label("Preferred quality")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityPreference.choices.forEach { (q, label) ->
                    FilterChip(quality == q, { quality = q; QualityPreference.max = q }, label = { Text(label) }, modifier = Modifier.handCursor())
                }
            }
            Hint("Auto follows your connection on adaptive streams and picks the best source. The player's ⋮ menu changes it too.")
            Label("Subtitle size")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(18 to "Small", 24 to "Medium", 32 to "Large", 40 to "Extra large").forEach { (size, label) ->
                    FilterChip(subtitleSize == size, { subtitleSize = size; SubtitlePreference.size = size }, label = { Text(label) }, modifier = Modifier.handCursor())
                }
            }

            Section("Downloads")
            Text(Downloads.folder.path.replace(System.getProperty("user.home"), "~"), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { chooseFolder()?.let { AppSettings.downloadFolder = it } }, modifier = Modifier.handCursor()) { Text("Change folder") }
                if (AppSettings.downloadFolder != null) OutlinedButton(onClick = { AppSettings.downloadFolder = null }, modifier = Modifier.handCursor()) { Text("Use $videosFolderName folder") }
            }
            Hint("Finished downloads stay where they are when you change the folder.")
            Label("Downloads at the same time")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 4).forEach { n ->
                    FilterChip(AppSettings.parallelDownloads == n, { AppSettings.parallelDownloads = n; Downloads.limitChanged() }, label = { Text("$n") }, modifier = Modifier.handCursor())
                }
            }
            Hint("Other downloads wait and start one by one as these finish.")
            Label("Connections per download")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 4, 8, 16).forEach { n ->
                    FilterChip(AppSettings.downloadConnections == n, { AppSettings.downloadConnections = n }, label = { Text(if (n == 1) "1 (off)" else "$n") }, modifier = Modifier.handCursor())
                }
            }
            Hint("Fetches a file in parts over several connections at once. Some hosts limit connections, so lower this if downloads fail.")

            Section("Data")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(Extensions.dataDir) } }, modifier = Modifier.handCursor()) { Text("Open data folder") }
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.handCursor()) { Text("Clear watch history") }
            }
            Hint(Extensions.dataDir.absolutePath)

            Section("About")
            Text("CloudStream for Mac, built on CloudStream's cross-platform library. Extensions are third-party and CloudStream " +
                "does not host any content. Licensed under GPL-3.0.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Hint("Java ${System.getProperty("java.version")} · ${System.getProperty("os.name")} ${System.getProperty("os.arch")} · " +
                "external player: ${Player.available ?: "none"}")
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.size(24.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().handCursor().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.handCursor())
    }
}

/** The native macOS folder picker. Returns null when cancelled. */
private fun chooseFolder(): String? {
    // Windows' native file dialog can't pick folders; Swing's chooser can
    if (isWindows) return javax.swing.JFileChooser(Downloads.folder).run {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose a download folder"
        if (showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) selectedFile.path else null
    }
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    return try {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a download folder", java.awt.FileDialog.LOAD)
        dialog.directory = Downloads.folder.path
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it).path }
    } finally {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}
