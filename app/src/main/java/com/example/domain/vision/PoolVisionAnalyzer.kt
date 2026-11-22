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
            PoolPocket("TL", tLeft + 0.030f, tTop + 0.040f),
            PoolPocket("TM", (tLeft + tRight) * 0.5f, tTop + 0.032f),
            PoolPocket("TR", tRight - 0.030f, tTop + 0.040f),
            PoolPocket("BL", tLeft + 0.030f, tBottom - 0.040f),
            PoolPocket("BM", (tLeft + tRight) * 0.5f, tBottom - 0.032f),
            PoolPocket("BR", tRight - 0.030f, tBottom - 0.040f)
        )

        // 1. Check green turn glow around player avatar vs opponent avatar + left power bar presence
        val leftGreenRatio = measureGreenBorderRatio(bitmap, profile.turnBoxX, profile.turnBoxY)
        val opponentTurnX = (1.0f - profile.turnBoxX).coerceIn(0.50f, 0.85f)
        val rightGreenRatio = measureGreenBorderRatio(bitmap, opponentTurnX, profile.turnBoxY)
        val powerBarVisible = measurePowerBarVisible(bitmap, profile.powerBarX)
        val isMyTurn = (leftGreenRatio >= 0.045f && leftGreenRatio >= rightGreenRatio * 1.35f) ||
            (leftGreenRatio >= 0.035f && powerBarVisible && rightGreenRatio < 0.03f)

        // 2. Measure potted balls indicator bar saturation/brightness
        val pottedRatio = measurePottedSlotsRatio(
            bitmap,
            profile.pottedBarLeft,
            profile.pottedBarRight,
            profile.pottedBarY
        )

        // Inner felt boundaries (exclude wooden rails & outer pockets)
        val fLeft = (tLeft + 0.032f).coerceAtMost(tRight - 0.20f)
        val fRight = (tRight - 0.032f).coerceAtLeast(fLeft + 0.20f)
        val fTop = (tTop + 0.042f).coerceAtMost(tBottom - 0.20f)
        val fBottom = (tBottom - 0.042f).coerceAtLeast(fTop + 0.20f)

        // 3. Estimate table felt color using median of 9 interior table points
        val feltHsv = estimateFeltColorHsv(bitmap, fLeft, fTop, fRight, fBottom)

        // 4. Scan interior felt on a 96x54 grid (handles 20:9 / 16:9 mobile screens accurately)
        val cols = 96
        val rows = 54
        val totalCells = cols * rows
        val cellColors = IntArray(totalCells)
        val cellHue = FloatArray(totalCells)
        val cellSat = FloatArray(totalCells)
        val cellLum = FloatArray(totalCells)
        val isWhiteCore = BooleanArray(totalCells)
        val isBallCell = BooleanArray(totalCells)

        val hsv = FloatArray(3)
        for (r in 0 until rows) {
            val ny = fTop + ((r + 0.5f) / rows) * (fBottom - fTop)
            val py = (ny * h).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val nx = fLeft + ((c + 0.5f) / cols) * (fRight - fLeft)
                val px = (nx * w).toInt().coerceIn(0, w - 1)
                val pixel = bitmap.getPixel(px, py)
                Color.colorToHSV(pixel, hsv)
                val idx = r * cols + c
                cellColors[idx] = pixel
                cellHue[idx] = hsv[0]
                cellSat[idx] = hsv[1]
                cellLum[idx] = hsv[2]

                // Skip pocket dark circles at corners/mid-rails
                val nearPocket = pockets.any { hypot(nx - it.normX, ny - it.normY) < 0.036f }
                if (nearPocket) continue

                val hueDiff = abs(hsv[0] - feltHsv[0]).let { minOf(it, 360f - it) }
                val valDiff = abs(hsv[2] - feltHsv[2])
                val satDiff = abs(hsv[1] - feltHsv[1])

                val isWhite = hsv[2] > 0.72f && hsv[1] < 0.28f
                isWhiteCore[idx] = isWhite

                // Differ significantly from table felt (saturated ball, bright stripe/white, or very dark 8-ball)
                val differsFromFelt = hueDiff > 22f || satDiff > 0.24f || valDiff > 0.24f
                isBallCell[idx] = differsFromFelt
            }
        }

        // 5. Dedicated Cue-Ball sliding 3x3 white-density kernel
        // Immune to wooden cue stick shafts or white aiming lines touching the cue ball!
        val cueBall = detectCueBallByWhiteKernel(
            cols = cols,
            rows = rows,
            isWhiteCore = isWhiteCore,
            cellColors = cellColors,
            cellLum = cellLum,
            cellSat = cellSat,
            fLeft = fLeft,
            fTop = fTop,
            fRight = fRight,
            fBottom = fBottom
        )

        // Mask out a small radius around the detected cue ball so BFS clusters focus on colored targets & 8-ball
        val visited = BooleanArray(totalCells)
        if (cueBall != null) {
            for (r in 0 until rows) {
                val ny = fTop + ((r + 0.5f) / rows) * (fBottom - fTop)
                for (c in 0 until cols) {
                    val nx = fLeft + ((c + 0.5f) / cols) * (fRight - fLeft)
                    if (hypot(nx - cueBall.normX, ny - cueBall.normY) < 0.028f) {
                        visited[r * cols + c] = true
                    }
                }
            }
        }

        // Suppress isolated thin 1px aiming/stick lines (cells without neighboring thick support)
        val filteredBallCell = BooleanArray(totalCells)
        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                val idx = r * cols + c
                if (!isBallCell[idx] || visited[idx]) continue
                var neighbors = 0
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if ((dr != 0 || dc != 0) && isBallCell[(r + dr) * cols + (c + dc)]) {
                            neighbors++
                        }
                    }
                }
                // Balls are solid round blobs (at least 3 of 8 neighbors), whereas cue stick lines have 1-2
                // Also prefer higher color saturation or very dark 8-ball over tan cue stick wood
                val vividOrDark = cellSat[idx] > 0.34f || cellLum[idx] < 0.22f || cellLum[idx] > 0.78f
                if (neighbors >= 3 && vividOrDark) {
                    filteredBallCell[idx] = true
                }
            }
        }

        val detectedTargets = mutableListOf<DetectedBall>()
        val queue = IntArray(totalCells)

        for (startIdx in filteredBallCell.indices) {
            if (!filteredBallCell[startIdx] || visited[startIdx]) continue
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
                if (cellSat[curr] > 0.35f && cellLum[curr] > maxLum) {
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
                            if (filteredBallCell[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }
            }

            val spanR = maxR - minR + 1
            val spanC = maxC - minC + 1
            val aspectOk = maxOf(spanR, spanC).toFloat() / minOf(spanR, spanC).coerceAtLeast(1) < 3.0f
            if (count in 3..48 && spanR <= 9 && spanC <= 9 && aspectOk) {
                val centerCol = sumC / count
                val centerRow = sumR / count
                val normX = fLeft + ((centerCol + 0.5f) / cols) * (fRight - fLeft)
                val normY = fTop + ((centerRow + 0.5f) / rows) * (fBottom - fTop)

                // Deduplicate close clusters (e.g. half of striped ball)
                val tooCloseToCue = cueBall != null && hypot(normX - cueBall.normX, normY - cueBall.normY) < 0.025f
                val tooCloseToExisting = detectedTargets.any { hypot(normX - it.normX, normY - it.normY) < 0.024f }
                if (!tooCloseToCue && !tooCloseToExisting) {
                    val mLum = avgLum / count
                    val mSat = avgSat / count
                    val kind = when {
                        mLum < 0.23f && mSat < 0.45f -> DetectedBallKind.EIGHT_BALL
                        else -> DetectedBallKind.TARGET
                    }
                    detectedTargets.add(DetectedBall(normX, normY, kind, repColor))
                }
            }
        }

        val eightBall = detectedTargets.firstOrNull { it.kind == DetectedBallKind.EIGHT_BALL }
        val targets = detectedTargets.filter { it.kind == DetectedBallKind.TARGET }
        val resolvedTargets = targets.ifEmpty { listOfNotNull(eightBall) }

        val planned = computeBestShot(cueBall, resolvedTargets, pockets, fLeft, fTop, fRight, fBottom)

        // Build 6x6 vision radar grid from table cells
        val visionGrid = build6x6VisionGrid(bitmap, fLeft, fTop, fRight, fBottom, cueBall, targets)

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
            greenTurnRatio = leftGreenRatio,
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

    private fun detectCueBallByWhiteKernel(
        cols: Int,
        rows: Int,
        isWhiteCore: BooleanArray,
        cellColors: IntArray,
        cellLum: FloatArray,
        cellSat: FloatArray,
        fLeft: Float,
        fTop: Float,
        fRight: Float,
        fBottom: Float
    ): DetectedBall? {
        var bestScore = -1f
        var bestR = -1
        var bestC = -1
        var bestColor = Color.WHITE

        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                val centerIdx = r * cols + c
                if (!isWhiteCore[centerIdx]) continue

                var whiteNeighbors = 0
                var lumSum = 0f
                var satSum = 0f
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val nIdx = (r + dr) * cols + (c + dc)
                        if (isWhiteCore[nIdx]) {
                            whiteNeighbors++
                            lumSum += cellLum[nIdx]
                            satSum += cellSat[nIdx]
                        }
                    }
                }
                // A solid white cue ball covers >= 5 cells of a 3x3 patch on 96x54 grid;
                // thin white aiming guidelines cover only 2-3 cells along 1 line.
                if (whiteNeighbors >= 4) {
                    val avgLum = lumSum / whiteNeighbors
                    val avgSat = satSum / whiteNeighbors
                    val score = whiteNeighbors * 2.2f + avgLum * 3.0f - avgSat * 4.0f
                    if (score > bestScore) {
                        bestScore = score
                        bestR = r
                        bestC = c
                        bestColor = cellColors[centerIdx]
                    }
                }
            }
        }

        if (bestR < 0) return null

        // Refine center over immediate 3x3 white pixels around peak
        var sumR = 0f
        var sumC = 0f
        var count = 0
        for (dr in -2..2) {
            for (dc in -2..2) {
                val nr = bestR + dr
                val nc = bestC + dc
                if (nr in 0 until rows && nc in 0 until cols && isWhiteCore[nr * cols + nc]) {
                    sumR += nr
                    sumC += nc
                    count++
                }
            }
        }
        val finalR = if (count > 0) sumR / count else bestR.toFloat()
        val finalC = if (count > 0) sumC / count else bestC.toFloat()
        val normX = fLeft + ((finalC + 0.5f) / cols) * (fRight - fLeft)
        val normY = fTop + ((finalR + 0.5f) / rows) * (fBottom - fTop)
        return DetectedBall(normX, normY, DetectedBallKind.CUE, bestColor)
    }

    fun computeAimPointWithTrim(shot: PlannedPoolShot, angleTrimDeg: Float): Pair<Float, Float> {
        val dx = shot.aimPointX - shot.cueBall.normX
        val dy = shot.aimPointY - shot.cueBall.normY
        val rad = angleTrimDeg * (PI / 180.0)
        val rx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ry = (dx * sin(rad) + dy * cos(rad)).toFloat()
        return (shot.cueBall.normX + rx).coerceIn(0.18f, 0.82f) to
            (shot.cueBall.normY + ry).coerceIn(0.20f, 0.92f)
    }

    private fun computeBestShot(
        cue: DetectedBall?,
        targets: List<DetectedBall>,
        pockets: List<PoolPocket>,
        fLeft: Float,
        fTop: Float,
        fRight: Float,
        fBottom: Float
    ): PlannedPoolShot? {
        if (cue == null || targets.isEmpty()) return null

        var bestScore = Float.NEGATIVE_INFINITY
        var bestShot: PlannedPoolShot? = null
        val ballRadiusNorm = 0.017f

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

                val dot = ((ctDx / ctDist) * (tpDx / tpDist) + (ctDy / ctDist) * (tpDy / tpDist)).coerceIn(-1f, 1f)
                val cutAngleRad = kotlin.math.acos(dot)
                val cutAngleDeg = (cutAngleRad * 180.0 / PI).toFloat()

                if (cutAngleDeg > 68f) continue

                val ghostX = target.normX - (tpDx / tpDist) * (ballRadiusNorm * 1.9f)
                val ghostY = target.normY - (tpDy / tpDist) * (ballRadiusNorm * 1.9f)

                val totalDist = ctDist + tpDist
                val geomScore = (90f - cutAngleDeg) * 1.4f - totalDist * 45f

                if (geomScore > bestScore) {
                    bestScore = geomScore
                    val angleBucket = (cutAngleDeg / 15f).toInt().coerceIn(0, 4)
                    val distBucket = (totalDist * 5f).toInt().coerceIn(0, 4)
                    val basePower = (0.42f + totalDist * 0.55f + (cutAngleDeg / 180f) * 0.15f).coerceIn(0.35f, 0.94f)
                    val stateHash = "POOL_A${angleBucket}_D${distBucket}_${pocket.id}"
                    bestShot = PlannedPoolShot(
                        cueBall = cue,
                        targetBall = target,
                        pocket = pocket,
                        aimPointX = ghostX.coerceIn(fLeft, fRight),
                        aimPointY = ghostY.coerceIn(fTop, fBottom),
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
        val rx = (0.040f * w).toInt().coerceAtLeast(8)
        val ry = (0.060f * h).toInt().coerceAtLeast(8)
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
                if (g > 135 && g > r + 40 && g > b + 40) {
                    greenCount++
                }
                total++
            }
        }
        return if (total > 0) greenCount.toFloat() / total else 0f
    }

    private fun measurePowerBarVisible(bitmap: Bitmap, powerBarX: Float): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val px = (powerBarX * w).toInt().coerceIn(0, w - 1)
        var brightWoodPixels = 0
        val samples = 10
        val hsv = FloatArray(3)
        for (i in 0 until samples) {
            val ny = 0.34f + (i.toFloat() / (samples - 1)) * 0.44f
            val py = (ny * h).toInt().coerceIn(0, h - 1)
            Color.colorToHSV(bitmap.getPixel(px, py), hsv)
            // Cue handle inside left slider has warm cream/brown brightness vs dark empty blue slot
            if (hsv[2] > 0.36f && hsv[0] in 15f..65f) {
                brightWoodPixels++
            }
        }
        return brightWoodPixels >= 4
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
