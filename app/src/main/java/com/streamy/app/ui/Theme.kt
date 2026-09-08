package com.streamy.app.ui

import androidx.compose.foundation.background
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
 * JokoUI (jokoui.web.id, MIT) design language, ported to Compose for Streamy.
 *
 * Source patterns (from the library's application components):
 *  - Buttons: Primary `px-6 py-3 bg-lime-600 text-white font-semibold rounded-lg shadow-md`,
 *    Secondary `bg-gray-100 text-gray-800`, Outline `border-2 border-lime-600 text-lime-600`.
 *  - Cards: `rounded-xl border border-border bg-card` (white card on a slate page).
 *  - Preview page: slate-50 `#f8fafc` canvas; badges `px-3 py-1 rounded-full bg-primary/10`.
 *  - Type: Poppins (font-sans); platform SansSerif is the fallback (no bundled font yet).
 *
 * Streamy mapping: lime-600 is the primary action hue; slate is the neutral scale; the
 * broadcast states keep semantic hues (on-air red-600, live green-600, amber-600) matching
 * JokoUI's error/success/warning alerts. The app is LIGHT-first: the page background is
 * slate-50 (`LightPaper`), never pure white — white is reserved for cards (bg-card).
 */

object StreamyColors {
    // Dark (kept for API compat — the app currently themes light-only; see StreamyTheme)
    val DarkPaper = Color(0xFF14161A)
    val DarkPaper2 = Color(0xFF1B1E24)     // raised surface (cards)
    val DarkPaper3 = Color(0xFF242830)     // raised surface (buttons)
    val DarkInk = Color(0xFFEDEFF3)
    val DarkInk2 = Color(0xFFA8AEB9)
    val DarkInk3 = Color(0xFF6B7280)        // tertiary / disabled
    val DarkRule = Color(0xFF2A2E36)

    val OnAir = Color(0xFFDC2626)           // broadcast tally red (JokoUI red-600 / error)
    val OnAirDim = Color(0xFF7A1A14)        // idle / outline state of red (dark theme)
    val Live = Color(0xFF16A34A)            // live signal green (JokoUI green-600 / success)
    val LiveDim = Color(0xFF0F5C36)
    val Warn = Color(0xFFD97706)            // amber hint (JokoUI amber-600 / warning)

    // JokoUI light scale (slate neutrals + lime primary)
    val JokoPrimary = Color(0xFF65A30D)     // lime-600 — primary buttons, focus rings, links
    val JokoPrimaryDark = Color(0xFF4D7C0F) // lime-700 — hover / strong tint text
    val JokoPrimarySoft = Color(0xFFECFCCB) // lime-100 — soft badge / active toggle fill
    val JokoPrimaryInk = Color(0xFF3F6212)  // lime-800 — text on soft fills

    // Light (JokoUI daylight — slate page, white cards; background is never shiny white)
    val LightPaper = Color(0xFFF8FAFC)      // slate-50 — app background (bg-[#f8fafc])
    val LightPaper2 = Color(0xFFFFFFFF)     // white — cards (bg-card) pop off the slate page
    val LightPaper3 = Color(0xFFF1F5F9)     // slate-100 — muted fills (bg-gray-100 / bg-muted)
    val LightInk = Color(0xFF0F172A)        // slate-900 — headings / body (text-foreground)
    val LightInk2 = Color(0xFF475569)       // slate-600 — secondary copy (text-muted)
    val LightInk3 = Color(0xFF94A3B8)       // slate-400 — tertiary / disabled
    val LightRule = Color(0xFFE2E8F0)       // slate-200 — card borders (border-border)
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
    primary = StreamyColors.JokoPrimary,            // lime-600 — primary actions
    onPrimary = Color.White,
    primaryContainer = StreamyColors.JokoPrimarySoft, // lime-100 — soft fills
    onPrimaryContainer = StreamyColors.JokoPrimaryInk, // lime-800 — text on soft fills
    secondary = StreamyColors.Live,                 // green-600 — live state
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCFCE7),         // green-100 — selected-tab pill
    onSecondaryContainer = Color(0xFF166534),       // green-800
    tertiary = StreamyColors.Warn,                  // amber-600 — hints
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),          // amber-100 — warning soft fills
    onTertiaryContainer = Color(0xFF92400E),        // amber-800
    error = StreamyColors.OnAir,                    // red-600 — stop / destructive / errors
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),             // red-100
    onErrorContainer = Color(0xFF991B1B),           // red-800
    background = StreamyColors.LightPaper,          // slate-50 — page, never white
    onBackground = StreamyColors.LightInk,
    surface = StreamyColors.LightPaper2,            // white — cards (bg-card)
    onSurface = StreamyColors.LightInk,
    surfaceVariant = StreamyColors.LightPaper3,     // slate-100 — muted fills
    onSurfaceVariant = StreamyColors.LightInk2,
    surfaceContainerLowest = StreamyColors.LightPaper2,
    surfaceContainerLow = StreamyColors.LightPaper,
    surfaceContainer = StreamyColors.LightPaper3,
    surfaceContainerHigh = StreamyColors.LightPaper2,
    surfaceContainerHighest = StreamyColors.LightRule,
    surfaceDim = StreamyColors.LightPaper,
    surfaceBright = StreamyColors.LightPaper2,
    outline = StreamyColors.LightRule,              // slate-200 — card borders
    outlineVariant = StreamyColors.LightInk3,       // slate-400
    inverseSurface = Color(0xFF0F172A),              // slate-900 — snackbars/toasts
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFFA3E635),              // lime-400 — readable on dark
    scrim = Color(0xFF0F172A),
)

// ---- Typography ----
// JokoUI ships Poppins (font-sans); the app has no bundled font yet, so the platform
// sans is the stand-in for headings/body and monospace keeps the technical readout voice
// (dB labels, bitrates, state pills). Labels stay tracked-out — that is the console voice.

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
// JokoUI card language: `rounded-xl` (12dp) cards, `rounded-lg` (8dp) controls,
// `rounded-full` pills. M3 mapped: large cards, medium/small controls, extraSmall chips.

private val StreamyShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(50),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
)

@Composable
fun StreamyTheme(
    darkTheme: Boolean = false,   // JokoUI daylight-first: light always, never follow system dark
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
