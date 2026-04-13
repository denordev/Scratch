@file:Suppress("MagicNumber")

package by.denor.scratch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*

/**
 * Default brush width used to "erase" the overlay.
 */
const val DEFAULT_STROKE_WIDTH_PX = 100f

/**
 * % of area that must be scratched before reveal triggers.
 */
const val DEFAULT_VANISH_THRESHOLD = 0.5f

/**
 * Minimum movement before recording next drag point (avoids too dense paths).
 */
const val MIN_RECORDED_POINT_DISTANCE_PX = 1.5f

/**
 * Grid resolution tuning for coverage calculation.
 */
const val COVERAGE_CELL_SIZE_RATIO = 0.4f
const val COVERAGE_SAMPLING_STEP_RATIO = 0.75f
const val MIN_COVERAGE_CELL_SIZE_PX = 6f
const val MAX_COVERAGE_CELL_SIZE_PX = 18f

/**
 * [SubcomposeLayout] slot ids. Used so the measure pass and the real UI are separate compositions.
 */
private enum class ScratchOverlaySlot {
    /** First pass: measure only — how large the scratchable composable lays out (size is read; slot is not placed). */
    MeasureScratchable,
    /** Full UI: revealed layer + scratch layer laid out at the measured pixel width and height. */
    Content,
}

/**
 * Scratch-to-reveal composable.
 *
 * **Visual stack (bottom → top)**  
 * 1. Optional [revealedContent] — what appears after scratching.  
 * 2. [scratchableContent] — the foil/image on top, drawn inside an offscreen layer where drags
 *    “erase” pixels using [BlendMode.Clear], so the layer behind shows through.
 *
 * **Why offscreen?**  
 * Clearing pixels must not punch holes through unrelated parents. [CompositingStrategy.Offscreen]
 * isolates the scratch surface so only this layer is affected.
 *
 * **End-to-end flow**  
 * 1. Layout gives the scratch surface a non-zero size ([ScratchState] needs real pixels for coverage).  
 * 2. [onSizeChanged] feeds that size (+ optional [ScratchOverlayConfig.clipShape]) into [ScratchState].  
 * 3. Drags append points; we draw a stroke with clear blend on top of the scratch content.  
 * 4. In parallel, [ScratchState] estimates how much area was scratched (grid “buckets”).  
 * 5. When enough buckets are hit → reveal flag → [onRevealed], then we animate overlay alpha to 0 (“vanish”).
 *
 * **Sizing modes** ([ScratchAreaSpec])  
 * - [ScratchAreaSpec.Fixed]: you pass an explicit [androidx.compose.ui.unit.DpSize].  
 * - [ScratchAreaSpec.MatchScratchable]: we measure [scratchableContent] first, then build the overlay
 *   at that size so you do not duplicate width/height. Note: scratchable is composed twice (measure +
 *   content); usually fine for images.
 */
@Composable
fun ScratchOverlay(
    config: ScratchOverlayConfig,
    modifier: Modifier = Modifier,
    state: ScratchState = rememberScratchState(),
    onScratchStarted: () -> Unit = {},
    onRevealed: () -> Unit = {},
    revealedContent: @Composable () -> Unit = {},
    scratchableContent: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val normalizedStrokeWidthPx = config.strokeWidthPx.coerceAtLeast(1f)
    val normalizedVanishThreshold = config.vanishThreshold.coerceIn(0f, 1f)

    // Fades only the top (scratch) layer after the reveal threshold is reached — does not remove composables.
    val vanishAlpha = remember { Animatable(1f) }

    // rememberUpdatedState: gesture/animation blocks always see the latest lambdas without restarting coroutines.
    val onScratchStartedState = rememberUpdatedState(onScratchStarted)
    val onRevealedState = rememberUpdatedState(onRevealed)

    // ScratchState is a plain class; SideEffect pushes brush/threshold whenever composable params change.
    SideEffect {
        state.updateParams(
            strokeWidthPx = normalizedStrokeWidthPx,
            newRevealThreshold = normalizedVanishThreshold
        )
    }

    // When coverage crosses the threshold, notify once and run the vanish animation on the scratch layer.
    LaunchedEffect(state.isRevealThresholdReached, config.vanishAnimationDurationMs) {
        if (state.isRevealThresholdReached) {
            onRevealedState.value()
            vanishAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(config.vanishAnimationDurationMs)
            )
        } else {
            // Geometry reset (e.g. new size) clears reveal — bring the foil back.
            vanishAlpha.snapTo(1f)
        }
    }

    // First time the user puts a finger down on the scratch layer.
    LaunchedEffect(state.hasUserStartedScratching) {
        if (state.hasUserStartedScratching) {
            onScratchStartedState.value()
        }
    }

    val clippedModifier = config.clipShape?.let { modifier.clip(it) } ?: modifier

    when (val area = config.scratchArea) {
        is ScratchAreaSpec.Fixed -> {
            // Caller-chosen size: lock the scratch layer to these Dp so coverage matches the card.
            val scratchSizeModifier = Modifier.requiredSize(area.size.width, area.size.height)
            ScratchOverlayLayers(
                modifier = clippedModifier,
                scratchSizeModifier = scratchSizeModifier,
                config = config,
                state = state,
                normalizedStrokeWidthPx = normalizedStrokeWidthPx,
                normalizedVanishThreshold = normalizedVanishThreshold,
                vanishAlpha = vanishAlpha,
                layoutDirection = layoutDirection,
                density = density,
                revealedContent = revealedContent,
                scratchableContent = scratchableContent,
            )
        }

        ScratchAreaSpec.MatchScratchable -> {
            // Two-phase layout: (1) measure scratchable to learn w×h, (2) build the real overlay at Constraints.fixed(w,h).
            // The measure slot’s placeable is intentionally not placed — only its measured size is used.
            SubcomposeLayout(modifier = clippedModifier) { constraints ->
                val measureConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                val measurePlaceable = subcompose(ScratchOverlaySlot.MeasureScratchable) {
                    scratchableContent()
                }.first().measure(measureConstraints)

                val w = measurePlaceable.width
                val h = measurePlaceable.height

                if (w == 0 || h == 0) {
                    layout(0, 0) {}
                } else {
                    layout(w, h) {
                        val contentPlaceable = subcompose(ScratchOverlaySlot.Content) {
                            val widthDp = with(density) { w.toDp() }
                            val heightDp = with(density) { h.toDp() }
                            val scratchSizeModifier = Modifier.requiredSize(widthDp, heightDp)
                            ScratchOverlayLayers(
                                modifier = Modifier.fillMaxSize(),
                                scratchSizeModifier = scratchSizeModifier,
                                config = config,
                                state = state,
                                normalizedStrokeWidthPx = normalizedStrokeWidthPx,
                                normalizedVanishThreshold = normalizedVanishThreshold,
                                vanishAlpha = vanishAlpha,
                                layoutDirection = layoutDirection,
                                density = density,
                                revealedContent = revealedContent,
                                scratchableContent = scratchableContent,
                            )
                        }.first().measure(Constraints.fixed(w, h))
                        contentPlaceable.place(0, 0)
                    }
                }
            }
        }
    }
}

