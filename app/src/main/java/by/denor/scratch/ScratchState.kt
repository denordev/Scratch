package by.denor.scratch

import android.graphics.RectF
import android.graphics.Region
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow


@Composable
internal fun rememberScratchState(): ScratchState = remember { ScratchState() }


/**
 * Mutable scratch / reveal model used by [ScratchOverlay].
 *
 * **Responsibilities (three layers)**  
 * 1. **Input** — store polyline points for the erase stroke ([buildScratchPath]).  
 * 2. **Coverage** — approximate “how much of the card is scratched” with a coarse grid (buckets),
 *    not by integrating pixel alpha (too expensive). Cell size scales with brush width.  
 * 3. **Reveal** — compare distinct scratched buckets to a target derived from [DEFAULT_VANISH_THRESHOLD]
 *    (or the configured fraction) and [visibleBucketCount] (cells inside optional clip).
 *
 * **Why [android.graphics.Region]?**  
 * If the overlay uses a non-rectangular [androidx.compose.ui.graphics.Shape], we build an Android
 * [Region] from the clip path so bucket tests ignore areas outside the shape (e.g. corners of a circle).
 *
 * **Threading** — all methods are main-thread / composition scoped; no threading concerns.
 */
@Stable
class ScratchState internal constructor() {

    /** Set on first finger-down after geometry is valid; drives [onScratchStarted] in the overlay. */
    var hasUserStartedScratching by mutableStateOf(false)
        private set

    /** When [scratchedBuckets] count reaches [revealBucketTarget]. */
    var isRevealThresholdReached by mutableStateOf(false)
        private set

    /**
     * Polyline for drawing the clear stroke: [Offset] points in order; `null` starts a new stroke
     * after a lift (multi-stroke support).
     */
    private val scratchPoints = mutableStateListOf<Offset?>()

    /**
     * Set of grid cell ids touched by the brush at least once. Id = `row * coverageColumns + col`.
     * Using a set avoids double-counting when the user revisits the same cell.
     */
    private val scratchedBuckets = HashSet<Int>()

    /** Last point in the current stroke; `null` after [handleDragEnd]. */
    private var lastDragPoint: Offset? = null

    /** Last size passed to [updateLayerSize]; must be non-zero for gestures and coverage. */
    private var layerSize: IntSize = IntSize.Companion.Zero

    private var brushWidthPx: Float = DEFAULT_STROKE_WIDTH_PX
    private var revealThreshold: Float = DEFAULT_VANISH_THRESHOLD

    /** Derived from brush width — each bucket is roughly a fraction of the stroke (see [COVERAGE_CELL_SIZE_RATIO]). */
    private var coverageCellSizePx: Float = MIN_COVERAGE_CELL_SIZE_PX
    private var coverageColumns: Int = 1
    private var coverageRows: Int = 1

    /** `ceil(visibleBucketCount * revealThreshold)` — how many distinct buckets must be hit. */
    private var revealBucketTarget: Int = 1

    /** Buckets whose center lies inside [clipRegion] (or full grid if no clip). */
    private var visibleBucketCount: Int = 1

    /** Built from optional clip path; used in [stampBrushAt] / [countVisibleBuckets]. */
    private var clipRegion: Region? = null

    /**
     * Called when brush or threshold changes while layout size is already known.
     * No-op until [layerSize] is valid — matches [updateLayerSize] lifecycle.
     */
    internal fun updateParams(
        strokeWidthPx: Float,
        newRevealThreshold: Float
    ) {
        if (layerSize.width <= 0 || layerSize.height <= 0) return

        val brushChanged = brushWidthPx != strokeWidthPx

        if (brushChanged) {
            applyGeometry(layerSize, strokeWidthPx)
        }

        if (brushChanged || revealThreshold != newRevealThreshold) {
            revealThreshold = newRevealThreshold
            recalculateRevealTarget()
            updateRevealState()
        }
    }

    /**
     * Invoked from [onSizeChanged] on the scratch layer: establishes grid, clip region, and resets
     * scratch data when width/height or brush changes.
     */
    internal fun updateLayerSize(
        size: IntSize,
        strokeWidthPx: Float,
        newRevealThreshold: Float,
        clipPath: Path? = null
    ) {
        val geometryChanged = layerSize != size || brushWidthPx != strokeWidthPx

        if (geometryChanged) {
            clipRegion = clipPath?.let { path ->
                val bounds = RectF()
                path.asAndroidPath().computeBounds(bounds, true)

                Region().apply {
                    setPath(
                        path.asAndroidPath(),
                        Region(
                            bounds.left.toInt(),
                            bounds.top.toInt(),
                            bounds.right.toInt(),
                            bounds.bottom.toInt()
                        )
                    )
                }
            }

            applyGeometry(size, strokeWidthPx)
        }

        if (geometryChanged || revealThreshold != newRevealThreshold) {
            revealThreshold = newRevealThreshold
            recalculateRevealTarget()
            updateRevealState()
        }
    }

