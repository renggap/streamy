package com.streamy.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.streamy.app.R
import com.streamy.app.audio.LevelSnapshot
import kotlin.math.roundToInt

/*
 * JokoUI signal card: flat rows on the parent JokoCard (white rounded-xl on the slate page).
 * Lime-600 trace (the primary action hue) + slate grid; mono dB label; phosphor afterglow
 * (previous frame held at low alpha under the live trace).
 * No per-frame allocations in the draw lambda beyond the two reused [Path]s — colors and
 * Stroke widths are hoisted out as remembered values.
 */

private const val GRID_COLS = 6
private const val DB_FLOOR = -60f

@Composable
fun VuMeter(levelsFlow: StateFlow<LevelSnapshot?>, modifier: Modifier = Modifier) {
    val snapshot by levelsFlow.collectAsStateWithLifecycle()
    val outline = MaterialTheme.colorScheme.outline
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val canvasBg = MaterialTheme.colorScheme.surfaceVariant   // slate-100 scope face
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val traceColor = MaterialTheme.colorScheme.primary   // lime-600 (JokoPrimary)

    // Low-alpha variants — value-class copies, no alloc.
    val gridColor = outline.copy(alpha = 0.35f)
    val axisColor = outline.copy(alpha = 0.6f)
    val glowColor = traceColor.copy(alpha = 0.25f)
    val afterColor = traceColor.copy(alpha = 0.15f)

    // Density-aware stroke widths, hoisted so the draw lambda allocates nothing per frame.
    val traceStrokePx = with(LocalDensity.current) { 1.5.dp.toPx() }
    val glowStrokePx = with(LocalDensity.current) { 3.dp.toPx() }
    val traceStroke = remember(traceStrokePx) { Stroke(width = traceStrokePx, cap = StrokeCap.Round) }
    val glowStroke = remember(glowStrokePx) { Stroke(width = glowStrokePx, cap = StrokeCap.Round) }

    // Afterglow: the frame BEFORE the one being drawn. Plain (non-state) holder advanced inside
    // the draw pass: the old SideEffect wrote the *current* samples into state right after
    // composition, so the "afterglow" redrew the live frame (and cost an extra recomposition).
    val glow = remember { AfterglowHolder() }

    val tracePath = remember { Path() }
    val afterPath = remember { Path() }

    val db = snapshot?.rmsDb
    // One TalkBack node with the level as words instead of an unlabeled canvas + bare "-23 dB".
    val a11y = if (db == null) {
        stringResource(R.string.vu_cd_no_signal)
    } else {
        stringResource(R.string.vu_cd_level, dbValue(db))
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(104.dp)
            .background(canvasBg, RoundedCornerShape(8.dp))
            .clearAndSetSemantics { contentDescription = a11y },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            val w = size.width
            val h = size.height
            val center = h / 2f
            val half = h / 2f

            // Grid: faint verticals + center axis = the "scope" read.
            var i = 1
            while (i < GRID_COLS) {
                val x = w * i / GRID_COLS
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                i++
            }
            drawLine(axisColor, Offset(0f, center), Offset(w, center), strokeWidth = 1f)

            // Afterglow: previous frame at low alpha, under the current trace.
            val samples = snapshot?.samples
            val prev = glow.advance(samples)
            if (prev != null && prev.size > 1) {
                val n = prev.size
                afterPath.reset()
                afterPath.moveTo(0f, center + prev[0] * half)
                var j = 1
                while (j < n) {
                    afterPath.lineTo(w * j / (n - 1), center + prev[j] * half)
                    j++
                }
                drawPath(afterPath, color = afterColor, style = glowStroke)
            }

            // Trace + idle.
            if (samples != null && samples.size > 1) {
                val n = samples.size
                tracePath.reset()
                tracePath.moveTo(0f, center + samples[0] * half)
                var k = 1
                while (k < n) {
                    tracePath.lineTo(w * k / (n - 1), center + samples[k] * half)
                    k++
                }
                drawPath(tracePath, color = glowColor, style = glowStroke)   // wide low-alpha under
                drawPath(tracePath, color = traceColor, style = traceStroke) // narrow bright on top
            } else {
                drawLine(outlineVariant, Offset(0f, center), Offset(w, center), strokeWidth = 1f)
            }
        }

        Text(
            text = if (db == null) stringResource(R.string.vu_no_signal) else stringResource(R.string.vu_db, dbValue(db)),
            style = MaterialTheme.typography.labelMedium,
            color = onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Whole dB, clamped to the floor. NaN (silent/empty buffer) reads as the floor instead of throwing in roundToInt. */
private fun dbValue(db: Float): Int =
    if (db.isNaN() || db <= DB_FLOOR) DB_FLOOR.roundToInt() else db.roundToInt()

/**
 * Remembers the last two distinct sample arrays seen by the draw pass. [advance] returns the
 * one before [current]; a null (stream stopped) clears both so no stale ghost lingers.
 * Identity comparison: each LevelSnapshot carries a fresh array, and redraws of the same
 * frame (e.g. a recomposition for an unrelated reason) must not shift the history.
 */
private class AfterglowHolder {
    private var last: FloatArray? = null
    private var prev: FloatArray? = null

    fun advance(current: FloatArray?): FloatArray? {
        if (current == null) {
            last = null
            prev = null
        } else if (current !== last) {
            prev = last
            last = current
        }
        return prev
    }
}
