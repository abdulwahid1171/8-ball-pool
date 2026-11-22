package com.example.domain.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.db.TrainingProfileEntity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class DetectedBallKind { CUE, TARGET, EIGHT_BALL }

enum class BallSuit { UNKNOWN, SOLID, STRIPE, EIGHT_BALL }

enum class AssignedSuit { OPEN, SOLIDS, STRIPES, EIGHT_BALL }

enum class TurnOwner { LEFT_PLAYER, RIGHT_PLAYER, NONE }

data class DetectedBall(
    val normX: Float, // normalized 0..1 across full screen
    val normY: Float, // normalized 0..1 across full screen
    val kind: DetectedBallKind,
    val sampleColor: Int,
    val suit: BallSuit = BallSuit.UNKNOWN
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
    val stateSummary: String,
    val isBankShot: Boolean = false
)

data class PoolFrameAnalysis(
    val isMyTurn: Boolean,
    val turnOwner: TurnOwner,
    val leftGreenRatio: Float,
    val rightGreenRatio: Float,
    val powerBarVisible: Boolean,
    val greenTurnRatio: Float,
    val pottedOccupiedRatio: Float,
    val detectedSuit: AssignedSuit,
    val effectiveSuit: AssignedSuit,
    val cueBall: DetectedBall?,
    val targetBalls: List<DetectedBall>,
    val eightBall: DetectedBall?,
    val pockets: List<PoolPocket>,
    val plannedShot: PlannedPoolShot?,
    val visionGrid: List<Float>,
    val summary: String,
    val detectedTableBounds: List<Float> = listOf(0.192f, 0.222f, 0.798f, 0.908f),
    val aspectRatio: Float = 2.0f
) {
    val allTableTargets: List<DetectedBall>
        get() = targetBalls + listOfNotNull(eightBall)
}

/**
 * Computer-vision analyzer for Pool / Billiards mobile game screenshots and
 * live MediaProjection frames.
 * - Visually detects the inner felt borders (cushion rails) & snaps the 6 black pocket mouths.
 * - Performs all shot vectors, ghost-ball offsets, pocket jaw cones, and cushion bank shots
 *   in aspect-corrected isotropic coordinates so diagonal and vertical shots are accurate.
 * - Classifies Solids vs Stripes vs 8-Ball and Left/Right turn ownership.
 */
object PoolVisionAnalyzer {

    private const val BALL_RADIUS_ISO = 0.017f

    private data class RackScanResult(
        val occupiedSlots: Int,
        val isStripesScore: Float,
        val occupiedRatio: Float
    )

