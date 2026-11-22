package com.example.domain.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.db.TrainingProfileEntity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class DetectedBallKind { CUE, TARGET, EIGHT_BALL }

data class DetectedBall(
    val normX: Float, // normalized 0..1 across full screen
    val normY: Float, // normalized 0..1 across full screen
    val kind: DetectedBallKind,
    val sampleColor: Int
)

data class PoolPocket(
    val id: String,
    val normX: Float,
    val normY: Float
)

data class PlannedPoolShot(
    val cueBall: DetectedBall,
    val targetBall: DetectedBall,
    val pocket: PoolPocket,
    val aimPointX: Float,
    val aimPointY: Float,
    val cutAngleDeg: Float,
    val totalDistance: Float,
    val basePowerFraction: Float,
    val stateHash: String,
    val stateSummary: String
)

data class PoolFrameAnalysis(
    val isMyTurn: Boolean,
    val greenTurnRatio: Float,
    val pottedOccupiedRatio: Float,
    val cueBall: DetectedBall?,
    val targetBalls: List<DetectedBall>,
    val eightBall: DetectedBall?,
    val pockets: List<PoolPocket>,
    val plannedShot: PlannedPoolShot?,
    val visionGrid: List<Float>,
    val summary: String
)

/**
 * Computer-vision analyzer for Pool / Billiards mobile game screenshots and
 * live MediaProjection frames. Reads the table felt, detects Cue Ball, Colored
 * Target Balls, 8-Ball, Green Turn Border, and Potted Ball Indicator Bar.
 */
object PoolVisionAnalyzer {

    fun analyze(bitmap: Bitmap, profile: TrainingProfileEntity): PoolFrameAnalysis {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)

        val tLeft = profile.tableLeft.coerceIn(0.05f, 0.45f)
        val tTop = profile.tableTop.coerceIn(0.05f, 0.45f)
        val tRight = profile.tableRight.coerceIn(tLeft + 0.25f, 0.96f)
        val tBottom = profile.tableBottom.coerceIn(tTop + 0.25f, 0.98f)

        val pockets = listOf(
            PoolPocket("TL", tLeft + 0.025f, tTop + 0.035f),
            PoolPocket("TM", (tLeft + tRight) * 0.5f, tTop + 0.025f),
            PoolPocket("TR", tRight - 0.025f, tTop + 0.035f),
            PoolPocket("BL", tLeft + 0.025f, tBottom - 0.035f),
            PoolPocket("BM", (tLeft + tRight) * 0.5f, tBottom - 0.025f),
            PoolPocket("BR", tRight - 0.025f, tBottom - 0.035f)
        )

        // 1. Check green turn glow around player avatar
        val greenTurnRatio = measureGreenBorderRatio(bitmap, profile.turnBoxX, profile.turnBoxY)
        val isMyTurn = greenTurnRatio >= 0.14f

        // 2. Measure potted balls indicator bar saturation/brightness
        val pottedRatio = measurePottedSlotsRatio(
            bitmap,
            profile.pottedBarLeft,
            profile.pottedBarRight,
            profile.pottedBarY
        )

        // 3. Estimate table felt color using median-like sample of 9 interior table points
        val feltHsv = estimateFeltColorHsv(bitmap, tLeft, tTop, tRight, tBottom)

        // 4. Scan table interior on a dense grid to cluster balls
        val cols = 64
        val rows = 36
        val visited = BooleanArray(cols * rows)
        val isBallCell = BooleanArray(cols * rows)
        val cellColors = IntArray(cols * rows)
        val cellLum = FloatArray(cols * rows)
        val cellSat = FloatArray(cols * rows)

