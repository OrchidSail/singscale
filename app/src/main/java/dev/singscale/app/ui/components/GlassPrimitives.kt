package dev.singscale.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.CyanPrimary
import dev.singscale.app.ui.theme.EmeraldGood
import dev.singscale.app.ui.theme.GlassRimBottom
import dev.singscale.app.ui.theme.GlassRimMid
import dev.singscale.app.ui.theme.GlassRimTop
import dev.singscale.app.ui.theme.IndigoAccent
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.ObsidianCard
import dev.singscale.app.ui.theme.ObsidianCardBottom
import dev.singscale.app.ui.theme.ObsidianElevated
import dev.singscale.app.ui.theme.TextMuted
import dev.singscale.app.ui.theme.TextPrimary
import dev.singscale.app.ui.theme.TextSecondary

/**
 * Ambient atmospheric Aurora mesh backdrop rendered at the top of screens.
 */
@Composable
fun AuroraMeshBackdrop(
    primaryColor: Color,
    secondaryColor: Color = IndigoAccent,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val animPrimary by animateColorAsState(
        targetValue = primaryColor,
        animationSpec = tween(700),
        label = "auroraPrimary"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val primaryAlpha = if (active) 0.15f else 0.08f
        val secondaryAlpha = if (active) 0.09f else 0.05f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animPrimary.copy(alpha = primaryAlpha), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.13f),
                radius = w * 0.88f
            ),
            radius = w * 0.88f,
            center = Offset(w * 0.5f, h * 0.13f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondaryColor.copy(alpha = secondaryAlpha), Color.Transparent),
                center = Offset(w * 0.85f, h * 0.26f),
                radius = w * 0.58f
            ),
            radius = w * 0.58f,
            center = Offset(w * 0.85f, h * 0.26f)
        )
    }
}

/**
 * Top-lit titanium-glass card surface with directional specular rim highlight
 * and optional tactile spring press bounce.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    accentTint: Color? = null,
    highlightBorderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cardPressScale"
    )

    val bgBrush = remember(accentTint) {
        if (accentTint != null) {
            Brush.verticalGradient(
                colors = listOf(
                    accentTint.copy(alpha = 0.09f),
                    ObsidianCard,
                    ObsidianCardBottom
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(ObsidianCard, ObsidianCardBottom)
            )
        }
    }

    val borderBrush = remember(highlightBorderColor, accentTint) {
        when {
            highlightBorderColor != null -> Brush.verticalGradient(
                colors = listOf(
                    highlightBorderColor.copy(alpha = 0.65f),
                    highlightBorderColor.copy(alpha = 0.20f)
                )
            )
            accentTint != null -> Brush.verticalGradient(
                colors = listOf(
                    accentTint.copy(alpha = 0.36f),
                    GlassRimMid,
                    GlassRimBottom
                )
            )
            else -> Brush.verticalGradient(
                colors = listOf(GlassRimTop, GlassRimMid, GlassRimBottom)
            )
        }
    }

    val clickMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(bgBrush)
            .border(width = 0.9.dp, brush = borderBrush, shape = shape)
            .then(clickMod)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/**
 * Progressive-disclosure accordion card: keeps secondary configurations & logs neatly folded
 * until the user taps to expand, eliminating visual overload.
 */
@Composable
fun ExpandableGlassSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    badgeColor: Color = CyanBright,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chevronRot"
    )

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        highlightBorderColor = if (expanded) iconTint.copy(alpha = 0.45f) else null,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggle()
                    }
                )
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!badgeText.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                MicroPillBadge(text = badgeText, color = badgeColor)
            }

            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = if (expanded) TextPrimary else TextMuted,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                fadeOut(animationSpec = tween(140))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                content()
            }
        }
    }
}

/**
 * Custom geometric SingScale brand emblem.
 */
@Composable
fun SingScaleBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    accent: Color = CyanPrimary
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.22f),
                        IndigoAccent.copy(alpha = 0.18f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.50f), GlassRimBottom)
                ),
                shape = RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.58f)) {
            val w = this.size.width
            val h = this.size.height
            val dotR = w * 0.11f

            drawArc(
                color = accent,
                startAngle = 195f,
                sweepAngle = 150f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = CyanBright.copy(alpha = 0.85f),
                startAngle = 15f,
                sweepAngle = 150f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            drawCircle(color = Color.White, radius = dotR * 1.15f, center = Offset(w * 0.5f, h * 0.5f))
            drawCircle(color = accent, radius = dotR, center = Offset(w * 0.20f, h * 0.76f))
            drawCircle(color = EmeraldGood, radius = dotR, center = Offset(w * 0.80f, h * 0.24f))
        }
    }
}

