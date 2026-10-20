package com.lagradost.cloudstream.mac

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Apple TV look: San Francisco with size-specific tracking, critically damped springs for
 * hover and press, white capsule primary buttons, translucent circular secondary buttons, and
 * large titles. Shared by every screen so they feel like one app.
 */

/** San Francisco, the macOS system font, in the weights the app uses. Other systems use their default font. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val SystemFontFamily: FontFamily =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        FontFamily(
            SystemFont(".AppleSystemUIFont", FontWeight.Normal),
            SystemFont(".AppleSystemUIFont", FontWeight.Medium),
            SystemFont(".AppleSystemUIFont", FontWeight.SemiBold),
            SystemFont(".AppleSystemUIFont", FontWeight.Bold),
            SystemFont(".AppleSystemUIFont", FontWeight.ExtraBold),
        )
    } else {
        FontFamily.Default
    }

private fun style(size: Int, weight: FontWeight, tracking: Double, lineHeight: Int) = TextStyle(
    fontFamily = SystemFontFamily,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeight = lineHeight.sp,
)

/** Apple's text styles: large text gets negative tracking and tight leading, small text a little positive tracking. */
val TvTypography = Typography(
    displayLarge = style(52, FontWeight.Bold, -1.2, 56),
    displayMedium = style(44, FontWeight.Bold, -0.9, 48),
    displaySmall = style(38, FontWeight.Bold, -0.7, 42),
    headlineLarge = style(34, FontWeight.Bold, -0.6, 40),
    headlineMedium = style(28, FontWeight.Bold, -0.4, 34),
    headlineSmall = style(24, FontWeight.Bold, -0.3, 30),
    titleLarge = style(22, FontWeight.SemiBold, -0.2, 28),
    titleMedium = style(17, FontWeight.SemiBold, -0.2, 22),
    titleSmall = style(15, FontWeight.SemiBold, -0.1, 20),
    bodyLarge = style(17, FontWeight.Normal, -0.2, 24),
    bodyMedium = style(15, FontWeight.Normal, -0.1, 21),
    bodySmall = style(13, FontWeight.Normal, 0.0, 18),
    labelLarge = style(15, FontWeight.SemiBold, -0.1, 20),
    labelMedium = style(13, FontWeight.Medium, 0.0, 17),
    labelSmall = style(11, FontWeight.Medium, 0.1, 14),
)

/** Critically damped, about 0.35 s to settle. No overshoot, as hover and press carry no momentum. */
fun <T> tvSpring() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 320f)

/** Secondary text: labels at 60%, like Apple's secondary label colour. */
val secondaryText: Color @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)

/**
 * The tvOS focus lift on hover: the item grows and casts a deeper shadow, and dips slightly the
 * instant it is pressed, before release.
 */
fun Modifier.tvLift(interaction: MutableInteractionSource, hoverScale: Float = 1.05f, cornerRadius: Dp = 12.dp): Modifier = composed {
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> hoverScale - 0.03f
            hovered -> hoverScale
            else -> 1f
        },
        tvSpring(),
    )
    val elevation by animateFloatAsState(if (hovered) 28f else 0f, tvSpring())
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        shadowElevation = elevation
        shape = RoundedCornerShape(cornerRadius)
        clip = false
    }.hoverable(interaction).handCursor()
}

/** The pointing-hand cursor over anything you can click, as on the web and in native Mac apps. */
fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/** Press feedback for buttons: a small dip on press. */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tvSpring())
    graphicsLayer { scaleX = scale; scaleY = scale }.handCursor()
}

/** Apple's primary action: a white capsule with dark text. */
@Composable
fun PrimaryCapsuleButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .pressScale(interaction)
            .hoverable(interaction)
            .background(Color.White.copy(alpha = if (hovered) 1f else 0.92f), RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.Black, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Color.Black, style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary action over imagery: a translucent capsule, or a circle when there is no text. */
@Composable
fun GlassButton(icon: ImageVector, description: String, text: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = if (text == null) CircleShape else RoundedCornerShape(50)
    Row(
        modifier
            .pressScale(interaction)
            .hoverable(interaction)
            .background(Color.White.copy(alpha = if (hovered) 0.28f else 0.16f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(if (text == null) PaddingValues(11.dp) else PaddingValues(horizontal = 18.dp, vertical = 11.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp))
        if (text != null) {
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The large title at the top of a screen, as in Apple's apps. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.padding(start = 32.dp, end = 24.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Darkens imagery towards the page background, so text over it stays readable. */
fun bottomScrim(background: Color) = Brush.verticalGradient(
    0f to Color.Transparent,
    0.55f to background.copy(alpha = 0.55f),
    1f to background,
)

fun leftScrim(background: Color) = Brush.horizontalGradient(
    0f to background.copy(alpha = 0.92f),
    0.45f to background.copy(alpha = 0.55f),
    0.75f to Color.Transparent,
)

/** Square-ish spacer used between shelves. */
@Composable
fun ShelfGap() = Spacer(Modifier.height(8.dp))

@Composable
fun Dot(selected: Boolean, onClick: () -> Unit) {
    val width by animateFloatAsState(if (selected) 22f else 7f, tvSpring())
    Box(
        Modifier.height(7.dp).width(width.dp)
            .background(Color.White.copy(alpha = if (selected) 0.95f else 0.4f), RoundedCornerShape(50))
            .handCursor().clickable(onClick = onClick)
    )
}