    fun analyze(
        bitmap: Bitmap,
        profile: TrainingProfileEntity,
        seatOverride: String? = null,
        suitOverride: String? = null,
        failedShotPenalties: Map<String, Float> = emptyMap()
    ): PoolFrameAnalysis {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val aspectRatio = (w.toFloat() / h.toFloat()).coerceIn(0.45f, 2.50f)

        val tLeft = profile.tableLeft.coerceIn(0.05f, 0.45f)
        val tTop = profile.tableTop.coerceIn(0.05f, 0.45f)
        val tRight = profile.tableRight.coerceIn(tLeft + 0.25f, 0.96f)
        val tBottom = profile.tableBottom.coerceIn(tTop + 0.25f, 0.98f)

        // 1. Strict Left vs Right Player Turn Ownership
        val leftGreenRatio = measureGreenBorderRatio(bitmap, profile.turnBoxX, profile.turnBoxY)
        val opponentTurnX = (1.0f - profile.turnBoxX).coerceIn(0.50f, 0.85f)
        val rightGreenRatio = measureGreenBorderRatio(bitmap, opponentTurnX, profile.turnBoxY)
        val powerBarVisible = measurePowerBarVisible(bitmap, profile.powerBarX)

        val turnOwner = when {
            leftGreenRatio >= 0.022f && leftGreenRatio >= rightGreenRatio * 1.28f -> TurnOwner.LEFT_PLAYER
            rightGreenRatio >= 0.022f && rightGreenRatio >= leftGreenRatio * 1.28f -> TurnOwner.RIGHT_PLAYER
            else -> TurnOwner.NONE
        }

        val effectiveSeat = (seatOverride ?: profile.botSeatSide).uppercase()
        val isMyTurn = when (effectiveSeat) {
            "LEFT" -> turnOwner == TurnOwner.LEFT_PLAYER
            "RIGHT" -> turnOwner == TurnOwner.RIGHT_PLAYER
            else -> false
        }

        // 2. Scan Left & Right Top HUD Racks to classify assigned suit
        val leftRack = scanHudRack(
            bitmap = bitmap,
            leftX = profile.pottedBarLeft,
            rightX = profile.pottedBarRight,
            centerY = profile.pottedBarY
        )
        val oppLeftX = (1.0f - profile.pottedBarRight).coerceIn(0.55f, 0.72f)
        val oppRightX = (1.0f - profile.pottedBarLeft).coerceIn(0.70f, 0.90f)
        val rightRack = scanHudRack(
            bitmap = bitmap,
            leftX = oppLeftX,
            rightX = oppRightX,
            centerY = profile.pottedBarY
        )

        val botRack = if (effectiveSeat == "RIGHT") rightRack else leftRack
        val oppRack = if (effectiveSeat == "RIGHT") leftRack else rightRack
        val detectedSuit = inferAssignedSuit(botRack, oppRack)

        val suitChoice = (suitOverride ?: profile.preferredSuit).uppercase()
        val effectiveSuit = when (suitChoice) {
            "SOLIDS" -> if (detectedSuit == AssignedSuit.EIGHT_BALL) AssignedSuit.EIGHT_BALL else AssignedSuit.SOLIDS
            "STRIPES" -> if (detectedSuit == AssignedSuit.EIGHT_BALL) AssignedSuit.EIGHT_BALL else AssignedSuit.STRIPES
            else -> detectedSuit
        }

        val greenTurnRatio = if (effectiveSeat == "RIGHT") rightGreenRatio else leftGreenRatio
        val pottedRatio = botRack.occupiedRatio

        // 3. Estimate table felt color & visually detect inner cushion borders + 6 black pocket holes
        val seedFLeft = (tLeft + 0.032f).coerceAtMost(tRight - 0.20f)
        val seedFRight = (tRight - 0.032f).coerceAtLeast(seedFLeft + 0.20f)
        val seedFTop = (tTop + 0.042f).coerceAtMost(tBottom - 0.20f)
        val seedFBottom = (tBottom - 0.042f).coerceAtLeast(seedFTop + 0.20f)

        val feltHsv = estimateFeltColorHsv(bitmap, seedFLeft, seedFTop, seedFRight, seedFBottom)
        val (fLeft, fTop, fRight, fBottom) = detectInnerFeltBorders(
            bitmap = bitmap,
            feltHsv = feltHsv,
            seedLeft = seedFLeft,
            seedTop = seedFTop,
            seedRight = seedFRight,
            seedBottom = seedFBottom
        )

        val pockets = detectSixPockets(bitmap, fLeft, fTop, fRight, fBottom)

        // 4. Scan interior felt on a 96x54 grid
        val cols = 96
        val rows = 54
        val totalCells = cols * rows
        val cellColors = IntArray(totalCells)
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
                cellSat[idx] = hsv[1]
                cellLum[idx] = hsv[2]

                // Exclude pixels inside visually localized dark pocket holes
                val nearPocket = pockets.any { isoDist(nx, ny, it.normX, it.normY, aspectRatio) < 0.042f }
                if (nearPocket) continue

                val hueDiff = abs(hsv[0] - feltHsv[0]).let { minOf(it, 360f - it) }
                val valDiff = abs(hsv[2] - feltHsv[2])
                val satDiff = abs(hsv[1] - feltHsv[1])

                val isWhite = hsv[2] > 0.70f && hsv[1] < 0.28f
                isWhiteCore[idx] = isWhite

                val differsFromFelt = hueDiff > 22f || satDiff > 0.24f || valDiff > 0.24f
                isBallCell[idx] = differsFromFelt
            }
        }

        // 5. Dedicated Cue-Ball sliding 3x3 white-density kernel
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

        // Mask out ONLY the cue ball in true isotropic circle
        val visited = BooleanArray(totalCells)
        if (cueBall != null) {
            for (r in 0 until rows) {
                val ny = fTop + ((r + 0.5f) / rows) * (fBottom - fTop)
                for (c in 0 until cols) {
                    val nx = fLeft + ((c + 0.5f) / cols) * (fRight - fLeft)
                    if (isoDist(nx, ny, cueBall.normX, cueBall.normY, aspectRatio) < 0.032f) {
                        visited[r * cols + c] = true
                    }
                }
            }
        }

        // Preserve both vivid colored regions AND bright white stripe bands
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
                val isStripeWhite = cellLum[idx] > 0.58f && cellSat[idx] < 0.28f
                val vividOrDarkOrStripe = cellSat[idx] > 0.34f || cellLum[idx] < 0.22f || isStripeWhite
                if (neighbors >= 3 && vividOrDarkOrStripe) {
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
            var whiteCells = 0
            var darkCells = 0
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
                val lum = cellLum[curr]
                val sat = cellSat[curr]
                avgLum += lum
                avgSat += sat
                if (lum > 0.58f && sat < 0.28f) whiteCells++
                if (lum < 0.24f && sat < 0.45f) darkCells++
                if (sat > 0.35f && lum > maxLum) {
                    maxLum = lum
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
            if (count in 3..52 && spanR <= 9 && spanC <= 9 && aspectOk) {
                val centerCol = sumC / count
                val centerRow = sumR / count
                val normX = fLeft + ((centerCol + 0.5f) / cols) * (fRight - fLeft)
                val normY = fTop + ((centerRow + 0.5f) / rows) * (fBottom - fTop)

                val tooCloseToCue = cueBall != null && isoDist(normX, normY, cueBall.normX, cueBall.normY, aspectRatio) < 0.030f
                val tooCloseToExisting = detectedTargets.any { isoDist(normX, normY, it.normX, it.normY, aspectRatio) < 0.028f }
                if (!tooCloseToCue && !tooCloseToExisting) {
                    val mLum = avgLum / count
                    val mSat = avgSat / count
                    val whiteFrac = whiteCells.toFloat() / count
                    val darkFrac = darkCells.toFloat() / count

                    val (kind, suit) = when {
                        darkFrac >= 0.48f || (mLum < 0.23f && mSat < 0.45f && whiteCells == 0) ->
                            DetectedBallKind.EIGHT_BALL to BallSuit.EIGHT_BALL
                        whiteFrac >= 0.22f || (whiteCells >= 2 && whiteFrac >= 0.16f && mSat < 0.72f) ->
                            DetectedBallKind.TARGET to BallSuit.STRIPE
                        else ->
                            DetectedBallKind.TARGET to BallSuit.SOLID
                    }
                    detectedTargets.add(DetectedBall(normX, normY, kind, repColor, suit))
                }
            }
        }

        val eightBall = detectedTargets.firstOrNull { it.kind == DetectedBallKind.EIGHT_BALL }
        val allColoredTargets = detectedTargets.filter { it.kind == DetectedBallKind.TARGET }
        val legalTargets = filterLegalTargets(
            effectiveSuit = effectiveSuit,
            targets = allColoredTargets,
            eightBall = eightBall
        )
        val obstacleBalls = allColoredTargets + listOfNotNull(eightBall)

        val planned = computeBestShot(
            cue = cueBall,
            targets = legalTargets,
            pockets = pockets,
            fLeft = fLeft,
            fTop = fTop,
            fRight = fRight,
            fBottom = fBottom,
            allObstacles = obstacleBalls,
            aspectRatio = aspectRatio,
            failedShotPenalties = failedShotPenalties
        )

        val visionGrid = build6x6VisionGrid(bitmap, fLeft, fTop, fRight, fBottom, cueBall, allColoredTargets)

        val solidsCount = allColoredTargets.count { it.suit == BallSuit.SOLID }
        val stripesCount = allColoredTargets.count { it.suit == BallSuit.STRIPE }
        val turnTag = when {
            effectiveSeat == "WATCH" -> "WATCH(${turnOwner.name.take(1)})"
            isMyTurn -> "BOT TURN"
            turnOwner != TurnOwner.NONE -> "HUMAN TURN"
            else -> "WAIT"
        }

        val summary = buildString {
            append(turnTag)
            append(" • ${effectiveSuit.name}")
            append(" | Cue:${if (cueBall != null) "OK" else "N/A"}")
            append(" | S:$solidsCount St:$stripesCount")
            if (planned != null) {
                val bankTag = if (planned.isBankShot) " [BANK]" else ""
                append(" | ${planned.targetBall.suit.name.take(3)}->${planned.pocket.id} (${planned.cutAngleDeg.toInt()}°)$bankTag")
            }
        }

        return PoolFrameAnalysis(
            isMyTurn = isMyTurn,
            turnOwner = turnOwner,
            leftGreenRatio = leftGreenRatio,
            rightGreenRatio = rightGreenRatio,
            powerBarVisible = powerBarVisible,
            greenTurnRatio = greenTurnRatio,
            pottedOccupiedRatio = pottedRatio,
            detectedSuit = detectedSuit,
            effectiveSuit = effectiveSuit,
            cueBall = cueBall,
            targetBalls = allColoredTargets,
            eightBall = eightBall,
            pockets = pockets,
            plannedShot = planned,
            visionGrid = visionGrid,
            summary = summary,
            detectedTableBounds = listOf(fLeft, fTop, fRight, fBottom),
            aspectRatio = aspectRatio
        )
    }

    private fun detectInnerFeltBorders(
        bitmap: Bitmap,
        feltHsv: FloatArray,
        seedLeft: Float,
        seedTop: Float,
        seedRight: Float,
        seedBottom: Float
    ): List<Float> {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)

        fun isFeltPixel(nx: Float, ny: Float): Boolean {
            val px = (nx * w).toInt().coerceIn(0, w - 1)
            val py = (ny * h).toInt().coerceIn(0, h - 1)
            Color.colorToHSV(bitmap.getPixel(px, py), hsv)
            val hueDiff = abs(hsv[0] - feltHsv[0]).let { minOf(it, 360f - it) }
            val satDiff = abs(hsv[1] - feltHsv[1])
            val valDiff = abs(hsv[2] - feltHsv[2])
            return hueDiff < 26f && satDiff < 0.30f && valDiff < 0.30f
        }

        val midX = (seedLeft + seedRight) * 0.5f
        val midY = (seedTop + seedBottom) * 0.5f

        fun scanEdgeX(startX: Float, endX: Float, step: Float): Float? {
            val ys = listOf(0.35f, 0.45f, 0.56f, 0.67f, 0.78f)
            val hits = mutableListOf<Float>()
            for (y in ys) {
                var x = startX
                var nonFeltRun = 0
                while (if (step > 0) x <= endX else x >= endX) {
                    if (!isFeltPixel(x, y)) {
                        nonFeltRun++
                        if (nonFeltRun >= 2) {
                            hits.add(x - step)
                            break
                        }
                    } else {
                        nonFeltRun = 0
                    }
                    x += step
                }
            }
            if (hits.size < 3) return null
            hits.sort()
            return hits[hits.size / 2]
        }

        fun scanEdgeY(startY: Float, endY: Float, step: Float): Float? {
            val xs = listOf(0.32f, 0.42f, 0.50f, 0.58f, 0.68f)
            val hits = mutableListOf<Float>()
            for (x in xs) {
                var y = startY
                var nonFeltRun = 0
                while (if (step > 0) y <= endY else y >= endY) {
                    if (!isFeltPixel(x, y)) {
                        nonFeltRun++
                        if (nonFeltRun >= 2) {
                            hits.add(y - step)
                            break
                        }
                    } else {
                        nonFeltRun = 0
                    }
                    y += step
                }
            }
            if (hits.size < 3) return null
            hits.sort()
            return hits[hits.size / 2]
        }

        val detLeft = scanEdgeX(midX - 0.14f, (seedLeft - 0.04f).coerceAtLeast(0.08f), -0.004f)
            ?.coerceIn(seedLeft - 0.035f, seedLeft + 0.035f) ?: seedLeft
        val detRight = scanEdgeX(midX + 0.14f, (seedRight + 0.04f).coerceAtMost(0.92f), +0.004f)
            ?.coerceIn(seedRight - 0.035f, seedRight + 0.035f) ?: seedRight
        val detTop = scanEdgeY(midY - 0.14f, (seedTop - 0.04f).coerceAtLeast(0.12f), -0.005f)
            ?.coerceIn(seedTop - 0.035f, seedTop + 0.035f) ?: seedTop
        val detBottom = scanEdgeY(midY + 0.14f, (seedBottom + 0.04f).coerceAtMost(0.97f), +0.005f)
            ?.coerceIn(seedBottom - 0.035f, seedBottom + 0.035f) ?: seedBottom

        return listOf(
            detLeft.coerceAtMost(detRight - 0.25f),
            detTop.coerceAtMost(detBottom - 0.25f),
            detRight.coerceAtLeast(detLeft + 0.25f),
            detBottom.coerceAtLeast(detTop + 0.25f)
        )
    }

    private fun detectSixPockets(
        bitmap: Bitmap,
        fLeft: Float,
        fTop: Float,
        fRight: Float,
        fBottom: Float
    ): List<PoolPocket> {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)
        val seeds = listOf(
            PoolPocket("TL", fLeft + 0.006f, fTop + 0.010f),
            PoolPocket("TM", (fLeft + fRight) * 0.5f, fTop - 0.004f),
            PoolPocket("TR", fRight - 0.006f, fTop + 0.010f),
            PoolPocket("BL", fLeft + 0.006f, fBottom - 0.010f),
            PoolPocket("BM", (fLeft + fRight) * 0.5f, fBottom + 0.004f),
            PoolPocket("BR", fRight - 0.006f, fBottom - 0.010f)
        )

        return seeds.map { seed ->
            var sumX = 0f
            var sumY = 0f
            var darkCount = 0
            for (dyIdx in -4..4) {
                val ny = (seed.normY + dyIdx * 0.005f).coerceIn(0.05f, 0.98f)
                val py = (ny * h).toInt().coerceIn(0, h - 1)
                for (dxIdx in -4..4) {
                    val nx = (seed.normX + dxIdx * 0.003f).coerceIn(0.05f, 0.96f)
                    val px = (nx * w).toInt().coerceIn(0, w - 1)
                    Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                    if (hsv[2] < 0.16f && hsv[1] < 0.55f) {
                        sumX += nx
                        sumY += ny
                        darkCount++
                    }
                }
            }
            if (darkCount >= 5) {
                PoolPocket(seed.id, sumX / darkCount, sumY / darkCount)
            } else {
                seed
            }
        }
    }

    fun filterLegalTargets(
        effectiveSuit: AssignedSuit,
        targets: List<DetectedBall>,
        eightBall: DetectedBall?
    ): List<DetectedBall> {
        val solids = targets.filter { it.suit == BallSuit.SOLID }
        val stripes = targets.filter { it.suit == BallSuit.STRIPE }
        return when (effectiveSuit) {
            AssignedSuit.SOLIDS -> {
                solids.ifEmpty {
                    if (stripes.isEmpty()) listOfNotNull(eightBall) else emptyList()
                }
            }
            AssignedSuit.STRIPES -> {
                stripes.ifEmpty {
                    if (solids.isEmpty()) listOfNotNull(eightBall) else emptyList()
                }
            }
            AssignedSuit.EIGHT_BALL -> listOfNotNull(eightBall)
            AssignedSuit.OPEN -> targets.ifEmpty { listOfNotNull(eightBall) }
        }
    }

    private fun inferAssignedSuit(botRack: RackScanResult, oppRack: RackScanResult): AssignedSuit {
        if (botRack.occupiedSlots == 0 && oppRack.occupiedSlots == 0) {
            return AssignedSuit.OPEN
        }
        if (botRack.occupiedSlots == 0 && oppRack.occupiedSlots >= 1) {
            return AssignedSuit.EIGHT_BALL
        }
        val diff = botRack.isStripesScore - oppRack.isStripesScore
        return when {
            botRack.isStripesScore >= 0.35f || diff >= 0.25f -> AssignedSuit.STRIPES
            botRack.isStripesScore <= -0.35f || diff <= -0.25f -> AssignedSuit.SOLIDS
            botRack.isStripesScore >= 0f -> AssignedSuit.STRIPES
            else -> AssignedSuit.SOLIDS
        }
    }

    private fun scanHudRack(
        bitmap: Bitmap,
        leftX: Float,
        rightX: Float,
        centerY: Float
    ): RackScanResult {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)
        var occupied = 0
        var stripeVotes = 0f

        val slotStart = (leftX + 0.008f).coerceIn(0.05f, 0.90f)
        val slotEnd = (rightX - 0.008f).coerceIn(slotStart + 0.05f, 0.95f)

        for (slot in 0 until 7) {
            val sx = slotStart + (slot / 6f) * (slotEnd - slotStart)
            var vividPixels = 0
            var outerWhitePixels = 0
            var outerColorPixels = 0
            var brightPixels = 0

            for (dyIdx in -2..2) {
                val dy = dyIdx * 0.0045f
                val py = ((centerY + dy) * h).toInt().coerceIn(0, h - 1)
                for (dxIdx in -2..2) {
                    val dx = dxIdx * 0.0032f
                    val px = ((sx + dx) * w).toInt().coerceIn(0, w - 1)
                    Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                    val s = hsv[1]
                    val v = hsv[2]
                    if (v > 0.30f) brightPixels++
                    if (s > 0.35f && v > 0.22f) vividPixels++
                    if (abs(dyIdx) == 2) {
                        if (s < 0.30f && v > 0.44f) outerWhitePixels++
                        if (s > 0.38f && v > 0.20f) outerColorPixels++
                    }
                }
            }

            if (vividPixels >= 4 || brightPixels >= 6) {
                occupied++
                if (outerWhitePixels >= 2 && outerWhitePixels >= outerColorPixels) {
                    stripeVotes += 1f
                } else if (outerColorPixels > outerWhitePixels + 1) {
                    stripeVotes -= 1f
                }
            }
        }

        val normScore = if (occupied > 0) stripeVotes / occupied else 0f
        return RackScanResult(
            occupiedSlots = occupied,
            isStripesScore = normScore,
            occupiedRatio = occupied / 7f
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
        return DetectedBall(normX, normY, DetectedBallKind.CUE, bestColor, BallSuit.UNKNOWN)
    }

    /**
     * Rotates the cue->aimPoint ray in true isotropic screen space by [angleTrimDeg] and
     * projects the aim tap out to a safe minimum distance along the ray so micro-pixel touch rounding
     * never causes huge angle error or clicks the cue-ball move handle.
     */
    fun computeAimPointWithTrim(
        shot: PlannedPoolShot,
        angleTrimDeg: Float,
        aspectRatio: Float = 2.0f,
        fLeft: Float = 0.18f,
        fTop: Float = 0.20f,
        fRight: Float = 0.82f,
        fBottom: Float = 0.92f
    ): Pair<Float, Float> {
        val isoDx = (shot.aimPointX - shot.cueBall.normX) * aspectRatio
        val isoDy = shot.aimPointY - shot.cueBall.normY
        val len = hypot(isoDx, isoDy).coerceAtLeast(0.01f)
        val rad = angleTrimDeg * (PI / 180.0)
        val rxIso = (isoDx * cos(rad) - isoDy * sin(rad)).toFloat()
        val ryIso = (isoDx * sin(rad) + isoDy * cos(rad)).toFloat()

        // Ensure tap is at least 0.12 isotropic units away along aim ray (or exact ghost if farther), clamped inside rails
        val targetRayDist = maxOf(len, 0.12f)
        val scale = targetRayDist / len
        val tapX = (shot.cueBall.normX + (rxIso * scale) / aspectRatio).coerceIn(fLeft + 0.01f, fRight - 0.01f)
        val tapY = (shot.cueBall.normY + (ryIso * scale)).coerceIn(fTop + 0.01f, fBottom - 0.01f)
        return tapX to tapY
    }

    fun computeBestShot(
        cue: DetectedBall?,
        targets: List<DetectedBall>,
        pockets: List<PoolPocket>,
        fLeft: Float,
        fTop: Float,
        fRight: Float,
        fBottom: Float,
        allObstacles: List<DetectedBall> = targets,
        aspectRatio: Float = 2.0f,
        failedShotPenalties: Map<String, Float> = emptyMap()
    ): PlannedPoolShot? {
        if (cue == null || targets.isEmpty()) return null

        var bestScore = Float.NEGATIVE_INFINITY
        var bestShot: PlannedPoolShot? = null

        val marginX = 0.008f / aspectRatio
        val marginY = 0.008f

        for (target in targets) {
            val ctIsoDx = (target.normX - cue.normX) * aspectRatio
            val ctIsoDy = target.normY - cue.normY
            val ctDist = hypot(ctIsoDx, ctIsoDy)
            if (ctDist < 0.028f) continue

            for (pocket in pockets) {
                val tpIsoDx = (pocket.normX - target.normX) * aspectRatio
                val tpIsoDy = pocket.normY - target.normY
                val tpDist = hypot(tpIsoDx, tpIsoDy)
                if (tpDist < 0.028f) continue

                val dot = ((ctIsoDx / ctDist) * (tpIsoDx / tpDist) + (ctIsoDy / ctDist) * (tpIsoDy / tpDist)).coerceIn(-1f, 1f)
                val cutAngleDeg = (acos(dot) * 180.0 / PI).toFloat()
                if (cutAngleDeg > 66f) continue

                // Pocket Jaw Acceptance Cone: prevent shallow rail shots that clip pocket knuckles
                val (jawX, jawY) = pocketEntryVector(pocket.id)
                val jawDot = ((tpIsoDx / tpDist) * jawX + (tpIsoDy / tpDist) * jawY).coerceIn(-1f, 1f)
                val jawAngleDeg = (acos(jawDot) * 180.0 / PI).toFloat()
                val maxJawDeg = if (pocket.id == "TM" || pocket.id == "BM") 50f else 58f
                if (jawAngleDeg > maxJawDeg) continue

                // Isotropic Ghost-Ball placement (diameter = 2 * BALL_RADIUS_ISO = 0.034f)
                val ghostIsoX = (target.normX * aspectRatio) - (tpIsoDx / tpDist) * (BALL_RADIUS_ISO * 2.0f)
                val ghostIsoY = target.normY - (tpIsoDy / tpDist) * (BALL_RADIUS_ISO * 2.0f)
                val ghostX = ghostIsoX / aspectRatio
                val ghostY = ghostIsoY

                // Reject ghost points buried inside or behind cushion rails
                if (ghostX < fLeft + marginX || ghostX > fRight - marginX ||
                    ghostY < fTop + marginY || ghostY > fBottom - marginY
                ) {
                    continue
                }

                // Obstacle ray check in true isotropic space
                var blockerPenalty = 0f
                for (obs in allObstacles) {
                    if (isoDist(obs.normX, obs.normY, target.normX, target.normY, aspectRatio) < 0.022f) continue
                    val distCuePath = pointToSegmentDistanceIso(
                        obs.normX, obs.normY,
                        cue.normX, cue.normY,
                        ghostX, ghostY,
                        aspectRatio
                    )
                    val distTargetPath = pointToSegmentDistanceIso(
                        obs.normX, obs.normY,
                        target.normX, target.normY,
                        pocket.normX, pocket.normY,
                        aspectRatio
                    )
                    if (distCuePath < BALL_RADIUS_ISO * 1.95f || distTargetPath < BALL_RADIUS_ISO * 1.95f) {
                        blockerPenalty += if (obs.kind == DetectedBallKind.EIGHT_BALL) 90f else 62f
                    }
                }

                // Scratch guard: avoid straight-in high-velocity follow where cue directly trails into pocket
                val scratchRiskPenalty = if (cutAngleDeg < 7f && abs(jawAngleDeg) < 12f && tpDist < 0.28f) 8f else 0f

                val angleBucket = (cutAngleDeg / 15f).toInt().coerceIn(0, 4)
                val distBucket = (ctDist + tpDist).let { (it * 3.5f).toInt().coerceIn(0, 4) }
                val stateHash = "POOL_A${angleBucket}_D${distBucket}_${pocket.id}"
                val priorFailPenalty = failedShotPenalties[stateHash] ?: 0f

                val totalDist = ctDist + tpDist
                val geomScore = (90f - cutAngleDeg) * 1.45f -
                    totalDist * 30f -
                    jawAngleDeg * 0.35f -
                    blockerPenalty -
                    scratchRiskPenalty -
                    priorFailPenalty

                if (geomScore > bestScore) {
                    bestScore = geomScore
                    val basePower = (0.38f + totalDist * 0.36f + (cutAngleDeg / 180f) * 0.14f).coerceIn(0.32f, 0.88f)
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
                        stateSummary = "Cut ${cutAngleDeg.toInt()}° | ${target.suit.name} | Dist ${"%.2f".format(totalDist)} -> ${pocket.id}",
                        isBankShot = false
                    )
                }
            }
        }

        // If all direct cuts were heavily blocked (geomScore < -20f) or null, evaluate 1-Cushion Bank Shots
        if (bestShot == null || bestScore < -15f) {
            val bank = computeOneCushionBankShot(
                cue = cue,
                targets = targets,
                pockets = pockets,
                fLeft = fLeft,
                fTop = fTop,
                fRight = fRight,
                fBottom = fBottom,
                allObstacles = allObstacles,
                aspectRatio = aspectRatio,
                failedShotPenalties = failedShotPenalties
            )
            if (bank != null && (bestShot == null || bestScore < -15f)) {
                return bank
            }
        }
        return bestShot
    }

    private fun computeOneCushionBankShot(
        cue: DetectedBall,
        targets: List<DetectedBall>,
        pockets: List<PoolPocket>,
        fLeft: Float,
        fTop: Float,
        fRight: Float,
        fBottom: Float,
        allObstacles: List<DetectedBall>,
        aspectRatio: Float,
        failedShotPenalties: Map<String, Float>
    ): PlannedPoolShot? {
        var bestScore = Float.NEGATIVE_INFINITY
        var bestBank: PlannedPoolShot? = null

        for (target in targets) {
            val ctIsoDx = (target.normX - cue.normX) * aspectRatio
            val ctIsoDy = target.normY - cue.normY
            val ctDist = hypot(ctIsoDx, ctIsoDy)
            if (ctDist < 0.03f) continue

            // Reflect pockets across Top and Bottom cushions (classic cross-table banks)
            val mirrorRails = listOf(
                Pair("TOP", fTop),
                Pair("BOT", fBottom)
            )
            for ((_, railY) in mirrorRails) {
                for (pocket in pockets) {
                    val mirroredY = 2f * railY - pocket.normY
                    val tpIsoDx = (pocket.normX - target.normX) * aspectRatio
                    val tpIsoDy = mirroredY - target.normY
                    val tpDist = hypot(tpIsoDx, tpIsoDy)
                    if (tpDist < 0.04f) continue

                    val dot = ((ctIsoDx / ctDist) * (tpIsoDx / tpDist) + (ctIsoDy / ctDist) * (tpIsoDy / tpDist)).coerceIn(-1f, 1f)
                    val cutAngleDeg = (acos(dot) * 180.0 / PI).toFloat()
                    if (cutAngleDeg > 38f) continue

                    val ghostX = ((target.normX * aspectRatio) - (tpIsoDx / tpDist) * (BALL_RADIUS_ISO * 2.0f)) / aspectRatio
                    val ghostY = target.normY - (tpIsoDy / tpDist) * (BALL_RADIUS_ISO * 2.0f)
                    if (ghostX !in fLeft..fRight || ghostY !in fTop..fBottom) continue

                    var blocked = false
                    for (obs in allObstacles) {
                        if (isoDist(obs.normX, obs.normY, target.normX, target.normY, aspectRatio) < 0.022f) continue
                        if (pointToSegmentDistanceIso(obs.normX, obs.normY, cue.normX, cue.normY, ghostX, ghostY, aspectRatio) < BALL_RADIUS_ISO * 1.9f) {
                            blocked = true
                            break
                        }
                    }
                    if (blocked) continue

                    val totalDist = ctDist + tpDist
                    val angleBucket = (cutAngleDeg / 15f).toInt().coerceIn(0, 4)
                    val distBucket = (totalDist * 3.5f).toInt().coerceIn(0, 4)
                    val stateHash = "POOL_A${angleBucket}_D${distBucket}_${pocket.id}"
                    val penalty = failedShotPenalties[stateHash] ?: 0f
                    val score = (80f - cutAngleDeg * 1.8f) - totalDist * 28f - penalty

                    if (score > bestScore) {
                        bestScore = score
                        bestBank = PlannedPoolShot(
                            cueBall = cue,
                            targetBall = target,
                            pocket = pocket,
                            aimPointX = ghostX,
                            aimPointY = ghostY,
                            cutAngleDeg = cutAngleDeg,
                            totalDistance = totalDist,
                            basePowerFraction = (0.50f + totalDist * 0.28f).coerceIn(0.45f, 0.90f),
                            stateHash = stateHash,
                            stateSummary = "Bank ${cutAngleDeg.toInt()}° | ${target.suit.name} -> ${pocket.id}",
                            isBankShot = true
                        )
                    }
                }
            }
        }
        return bestBank
    }

    private fun pocketEntryVector(pocketId: String): Pair<Float, Float> {
        val invSqrt2 = 0.70710677f
        return when (pocketId) {
            "TL" -> -invSqrt2 to -invSqrt2
            "TR" -> +invSqrt2 to -invSqrt2
            "BL" -> -invSqrt2 to +invSqrt2
            "BR" -> +invSqrt2 to +invSqrt2
            "TM" -> 0f to -1f
            "BM" -> 0f to +1f
            else -> 0f to 1f
        }
    }

    private fun isoDist(ax: Float, ay: Float, bx: Float, by: Float, aspectRatio: Float): Float {
        return hypot((ax - bx) * aspectRatio, ay - by)
    }

    private fun pointToSegmentDistanceIso(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        aspectRatio: Float
    ): Float {
        val pxI = px * aspectRatio
        val axI = ax * aspectRatio
        val bxI = bx * aspectRatio
        val abx = bxI - axI
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        if (lenSq < 1e-6f) return hypot(pxI - axI, py - ay)
        val t = (((pxI - axI) * abx + (py - ay) * aby) / lenSq).coerceIn(0.05f, 0.95f)
        val projX = axI + t * abx
        val projY = ay + t * aby
        return hypot(pxI - projX, py - projY)
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
        val rx = (0.030f * w).toInt().coerceAtLeast(8)
        val ry = (0.052f * h).toInt().coerceAtLeast(8)
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
                if (g > 125 && g > r + 35 && g > b + 35) {
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
        var brightWoodPixels = 0
        val samples = 10
        val hsv = FloatArray(3)
        for (dxNorm in listOf(-0.008f, 0f, 0.008f)) {
            val px = ((powerBarX + dxNorm) * w).toInt().coerceIn(0, w - 1)
            for (i in 0 until samples) {
                val ny = 0.34f + (i.toFloat() / (samples - 1)) * 0.44f
                val py = (ny * h).toInt().coerceIn(0, h - 1)
                Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                if (hsv[2] > 0.34f && hsv[0] in 12f..68f) {
                    brightWoodPixels++
                }
            }
        }
        return brightWoodPixels >= 6
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
