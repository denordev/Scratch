package by.denor.scratch

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.DpSize

/**
 * How [ScratchOverlay] chooses its layout width and height.
 *
 * - **[ScratchAreaSpec.Fixed]** — supply [size] in dp; the scratch layer uses [androidx.compose.foundation.layout.requiredSize].
 * - **[ScratchAreaSpec.MatchScratchable]** — run a measure-only [androidx.compose.ui.layout.SubcomposeLayout]
 *   pass on the scratchable composable, then lay out the real overlay at the measured pixel size.
 *   Use when the scratchable content already defines the card size (e.g. [androidx.compose.foundation.layout.size] on an [androidx.compose.foundation.Image]).
 */
@Immutable
sealed class ScratchAreaSpec {
    @Immutable
    data class Fixed(val size: DpSize) : ScratchAreaSpec()

    @Immutable
    data object MatchScratchable : ScratchAreaSpec()
}
