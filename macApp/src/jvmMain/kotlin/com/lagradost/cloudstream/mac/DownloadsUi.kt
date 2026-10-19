package com.lagradost.cloudstream.mac

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists every source and quality for [item] and downloads the one picked. Pass [links] when they're already
 * loaded (the player); otherwise they're fetched from [api] like the player does.
 */
@Composable
fun DownloadPicker(
    api: MainAPI?, item: PlayItem, onDismiss: () -> Unit,
    links: List<ExtractorLink>? = null, subtitles: List<SubtitleFile> = emptyList(),
) {
    val options = remember(item) { mutableStateListOf<DownloadSource>() }
    val subs = remember(item) { mutableStateListOf<SubtitleFile>().apply { addAll(subtitles) } }
    var loading by remember(item) { mutableStateOf(true) }
    var error by remember(item) { mutableStateOf<String?>(null) }

    LaunchedEffect(item) {
        withContext(Dispatchers.IO) {
            runCatching {
                val found = links ?: mutableListOf<ExtractorLink>().also { l ->
                    api?.loadLinks(item.data, false, { subs.add(it) }, { l.add(it) })
                }
                // HLS playlists are opened to list each quality inside them
                sortLinks(found, Int.MAX_VALUE).forEach { link -> options.addAll(downloadOptions(link).filter { o -> options.none { it.url == o.url } }) }
            }.onFailure { error = it.message ?: it.toString() }
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download “${item.title}”") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                if (loading) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Finding sources… (${options.size})")
                }
                error?.let { Text("Couldn't load sources: $it", color = MaterialTheme.colorScheme.error) }
                if (!loading && options.isEmpty() && error == null) Text("No downloadable sources found.")
                LazyColumn {
                    items(options) { option ->
                        ListItem(
                            headlineContent = { Text(if (option.quality > 0) "${option.quality}p" else "Auto quality") },
                            supportingContent = {
                                Text(listOfNotNull(option.label, option.type.name, "with audio track".takeIf { option.audioUrl != null }).joinToString(" · "))
                            },
                            trailingContent = {
                                Button(onClick = {
                                    Downloads.enqueue(item.title, api?.name ?: "Downloads", option, subs.toList())
                                    Toasts.show("Downloading “${item.title}” to ${Downloads.folder.path.replace(System.getProperty("user.home"), "~")}")
                                    onDismiss()
                                }) { Text("Download") }
                            },
                            modifier = Modifier.testTag("download-option"),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun DownloadsScreen(play: (PlayItem) -> Unit) {
    val items = Downloads.items
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Downloads", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = { Downloads.folder.mkdirs(); ProcessBuilder("open", Downloads.folder.path).start() }) {
            Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(4.dp)); Text("Open folder")
        }
    }
    Text(Downloads.folder.path.replace(System.getProperty("user.home"), "~"), style = MaterialTheme.typography.bodySmall)
    if (items.isEmpty()) Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Download, null, tint = Color(0xFF777777), modifier = Modifier.size(56.dp))
        Text("Nothing downloaded yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text("Use Download on a movie, an episode, or in the player's ⋮ menu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(Modifier.padding(top = 8.dp)) {
        items(items.toList(), key = { it.id }) { d ->
            val file = File(d.file)
            ListItem(
                headlineContent = { Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        val size = if (file.exists()) " · ${"%.1f".format(file.length() / 1_048_576.0)} MB" else ""
                        val quality = if (d.source.quality > 0) " · ${d.source.quality}p" else ""
                        val state = when (d.status) {
                            DownloadStatus.Downloading -> "Downloading ${(d.progress * 100).toInt()}%"
                            DownloadStatus.Failed -> "Failed: ${d.error}"
                            else -> d.status.name
                        }
                        Text("$state · ${d.apiName}$quality$size", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (d.status == DownloadStatus.Downloading) LinearProgressIndicator({ d.progress }, Modifier.fillMaxWidth().padding(top = 4.dp), color = NetflixRed)
                    }
                },
                trailingContent = {
                    Row {
                        when (d.status) {
                            DownloadStatus.Done -> {
                                Button(onClick = { play(PlayItem(d.title, d.file, file = d.file, subtitleFiles = d.subtitles)) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play")
                                }
                                TextButton(onClick = { ProcessBuilder("open", "-R", d.file).start() }) {
                                    Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(4.dp)); Text("Show in Finder")
                                }
                            }
                            DownloadStatus.Downloading, DownloadStatus.Queued -> TextButton(onClick = { Downloads.cancel(d.id) }) {
                                Icon(Icons.Filled.Close, null); Spacer(Modifier.width(4.dp)); Text("Cancel")
                            }
                            DownloadStatus.Failed, DownloadStatus.Cancelled -> TextButton(onClick = { Downloads.retry(d.id) }) {
                                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Retry")
                            }
                        }
                        IconButton(onClick = { Downloads.delete(d.id) }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                },
                modifier = Modifier.testTag("download-${d.status}"),
            )
        }
    }
}
