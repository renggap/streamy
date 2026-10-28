package com.streamy.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * JokoUI (jokoui.web.id, MIT) design language, ported to Compose for Streamy.
 *
 * Faithful source patterns (fetched from the library's application components):
 *  - Buttons: Primary `px-6 py-3 bg-lime-600 text-white font-semibold rounded-lg shadow-md
 *    hover:bg-lime-700 focus:ring-2 focus:ring-lime-500`; Secondary `bg-gray-100 text-gray-800`;
 *    Outline `border-2 border-lime-600 text-lime-600 hover:bg-lime-600 hover:text-white`.
 *  - Cards: `rounded-xl border border-border bg-card` — white card on slate page.
 *  - Badges: `px-3 py-1 rounded-full text-sm font-medium bg-primary/10 text-primary`.
 *  - Section head: numbered `w-8 h-8 rounded-full bg-primary/10 text-primary` + `text-xl
 *    font-semibold text-foreground`.
 *  - Preview page canvas: `bg-[#f8fafc]` (slate-50) — the app page background.
 *  - Loaders: "Pulse Ring" — a scaling fading ring for busy states.
 *
 * Streamy mapping: lime-700 primary (one shade deeper than JokoUI's lime-600 for WCAG AA
 * contrast — see Theme.kt), slate neutrals, broadcast semantic hues (on-air red-600 /
 * live green-600 / amber-700) for the tally states. Light-first: slate-50 page, white
 * cards — the background is never shiny white. Text on a 10–14% tint of a hue uses the
 * hue's darker "ink" (e.g. lime-800 / red-800): the hue itself is only ~4:1 on its tint.
 */

/** JokoUI dot badge: soft-tinted pill, colored dot, label. The console status language. */
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
            .background(dotColor.copy(alpha = 0.10f))
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
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * JokoUI "Soft Badge": tinted pill (bg = [color] @ 14% alpha). [textColor] defaults to
 * [color] but callers pass the hue's darker ink (onPrimaryContainer / onErrorContainer):
 * a 600-weight hue on its own 14% tint is below 4.5:1.
 */
@Composable
fun JokoSoftBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    textColor: Color = color,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = textColor,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * JokoUI alert: soft tinted container + 1dp tinted border + dot marker + message.
 * Info/success/warning/error map to lime/green/amber/red soft fills.
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
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** JokoUI primary: `px-6 py-3 bg-lime-700 text-white font-semibold rounded-lg shadow-md`. */
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
            disabledContentColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 24.dp, vertical = verticalPadding,
        ),
        modifier = modifier.shadow(
            elevation = if (enabled) 4.dp else 0.dp,
            shape = RoundedCornerShape(8.dp),
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val defaultButtonPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 24.dp, vertical = 12.dp,
)

/** JokoUI outline: `border-2 border-lime-700 text-lime-700` (2dp border, tinted text). */
@Composable
fun JokoOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(2.dp, color.copy(alpha = if (enabled) 1f else 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color,
            disabledContentColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        contentPadding = defaultButtonPadding,
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** JokoUI secondary: `bg-gray-100 text-gray-800 font-semibold rounded-lg shadow-md`. */
@Composable
fun JokoSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        contentPadding = defaultButtonPadding,
        modifier = modifier.shadow(
            elevation = if (enabled) 4.dp else 0.dp,
            shape = RoundedCornerShape(8.dp),
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * JokoUI simple card: `rounded-xl border border-border bg-card` — white card floating on
 * the slate page. Title `text-xl font-semibold`, body `text-muted-foreground`.
 */
@Composable
fun JokoCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = { content() },
    )
}

/**
 * JokoUI section head: numbered `w-8 h-8 rounded-full bg-primary/10 text-primary` dot +
 * `text-xl font-semibold text-foreground` title — the docs-page section rhythm.
 */
@Composable
fun JokoSectionHead(
    index: Int,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        ) {
            Text(
                "$index",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,   // lime-800 on lime tint
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** JokoUI page eyebrow: `px-3 py-1 rounded-full bg-primary/10 text-primary` pill. */
@Composable
fun JokoEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onPrimaryContainer,   // lime-800 on lime tint (AA)
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
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