        val hsv = FloatArray(3)
        for (r in 0 until rows) {
            val ny = tTop + ((r + 0.5f) / rows) * (tBottom - tTop)
            val py = (ny * h).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val nx = tLeft + ((c + 0.5f) / cols) * (tRight - tLeft)
                val px = (nx * w).toInt().coerceIn(0, w - 1)
                val pixel = bitmap.getPixel(px, py)
                Color.colorToHSV(pixel, hsv)
                val hueDiff = abs(hsv[0] - feltHsv[0]).let { minOf(it, 360f - it) }
                val valDiff = abs(hsv[2] - feltHsv[2])
                val satDiff = abs(hsv[1] - feltHsv[1])

                val idx = r * cols + c
                cellColors[idx] = pixel
                cellLum[idx] = hsv[2]
                cellSat[idx] = hsv[1]

                // Exclude thin white head-string line (only 1px wide) via clustering size >= 2 later
                val differsFromFelt = hueDiff > 24f || satDiff > 0.25f || valDiff > 0.24f
                isBallCell[idx] = differsFromFelt
            }
        }

        val detectedBalls = mutableListOf<DetectedBall>()
        val queue = IntArray(cols * rows)

        for (startIdx in isBallCell.indices) {
            if (!isBallCell[startIdx] || visited[startIdx]) continue
            var head = 0
            var tail = 0
            queue[tail++] = startIdx
            visited[startIdx] = true

            var sumC = 0f
            var sumR = 0f
            var count = 0
            var maxLum = 0f
            var avgLum = 0f
            var avgSat = 0f
            var repColor = cellColors[startIdx]
            var minR = rows
            var maxR = 0
            var minC = cols
            var maxC = 0

            while (head < tail) {
                val curr = queue[head++]
                val cr = curr / cols
                val cc = curr % cols
                sumR += cr
                sumC += cc
                count++
                avgLum += cellLum[curr]
                avgSat += cellSat[curr]
                if (cellLum[curr] > maxLum) {
                    maxLum = cellLum[curr]
                    repColor = cellColors[curr]
                }
                if (cr < minR) minR = cr
                if (cr > maxR) maxR = cr
                if (cc < minC) minC = cc
                if (cc > maxC) maxC = cc

                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val nr = cr + dr
                        val nc = cc + dc
                        if (nr in 0 until rows && nc in 0 until cols) {
                            val nIdx = nr * cols + nc
                            if (isBallCell[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }
            }

            val spanR = maxR - minR + 1
            val spanC = maxC - minC + 1
            // Filter out tiny noise (1 cell) and long cue-stick shafts (aspect ratio > 3.5)
            val aspectOk = maxOf(spanR, spanC).toFloat() / minOf(spanR, spanC).coerceAtLeast(1) < 3.2f
            if (count in 2..22 && spanR <= 6 && spanC <= 6 && aspectOk) {
                val centerCol = sumC / count
                val centerRow = sumR / count
                val normX = tLeft + ((centerCol + 0.5f) / cols) * (tRight - tLeft)
                val normY = tTop + ((centerRow + 0.5f) / rows) * (tBottom - tTop)
                val mLum = avgLum / count
                val mSat = avgSat / count

                val kind = when {
                    mLum > 0.78f && mSat < 0.24f -> DetectedBallKind.CUE
                    mLum < 0.24f -> DetectedBallKind.EIGHT_BALL
                    else -> DetectedBallKind.TARGET
                }
                detectedBalls.add(DetectedBall(normX, normY, kind, repColor))
            }
        }

        val cueBall = detectedBalls
            .filter { it.kind == DetectedBallKind.CUE }
            .maxByOrNull { Color.red(it.sampleColor) + Color.green(it.sampleColor) + Color.blue(it.sampleColor) }

        val eightBall = detectedBalls.firstOrNull { it.kind == DetectedBallKind.EIGHT_BALL }
        val targets = detectedBalls.filter { it.kind == DetectedBallKind.TARGET }

        val planned = computeBestShot(cueBall, targets.ifEmpty { listOfNotNull(eightBall) }, pockets)

        // Build 6x6 vision radar grid from table cells
        val visionGrid = build6x6VisionGrid(bitmap, tLeft, tTop, tRight, tBottom, cueBall, targets)

        val summary = buildString {
            append(if (isMyTurn) "YOUR TURN" else "WAIT TURN")
            append(" | Cue: ")
            append(if (cueBall != null) "(${(cueBall.normX * 100).toInt()}%,${(cueBall.normY * 100).toInt()}%)" else "N/A")
            append(" | Targets: ${targets.size}")
            if (planned != null) {
                append(" | Cut ${planned.cutAngleDeg.toInt()}° -> ${planned.pocket.id}")
            }
        }

        return PoolFrameAnalysis(
            isMyTurn = isMyTurn,
            greenTurnRatio = greenTurnRatio,
            pottedOccupiedRatio = pottedRatio,
            cueBall = cueBall,
            targetBalls = targets,
            eightBall = eightBall,
            pockets = pockets,
            plannedShot = planned,
            visionGrid = visionGrid,
            summary = summary
        )
    }

    fun computeAimPointWithTrim(shot: PlannedPoolShot, angleTrimDeg: Float): Pair<Float, Float> {
        val dx = shot.aimPointX - shot.cueBall.normX
        val dy = shot.aimPointY - shot.cueBall.normY
        val rad = angleTrimDeg * (PI / 180.0)
        val rx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ry = (dx * sin(rad) + dy * cos(rad)).toFloat()
        return (shot.cueBall.normX + rx).coerceIn(0.16f, 0.84f) to
            (shot.cueBall.normY + ry).coerceIn(0.18f, 0.94f)
    }

    private fun computeBestShot(
        cue: DetectedBall?,
        targets: List<DetectedBall>,
        pockets: List<PoolPocket>
    ): PlannedPoolShot? {
        if (cue == null || targets.isEmpty()) return null

        var bestScore = Float.NEGATIVE_INFINITY
        var bestShot: PlannedPoolShot? = null
        val ballRadiusNorm = 0.018f

        for (target in targets) {
            val ctDx = target.normX - cue.normX
            val ctDy = target.normY - cue.normY
            val ctDist = hypot(ctDx, ctDy)
            if (ctDist < 0.02f) continue

            for (pocket in pockets) {
                val tpDx = pocket.normX - target.normX
                val tpDy = pocket.normY - target.normY
                val tpDist = hypot(tpDx, tpDy)
                if (tpDist < 0.02f) continue

                // Dot product gives cosine of cut angle
                val dot = ((ctDx / ctDist) * (tpDx / tpDist) + (ctDy / ctDist) * (tpDy / tpDist)).coerceIn(-1f, 1f)
                val cutAngleRad = kotlin.math.acos(dot)
                val cutAngleDeg = (cutAngleRad * 180.0 / PI).toFloat()

                if (cutAngleDeg > 68f) continue // infeasible steep cut

                // Ghost ball aim point behind target ball opposite pocket direction
                val ghostX = target.normX - (tpDx / tpDist) * (ballRadiusNorm * 1.9f)
                val ghostY = target.normY - (tpDy / tpDist) * (ballRadiusNorm * 1.9f)

                val totalDist = ctDist + tpDist
                // Lower cut angle & shorter distance = higher shot quality
                val geomScore = (90f - cutAngleDeg) * 1.4f - totalDist * 45f

                if (geomScore > bestScore) {
                    bestScore = geomScore
                    val angleBucket = (cutAngleDeg / 15f).toInt().coerceIn(0, 4)
                    val distBucket = (totalDist * 5f).toInt().coerceIn(0, 4)
                    val basePower = (0.42f + totalDist * 0.55f + (cutAngleDeg / 180f) * 0.15f).coerceIn(0.35f, 0.95f)
                    val stateHash = "POOL_A${angleBucket}_D${distBucket}_${pocket.id}"
                    bestShot = PlannedPoolShot(
                        cueBall = cue,
                        targetBall = target,
                        pocket = pocket,
                        aimPointX = ghostX.coerceIn(0.16f, 0.84f),
                        aimPointY = ghostY.coerceIn(0.18f, 0.94f),
                        cutAngleDeg = cutAngleDeg,
                        totalDistance = totalDist,
                        basePowerFraction = basePower,
                        stateHash = stateHash,
                        stateSummary = "Cut ${cutAngleDeg.toInt()}° | Dist ${"%.2f".format(totalDist)} -> ${pocket.id}"
                    )
                }
            }
        }
        return bestShot
    }

    private fun estimateFeltColorHsv(
        bitmap: Bitmap,
        tLeft: Float,
        tTop: Float,
        tRight: Float,
        tBottom: Float
    ): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val hues = mutableListOf<Float>()
        val sats = mutableListOf<Float>()
        val vals = mutableListOf<Float>()
        val hsv = FloatArray(3)
        for (ry in listOf(0.28f, 0.50f, 0.72f)) {
            for (rx in listOf(0.25f, 0.50f, 0.75f)) {
                val px = ((tLeft + rx * (tRight - tLeft)) * w).toInt().coerceIn(0, w - 1)
                val py = ((tTop + ry * (tBottom - tTop)) * h).toInt().coerceIn(0, h - 1)
                Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                hues.add(hsv[0])
                sats.add(hsv[1])
                vals.add(hsv[2])
            }
        }
        hues.sort()
        sats.sort()
        vals.sort()
        return floatArrayOf(hues[hues.size / 2], sats[sats.size / 2], vals[vals.size / 2])
    }

    private fun measureGreenBorderRatio(bitmap: Bitmap, centerX: Float, centerY: Float): Float {
        val w = bitmap.width
        val h = bitmap.height
        val cx = (centerX * w).toInt()
        val cy = (centerY * h).toInt()
        val rx = (0.045f * w).toInt().coerceAtLeast(8)
        val ry = (0.065f * h).toInt().coerceAtLeast(8)
        var greenCount = 0
        var total = 0
        val stepX = (rx / 6).coerceAtLeast(1)
        val stepY = (ry / 6).coerceAtLeast(1)
        for (y in (cy - ry)..(cy + ry) step stepY) {
            if (y !in 0 until h) continue
            for (x in (cx - rx)..(cx + rx) step stepX) {
                if (x !in 0 until w) continue
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (g > 150 && g > r + 45 && g > b + 45) {
                    greenCount++
                }
                total++
            }
        }
        return if (total > 0) greenCount.toFloat() / total else 0f
    }

    private fun measurePottedSlotsRatio(
        bitmap: Bitmap,
        leftX: Float,
        rightX: Float,
        centerY: Float
    ): Float {
        val w = bitmap.width
        val h = bitmap.height
        val py = (centerY * h).toInt().coerceIn(0, h - 1)
        var bright = 0
        val samples = 14
        val hsv = FloatArray(3)
        for (i in 0 until samples) {
            val nx = leftX + (i.toFloat() / (samples - 1)) * (rightX - leftX)
            val px = (nx * w).toInt().coerceIn(0, w - 1)
            Color.colorToHSV(bitmap.getPixel(px, py), hsv)
            if (hsv[2] > 0.35f) bright++
        }
        return bright.toFloat() / samples
    }

    private fun build6x6VisionGrid(
        bitmap: Bitmap,
        tLeft: Float,
        tTop: Float,
        tRight: Float,
        tBottom: Float,
        cueBall: DetectedBall?,
        targets: List<DetectedBall>
    ): List<Float> {
        val grid = MutableList(36) { 0.10f }
        val w = bitmap.width
        val h = bitmap.height
        for (r in 0 until 6) {
            for (c in 0 until 6) {
                val nx = tLeft + ((c + 0.5f) / 6f) * (tRight - tLeft)
                val ny = tTop + ((r + 0.5f) / 6f) * (tBottom - tTop)
                val p = bitmap.getPixel((nx * w).toInt().coerceIn(0, w - 1), (ny * h).toInt().coerceIn(0, h - 1))
                val lum = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
                grid[r * 6 + c] = (lum * 0.45f).coerceIn(0.05f, 0.45f)
            }
        }
        for (ball in targets) {
            val gc = (((ball.normX - tLeft) / (tRight - tLeft)) * 6f).toInt().coerceIn(0, 5)
            val gr = (((ball.normY - tTop) / (tBottom - tTop)) * 6f).toInt().coerceIn(0, 5)
            grid[gr * 6 + gc] = 0.72f
        }
        if (cueBall != null) {
            val gc = (((cueBall.normX - tLeft) / (tRight - tLeft)) * 6f).toInt().coerceIn(0, 5)
            val gr = (((cueBall.normY - tTop) / (tBottom - tTop)) * 6f).toInt().coerceIn(0, 5)
            grid[gr * 6 + gc] = 1.0f
        }
        return grid
    }
}