/**
 * Sleek circular glass icon button used across screen headers.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TextSecondary,
    activeHighlight: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 38.dp
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (activeHighlight) CyanPrimary.copy(alpha = 0.18f) else ObsidianCard)
            .border(
                width = 1.dp,
                color = if (activeHighlight) CyanPrimary.copy(alpha = 0.55f) else ObsidianBorder,
                shape = CircleShape
            )
            .clickable(
                enabled = enabled,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> TextMuted.copy(alpha = 0.5f)
                activeHighlight -> CyanBright
                else -> tint
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Compact flagship search capsule with optional auto-focus when revealed on demand.
 */
@Composable
fun GlassSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onDismissIfEmpty: (() -> Unit)? = null
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(ObsidianElevated.copy(alpha = 0.68f))
            .border(1.dp, ObsidianBorder.copy(alpha = 0.85f), RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = CyanBright.copy(alpha = 0.85f),
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(CyanBright),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }
        if (value.isNotEmpty() || onDismissIfEmpty != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(ObsidianCard)
                    .clickable {
                        if (value.isNotEmpty()) {
                            onValueChange("")
                        } else {
                            onDismissIfEmpty?.invoke()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭搜索",
                    tint = TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/**
 * Sleek glowing capsule pill for filter bars & sort selectors.
 */
@Composable
fun GlassFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = CyanPrimary,
    leadingDotColor: Color? = null
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .height(31.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) accent.copy(alpha = 0.18f)
                else ObsidianCard.copy(alpha = 0.82f)
            )
            .border(
                width = 0.9.dp,
                color = if (selected) accent.copy(alpha = 0.58f) else ObsidianBorder.copy(alpha = 0.72f),
                shape = RoundedCornerShape(999.dp)
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leadingDotColor != null) {
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .clip(CircleShape)
                    .background(leadingDotColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (selected) TextPrimary else TextSecondary,
            maxLines = 1
        )
    }
}

/**
 * Spring-animated sliding segmented control.
 */
@Composable
fun GlassSegmentedControl(
    items: List<Pair<String, ImageVector?>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = CyanPrimary
) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianElevated.copy(alpha = 0.62f))
            .border(0.9.dp, ObsidianBorder.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(3.dp)
    ) {
        val count = items.size.coerceAtLeast(1)
        val segmentWidth = maxWidth / count
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex.coerceIn(0, count - 1),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "segIndicator"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(9.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = 0.24f),
                            accent.copy(alpha = 0.12f)
                        )
                    )
                )
                .border(0.9.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
        )

        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { index, (label, icon) ->
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (index != selectedIndex) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(index)
                                }
                            }
                        )
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (selected) CyanBright else TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (selected) TextPrimary else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Splits numeric part ("5.6") from unit ("KB/s") for crisp tabular instrument styling.
 */
@Composable
fun MetricValueText(
    formatted: String,
    modifier: Modifier = Modifier,
    prefix: String = "",
    numberSize: TextUnit = 19.sp,
    unitSize: TextUnit = 11.5.sp,
    numberColor: Color = TextPrimary,
    unitColor: Color = TextSecondary,
    prefixColor: Color = TextSecondary
) {
    val spaceIdx = formatted.lastIndexOf(' ')
    val numPart = if (spaceIdx > 0) formatted.substring(0, spaceIdx) else formatted
    val unitPart = if (spaceIdx > 0) formatted.substring(spaceIdx) else ""

    Text(
        text = buildAnnotatedString {
            if (prefix.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        color = prefixColor,
                        fontSize = (unitSize.value + 1f).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                ) {
                    append(prefix)
                }
            }
            withStyle(
                SpanStyle(
                    color = numberColor,
                    fontSize = numberSize,
                    fontWeight = FontWeight.ExtraBold,
                    fontFeatureSettings = "tnum"
                )
            ) {
                append(numPart)
            }
            if (unitPart.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        color = unitColor,
                        fontSize = unitSize,
                        fontWeight = FontWeight.SemiBold
                    )
                ) {
                    append(unitPart)
                }
            }
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * Refined semantic micro-pill badge.
 */
@Composable
fun MicroPillBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color.copy(alpha = 0.20f) else color.copy(alpha = 0.12f))
            .border(
                width = 0.75.dp,
                color = color.copy(alpha = if (filled) 0.45f else 0.24f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1
        )
    }
}

@Composable
fun singScaleSwitchColors(accent: Color = CyanPrimary) = SwitchDefaults.colors(
    checkedThumbColor = Color(0xFF04131A),
    checkedTrackColor = accent,
    checkedBorderColor = accent,
    uncheckedThumbColor = TextSecondary,
    uncheckedTrackColor = ObsidianElevated,
    uncheckedBorderColor = ObsidianBorder
)
