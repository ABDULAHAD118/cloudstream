package com.lagradost.cloudstream.mac

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay

private val TrackGrey = Color.White.copy(alpha = 0.3f)

/**
 * Netflix-style seek bar: a thin grey track with a red played part. Hovering thickens it, shows the red dot and a
 * time preview at the pointer; click or drag to seek. [lengthMs] of 0 means unknown (live), which disables seeking.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeekBar(positionMs: Long, lengthMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1) }
    val active = hovered || dragging != null
    val trackHeight by animateDpAsState(if (active) 6.dp else 3.dp)
    val thumb by animateDpAsState(if (active) 16.dp else 0.dp)
    val fraction = dragging ?: if (lengthMs > 0) (positionMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
    fun at(x: Float) = (x / widthPx).coerceIn(0f, 1f)

    Box(
        modifier.fillMaxWidth().height(28.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .hoverable(hover)
            .onPointerEvent(PointerEventType.Move) { hoverX = it.changes.first().position.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
            .pointerInput(lengthMs) {
                if (lengthMs > 0) detectTapGestures { onSeek((at(it.x) * lengthMs).toLong()) }
            }
            .pointerInput(lengthMs) {
                if (lengthMs > 0) detectDragGestures(
                    onDragStart = { dragging = at(it.x) },
                    onDragEnd = { dragging?.let { onSeek((it * lengthMs).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = at(change.position.x) }
            }
            .semantics {
                contentDescription = "Seek"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            }
            .testTag("seek-bar"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val h = trackHeight.toPx()
            val y = size.height / 2 - h / 2
            val r = CornerRadius(h / 2)
            drawRoundRect(TrackGrey, Offset(0f, y), Size(size.width, h), r)
            // Where the pointer is, a lighter band previews the jump, as Netflix does
            hoverX?.takeIf { lengthMs > 0 }?.let { drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(it.coerceIn(0f, size.width), h), r) }
            drawRoundRect(NetflixRed, Offset(0f, y), Size(size.width * fraction, h), r)
            if (thumb > 0.dp) drawCircle(NetflixRed, thumb.toPx() / 2, Offset(size.width * fraction, size.height / 2))
        }
        // Time preview above the pointer
        val density = LocalDensity.current
        hoverX?.takeIf { lengthMs > 0 && active }?.let { x ->
            Popup(offset = IntOffset((x - with(density) { 26.dp.toPx() }).toInt(), with(density) { (-34).dp.roundToPx() })) {
                Text(formatTime((at(x) * lengthMs).toLong()), color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

/**
 * Netflix-style volume: the speaker icon mutes; hovering it opens a slim vertical bar above it. Volume runs to
 * 200 (libVLC's boost); the bar's top is 100 %, and the boost stays on the keyboard (↑).
 */
@Composable
fun VolumeControl(volume: Int, onVolume: (Int) -> Unit) {
    val iconHover = remember { MutableInteractionSource() }
    val popupHover = remember { MutableInteractionSource() }
    val overIcon by iconHover.collectIsHoveredAsState()
    val overPopup by popupHover.collectIsHoveredAsState()
    var open by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(overIcon, overPopup, dragging) {
        if (overIcon || overPopup || dragging) open = true
        else { delay(350); open = false } // a short grace period to move from the icon to the bar
    }
    val density = LocalDensity.current
    Box {
        IconButton(onClick = { onVolume(if (volume == 0) 100 else 0) }, modifier = Modifier.hoverable(iconHover)) {
            Icon(
                when { volume == 0 -> Icons.AutoMirrored.Filled.VolumeOff; volume < 50 -> Icons.AutoMirrored.Filled.VolumeDown; else -> Icons.AutoMirrored.Filled.VolumeUp },
                "Volume", tint = Color.White,
            )
        }
        if (open) Popup(offset = IntOffset(0, with(density) { (-150).dp.roundToPx() })) {
            var heightPx by remember { mutableStateOf(1) }
            fun level(y: Float) = ((1f - y / heightPx) * 100).toInt().coerceIn(0, 100)
            Box(
                Modifier.size(48.dp, 146.dp).hoverable(popupHover)
                    .background(Color(0xFF262626), RoundedCornerShape(6.dp)).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width(28.dp).fillMaxHeight().onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                        .pointerInput(Unit) { detectTapGestures { onVolume(level(it.y)) } }
                        .pointerInput(Unit) {
                            detectDragGestures(onDragStart = { dragging = true; onVolume(level(it.y)) }, onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false }) { change, _ -> onVolume(level(change.position.y)) }
                        }
                        .testTag("volume-bar"),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = 4.dp.toPx()
                        val x = size.width / 2 - w / 2
                        val filled = size.height * (volume.coerceAtMost(100) / 100f)
                        drawRoundRect(TrackGrey, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
                        drawRoundRect(NetflixRed, Offset(x, size.height - filled), Size(w, filled), CornerRadius(w / 2))
                        drawCircle(Color.White, 7.dp.toPx(), Offset(size.width / 2, size.height - filled))
                    }
                }
            }
        }
    }
}