/**
 * Shared visual tree for both [ScratchAreaSpec] modes: background, then the interactive scratch layer.
 *
 * Modifier order on the scratch [Box] matters (top = outer / applied first in the chain):
 * 1. [scratchSizeModifier] — fixed dimensions for coverage.
 * 2. [onSizeChanged] — pushes pixel size + clip into [ScratchState].
 * 3. [pointerInput] — drag → [ScratchState] path + coverage.
 * 4. [androidx.compose.ui.graphics.graphicsLayer] — offscreen + vanish alpha.
 * 5. [drawWithContent] — draw foil, then punch transparent “holes” along the path with [BlendMode.Clear].
 */
@Composable
private fun ScratchOverlayLayers(
    modifier: Modifier,
    scratchSizeModifier: Modifier,
    config: ScratchOverlayConfig,
    state: ScratchState,
    normalizedStrokeWidthPx: Float,
    normalizedVanishThreshold: Float,
    vanishAlpha: Animatable<Float, *>,
    layoutDirection: LayoutDirection,
    density: Density,
    revealedContent: @Composable () -> Unit,
    scratchableContent: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {

        Box(contentAlignment = Alignment.Center) {
            revealedContent()
        }

        Box(
            modifier = scratchSizeModifier
                .onSizeChanged { newSize ->
                    // Optional shape clip (e.g. circle): convert to Android Region so coverage ignores outside pixels.
                    val shapePath = config.clipShape?.let { shape ->
                        val floatSize = Size(newSize.width.toFloat(), newSize.height.toFloat())
                        when (val outline = shape.createOutline(floatSize, layoutDirection, density)) {
                            is Outline.Generic -> outline.path
                            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                        }
                    }

                    state.updateLayerSize(
                        size = newSize,
                        strokeWidthPx = normalizedStrokeWidthPx,
                        newRevealThreshold = normalizedVanishThreshold,
                        clipPath = shapePath
                    )
                }
                .pointerInput(state) {
                    detectDragGestures(
                        onDragStart = { state.handleDragStart(it) },
                        onDrag = { change, _ ->
                            if (state.isRevealThresholdReached) return@detectDragGestures
                            change.consume()
                            state.handleDrag(change.position)
                        },
                        onDragEnd = state::handleDragEnd,
                        onDragCancel = state::handleDragEnd
                    )
                }
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = vanishAlpha.value
                }
                .drawWithContent {
                    val clearStroke = Stroke(
                        width = normalizedStrokeWidthPx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )

                    val scratchPath = Path()
                    state.buildScratchPath(scratchPath)

                    drawContent()

                    if (!scratchPath.isEmpty) {
                        drawPath(
                            path = scratchPath,
                            color = Color.Transparent,
                            style = clearStroke,
                            blendMode = BlendMode.Clear
                        )
                    }
                }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                scratchableContent()
            }
        }
    }
}
