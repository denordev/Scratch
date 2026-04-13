package by.denor.scratch

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape

/**
 * Immutable tuning for [ScratchOverlay] (brush, threshold, animation, clip, sizing mode).
 *
 * **Sizing** — [scratchArea] decides how the overlay’s width/height are chosen:
 * - [ScratchAreaSpec.MatchScratchable] (default): measure the scratchable composable, then lock the
 *   overlay to that size (avoids duplicating dp).
 * - [ScratchAreaSpec.Fixed]: use an explicit [androidx.compose.ui.unit.DpSize] when measurement is not desired.
 *
 * **Clip** — [clipShape] affects drawing *and* coverage: out-of-shape buckets do not count toward reveal.
 */
@Immutable
data class ScratchOverlayConfig(
    val scratchArea: ScratchAreaSpec = ScratchAreaSpec.MatchScratchable,
    val clipShape: Shape? = null,
    val strokeWidthPx: Float = DEFAULT_STROKE_WIDTH_PX,
    val vanishThreshold: Float = DEFAULT_VANISH_THRESHOLD,
    val vanishAnimationDurationMs: Int = 400,
)
