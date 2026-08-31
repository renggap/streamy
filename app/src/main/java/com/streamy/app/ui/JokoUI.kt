package com.streamy.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * JokoUI (jokoui.web.id, MIT) design language, ported to Compose for Streamy.
 *
 * Tokens taken from the library's application components:
 *  - Badges: "Dot Badges" (gray pill + colored status dot) and "Soft Badges"
 *    (tinted bg / tinted text, rounded-full) — the status language of the console.
 *  - Buttons: Primary (solid, rounded-lg), Outline (2dp border, tinted text) — full-width on mobile.
 *  - Alerts: soft tinted container + 1dp tinted border + dot marker — used for hints.
 *  - Loaders: "Pulse Ring" — a scaling fading ring for busy states.
 *
 * Streamy mapping: JokoUI's Tailwind palette is indigo/green/yellow/red on slate. Streamy's
 * locked console palette (on-air red / signal green / amber) keeps its hue identity but adopts
 * the JokoUI *structures*: pill+dot status badges, soft two-tone fills, tinted alert cards,
 * pulse-ring loaders. Sizes follow the library: badges px-3/py-1 (12/4dp), buttons px-6/py-3
 * (24/12dp), rounded-lg (8dp), border-2 (2dp) on outline variants.
 */

/** JokoUI "Dot Badge": gray pill, colored dot, label. The console status language. */
@Composable
fun JokoDotBadge(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
) {
    val pulseAlpha = if (pulse) {
        val t = rememberInfiniteTransition(label = "joko-dot-pulse")
        val a by t.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_400),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        a
    } else 1f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(pulseAlpha)
                .background(dotColor, CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** JokoUI "Soft Badge": tinted pill (bg = color @ 14% alpha, text = color). */
@Composable
fun JokoSoftBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * JokoUI "Alert": tinted container, 1dp tinted border, dot marker + message.
 * Maps Streamy hints (dropped frames, restart-to-apply, recorder errors) to this structure.
 */
@Composable
fun JokoAlert(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/** jokoui Primary: solid slab, rounded-lg — the console hero. */
@Composable
fun JokoPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    verticalPadding: Dp = 12.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 24.dp, vertical = verticalPadding,
        ),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
        )
    }
}

private val defaultButtonPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 24.dp, vertical = 12.dp,
)

/** jokoui Outline: 2dp border, tinted text; Material ripple covers the press fill. */
@Composable
fun JokoOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, color.copy(alpha = 0.55f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = defaultButtonPadding,
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** JokoUI "Pulse Ring" loader: scaling, fading ring — busy states (Connecting, etc.). */
@Composable
fun JokoPulseRing(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val t = rememberInfiniteTransition(label = "joko-pulse-ring")
    val scale by t.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scale",
    )
    val alpha by t.animateFloat(
        initialValue = 0.9f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fade",
    )
    Box(
        modifier
            .size(size)
            .scale(scale)
            .alpha(alpha)
            .border(3.dp, color, CircleShape),
    )
}

/** jokoui-styled outlined text field (8dp radius). */
@Composable
fun JokoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    supportingText: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        singleLine = true,
        label = label?.let { { Text(it, style = MaterialTheme.typography.labelMedium) } },
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        supportingText = supportingText,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(),
    )
}

/** Vertical stack helper matching jokoui's form section rhythm (space-y-4 = 16dp). */
@Composable
fun JokoFormSection(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = { content() },
    )
}