    internal fun handleDragStart(offset: Offset) {
        if (isRevealThresholdReached || layerSize.width <= 0) return

        hasUserStartedScratching = true

        if (scratchPoints.isNotEmpty()) scratchPoints.add(null)
        scratchPoints.add(offset)

        lastDragPoint = offset

        markCoverageAlongSegment(offset, offset)
        updateRevealState()
    }

    /**
     * Appends points when the finger moves far enough — keeps the path light and avoids oversampling.
     */
    internal fun handleDrag(position: Offset) {
        if (isRevealThresholdReached) return

        val previous = lastDragPoint ?: return handleDragStart(position)

        val dx = position.x - previous.x
        val dy = position.y - previous.y

        val movedEnough =
            (dx * dx + dy * dy) >= MIN_RECORDED_POINT_DISTANCE_PX.pow(2)

        if (!movedEnough) return

        scratchPoints.add(position)
        lastDragPoint = position

        markCoverageAlongSegment(previous, position)
        updateRevealState()
    }

    internal fun handleDragEnd() {
        lastDragPoint = null
    }

    /**
     * Replays [scratchPoints] into [path] for the clear stroke in [androidx.compose.ui.draw.drawWithContent].
     */
    internal fun buildScratchPath(path: Path) {
        path.reset()
        var move = true

        scratchPoints.forEach { point ->
            when {
                point == null -> move = true
                move -> {
                    path.moveTo(point.x, point.y)
                    move = false
                }
                else -> path.lineTo(point.x, point.y)
            }
        }
    }

    /**
     * Rebuilds the coverage grid for the new [size]. Clears scratch state — resizing starts a fresh card.
     */
    private fun applyGeometry(size: IntSize, strokeWidthPx: Float) {
        layerSize = size
        brushWidthPx = strokeWidthPx

        coverageCellSizePx = (brushWidthPx * COVERAGE_CELL_SIZE_RATIO)
            .coerceIn(MIN_COVERAGE_CELL_SIZE_PX, MAX_COVERAGE_CELL_SIZE_PX)

        coverageColumns = max(1, ceil(size.width / coverageCellSizePx).toInt())
        coverageRows = max(1, ceil(size.height / coverageCellSizePx).toInt())

        visibleBucketCount = countVisibleBuckets()

        clearScratchData()
    }

    private fun countVisibleBuckets(): Int {
        val region = clipRegion ?: return coverageColumns * coverageRows

        var count = 0
        for (row in 0 until coverageRows) {
            for (col in 0 until coverageColumns) {
                val x = ((col + 0.5f) * coverageCellSizePx).toInt()
                val y = ((row + 0.5f) * coverageCellSizePx).toInt()
                if (region.contains(x, y)) count++
            }
        }
        return max(1, count)
    }

    private fun recalculateRevealTarget() {
        revealBucketTarget = max(
            1,
            ceil(visibleBucketCount * revealThreshold).toInt()
        )
    }

    private fun clearScratchData() {
        scratchPoints.clear()
        scratchedBuckets.clear()
        lastDragPoint = null
        hasUserStartedScratching = false
        isRevealThresholdReached = false
    }

    /**
     * Walks the segment in steps so a fast drag still marks intermediate buckets (continuous coverage).
     */
    private fun markCoverageAlongSegment(start: Offset, end: Offset) {
        val dx = end.x - start.x
        val dy = end.y - start.y

        val steps = max(
            1,
            ceil(
                max(abs(dx), abs(dy)) /
                        max(1f, coverageCellSizePx * COVERAGE_SAMPLING_STEP_RATIO)
            ).toInt()
        )

        for (i in 0..steps) {
            val t = i / steps.toFloat()
            stampBrushAt(
                start.x + dx * t,
                start.y + dy * t
            )
        }
    }

    /**
     * Marks every bucket whose center falls inside the circle of radius [brushWidthPx]/2 around ([x],[y]),
     * intersected with [clipRegion] when present.
     */
    private fun stampBrushAt(x: Float, y: Float) {
        val radius = brushWidthPx * 0.5f
        val radiusSq = radius * radius

        val minCol = floor((x - radius) / coverageCellSizePx).toInt().coerceAtLeast(0)
        val maxCol = floor((x + radius) / coverageCellSizePx).toInt().coerceAtMost(coverageColumns - 1)

        val minRow = floor((y - radius) / coverageCellSizePx).toInt().coerceAtLeast(0)
        val maxRow = floor((y + radius) / coverageCellSizePx).toInt().coerceAtMost(coverageRows - 1)

        val region = clipRegion

        for (row in minRow..maxRow) {
            val cy = (row + 0.5f) * coverageCellSizePx
            val dySq = (cy - y).let { it * it }

            for (col in minCol..maxCol) {
                val cx = (col + 0.5f) * coverageCellSizePx
                val distSq = (cx - x).let { it * it } + dySq

                if (distSq <= radiusSq) {
                    if (region == null || region.contains(cx.toInt(), cy.toInt())) {
                        scratchedBuckets.add(row * coverageColumns + col)
                    }
                }
            }
        }
    }

    private fun updateRevealState() {
        if (!isRevealThresholdReached &&
            scratchedBuckets.size >= revealBucketTarget
        ) {
            isRevealThresholdReached = true
        }
    }
}
