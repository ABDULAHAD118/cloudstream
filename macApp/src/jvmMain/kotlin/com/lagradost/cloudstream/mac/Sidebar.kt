package com.lagradost.cloudstream.mac

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The Apple TV app's sidebar: the app mark, a search entry, the sections, slightly lighter than the content. */
@Composable
fun Sidebar(tab: Screen, onTab: (Screen) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val active = Downloads.items.count { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
    Column(
        Modifier.width(232.dp).fillMaxHeight().background(colors.onBackground.copy(alpha = 0.05f).compositeOver(colors.background))
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, bottom = 18.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Cloud, null, tint = colors.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("CloudStream", style = MaterialTheme.typography.titleMedium)
        }
        SidebarItem(Icons.Outlined.Search, "Search", tab == Screen.Search) { onTab(Screen.Search) }
        Spacer(Modifier.height(14.dp))
        SidebarItem(Icons.Outlined.Home, "Home", tab == Screen.Home) { onTab(Screen.Home) }
        SidebarItem(Icons.Outlined.VideoLibrary, "Library", tab == Screen.LibraryTab) { onTab(Screen.LibraryTab) }
        SidebarItem(Icons.Outlined.Download, "Downloads", tab == Screen.DownloadsTab, active.takeIf { it > 0 }) { onTab(Screen.DownloadsTab) }
        SidebarItem(Icons.Outlined.Extension, "Extensions", tab == Screen.ExtensionsTab) { onTab(Screen.ExtensionsTab) }
        Spacer(Modifier.weight(1f))
        SidebarItem(Icons.Outlined.Settings, "Settings", tab == Screen.SettingsTab) { onTab(Screen.SettingsTab) }
    }
}

@Composable
private fun SidebarItem(icon: ImageVector, label: String, selected: Boolean, badge: Int? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).pressScale(interaction).clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> colors.onBackground.copy(alpha = 0.14f)
                    hovered -> colors.onBackground.copy(alpha = 0.06f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction).handCursor().clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) colors.primary else colors.onBackground.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (badge != null) Text(
            "$badge", style = MaterialTheme.typography.labelSmall, color = colors.onPrimary,
            modifier = Modifier.background(colors.primary, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}
