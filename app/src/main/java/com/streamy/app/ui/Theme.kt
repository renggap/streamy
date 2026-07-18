package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Hallmark · macrostructure: Workbench (broadcast console) · tone: technical / austere
 * theme: custom (atmospheric cluster · vibe: "late-night tally light")
 * paper-band: dark  ·  display-style: mono  ·  accent-hue: warm (on-air red) + signal-green
 *
 * Ponytail: swap the implicit system fonts for a bundled display face (e.g. JetBrains Mono
 * via compose-assets) when the brand identity hardens. Current FontFamily.Monospace is the
 * platform's UI monospace which is acceptable, but a named face gives stronger identity.
 */

// ---- Locked color tokens (OKLCH intent documented; sRGB vals are the Material3 reality) ----
//
// Paper dark ~ oklch(16% 0.005 240) — studio dim, near-black, cool-neutral.
// Ink         ~ oklch(94% 0.005 240) — off-white, slight cool bias, anti-pure-white.
// On-air red  ~ oklch(60% 0.22 25)   — broadcast tally-lamp, the canonical "RECORD" red.
// Signal grn  ~ oklch(78% 0.18 145)  — "live signal" green, dimmed to avoid gaming-green.
// Warning amb ~ oklch(80% 0.15 70)   — used for HE-AAC / restart-to-apply hints.
// Rule        ~ ink @ 12% alpha      — hairline divider language.

object StreamyColors {
    // Dark (primary palette — broadcast console is dark by default)
    val DarkPaper = Color(0xFF14161A)
    val DarkPaper2 = Color(0xFF1B1E24)     // raised surface (cards)
    val DarkPaper3 = Color(0xFF242830)     // raised surface (buttons)
    val DarkInk = Color(0xFFEDEFF3)
    val DarkInk2 = Color(0xFFA8AEB9)
    val DarkInk3 = Color(0xFF6B7280)        // tertiary / disabled
    val DarkRule = Color(0xFF2A2E36)

    val OnAir = Color(0xFFFF3B30)           // broadcast tally red
    val OnAirDim = Color(0xFF7A1A14)        // idle / outline state of red
    val Live = Color(0xFF30D17B)            // signal green
    val LiveDim = Color(0xFF0F5C36)
    val Warn = Color(0xFFFFC542)            // amber hint

    // Light (daylight variant — same accent hues, retuned for paper contrast)
    val LightPaper = Color(0xFFFAFAFB)
    val LightPaper2 = Color(0xFFFFFFFF)
    val LightPaper3 = Color(0xFFEFF1F4)
    val LightInk = Color(0xFF14161A)
    val LightInk2 = Color(0xFF4A4F58)
    val LightInk3 = Color(0xFF8A8F99)
    val LightRule = Color(0xFFE2E5EA)
}

private val StreamyDarkColors = darkColorScheme(
    primary = StreamyColors.OnAir,
    onPrimary = Color.White,
    primaryContainer = StreamyColors.OnAirDim,
    onPrimaryContainer = StreamyColors.OnAir,
    secondary = StreamyColors.Live,
    onSecondary = Color.Black,
    secondaryContainer = StreamyColors.LiveDim,
    onSecondaryContainer = StreamyColors.Live,
    tertiary = StreamyColors.Warn,
    background = StreamyColors.DarkPaper,
    onBackground = StreamyColors.DarkInk,
    surface = StreamyColors.DarkPaper2,
    onSurface = StreamyColors.DarkInk,
    surfaceVariant = StreamyColors.DarkPaper3,
    onSurfaceVariant = StreamyColors.DarkInk2,
    outline = StreamyColors.DarkRule,
    outlineVariant = StreamyColors.DarkInk3,
    error = StreamyColors.OnAir,
    onError = Color.White,
)

private val StreamyLightColors = lightColorScheme(
    primary = StreamyColors.OnAir,
    onPrimary = Color.White,
    primaryContainer = StreamyColors.OnAirDim,
    onPrimaryContainer = StreamyColors.OnAir,
    secondary = StreamyColors.Live,
    onSecondary = Color.Black,
    secondaryContainer = StreamyColors.LiveDim,
    onSecondaryContainer = StreamyColors.Live,
    tertiary = StreamyColors.Warn,
    background = StreamyColors.LightPaper,
    onBackground = StreamyColors.LightInk,
    surface = StreamyColors.LightPaper2,
    onSurface = StreamyColors.LightInk,
    surfaceVariant = StreamyColors.LightPaper3,
    onSurfaceVariant = StreamyColors.LightInk2,
    outline = StreamyColors.LightRule,
    outlineVariant = StreamyColors.LightInk3,
    error = StreamyColors.OnAir,
    onError = Color.White,
)

// ---- Typography ----
// Display = monospace (technical readout voice — bitrates, sample rates, state labels).
// Body    = platform sans-serif (humanist, comfortable for labels and helper copy).
// No italics on display. Emphasis carried by weight + size only.

object StreamyType {
    val Mono = FontFamily.Monospace
    val Sans = FontFamily.SansSerif
}

private val StreamyTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = StreamyType.Mono,
        fontWeight = FontWeight.Black,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = StreamyType.Mono,
        fontWeight = FontWeight.Black,
        fontSize = 42.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = StreamyType.Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = StreamyType.Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = StreamyType.Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = StreamyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = StreamyType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = StreamyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = StreamyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = StreamyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = StreamyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = StreamyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = StreamyType.Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,   // tracking-heavy mono labels = the "console" voice
    ),
    labelMedium = TextStyle(
        fontFamily = StreamyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = StreamyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.8.sp,
    ),
)

// ---- Shapes ----
// Deliberately small radii. The default Material3 "rounded everywhere" look is a slop tell;
// console surfaces want crisp edges with a hairline soften. Hard 0dp on hairline dividers,
// 2dp on chips, 4dp on cards, 0dp on the hero button (the tally button is a hard slab).

private val StreamyShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
)

@Composable
fun StreamyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) StreamyDarkColors else StreamyLightColors
    MaterialTheme(
        colorScheme = colors,
        typography = StreamyTypography,
        shapes = StreamyShapes,
        content = content,
    )
}

/** Filled background helper — keeps Surface usage terse at call sites. */
@Composable
fun StreamyBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) { content() }
}
