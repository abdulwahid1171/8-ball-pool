package com.example.domain.physics

import com.example.data.db.ShotPhysicsEntity
import com.example.data.db.TrainingProfileEntity
import com.example.domain.rl.BotAction
import com.example.domain.vision.DetectedBall
import com.example.domain.vision.PlannedPoolShot
import com.example.domain.vision.PoolFrameAnalysis
import com.example.domain.vision.PoolPocket
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

data class PhysicsUpdateOutcome(
    val shotLog: ShotPhysicsEntity,
    val updatedProfile: TrainingProfileEntity
)

data class HumanObservationOutcome(
    val shotLog: ShotPhysicsEntity,
    val updatedProfile: TrainingProfileEntity,
    val matchedAction: BotAction,
    val inferredStateHash: String,
    val inferredStateSummary: String,
    val reward: Float,
    val pocketId: String,
    val inferredPowerFraction: Float
)

/**
 * Estimates real table physics (friction, power-to-distance scale, and cut deflection bias)
 * from both Bot shots and live Human-played shots so the bot self-calibrates and learns
 * directly from watching the user play.
 */
object PhysicsEstimator {

    fun computeCalibratedPower(basePower: Float, profile: TrainingProfileEntity): Float {
        val safeScale = profile.learnedPowerScale.coerceIn(0.78f, 1.22f)
        return (basePower * safeScale).coerceIn(0.28f, 0.94f)
    }

    fun evaluatePostShotPhysics(
        profile: TrainingProfileEntity,
        shotNumber: Int,
        preShot: PlannedPoolShot,
        powerFraction: Float,
        angleTrimDeg: Float,
        postCueBall: DetectedBall?,
        pottedBall: Boolean,
        keptTurn: Boolean,
        reward: Float
    ): PhysicsUpdateOutcome {
        val postX = postCueBall?.normX ?: preShot.cueBall.normX
        val postY = postCueBall?.normY ?: preShot.cueBall.normY

        val cueTravelDist = hypot(postX - preShot.cueBall.normX, postY - preShot.cueBall.normY)

        val expectedAngleDeg = atan2(
            (preShot.aimPointY - preShot.cueBall.normY).toDouble(),
            (preShot.aimPointX - preShot.cueBall.normX).toDouble()
        ) * 180.0 / PI

        val actualAngleDeg = if (cueTravelDist > 0.025f) {
            atan2(
                (postY - preShot.cueBall.normY).toDouble(),
                (postX - preShot.cueBall.normX).toDouble()
            ) * 180.0 / PI
        } else {
            expectedAngleDeg
        }

        val deflectionErr = normalizeAngleDeg((actualAngleDeg - expectedAngleDeg).toFloat())

        // Zero-travel frames (cue obscured or screen stalled) must NOT corrupt learned friction/powerScale!
        val safeCurrentFriction = profile.learnedFrictionCoef.coerceIn(0.28f, 0.78f)
        val safeCurrentScale = profile.learnedPowerScale.coerceIn(0.78f, 1.22f)

        val estimatedFriction: Float
        val nextFriction: Float
        val nextPowerScale: Float
        if (cueTravelDist >= 0.025f) {
            val travelPerPower = (cueTravelDist / powerFraction.coerceAtLeast(0.18f)).coerceIn(0.08f, 1.6f)
            estimatedFriction = (0.95f - (travelPerPower * 0.42f)).coerceIn(0.26f, 0.80f)
            val emaAlpha = 0.18f
            nextFriction = ((1f - emaAlpha) * safeCurrentFriction + emaAlpha * estimatedFriction)
                .coerceIn(0.28f, 0.78f)

            val targetScaleAdjustment = when {
                pottedBall -> safeCurrentScale
                cueTravelDist < preShot.plannedDistanceSafe() * 0.40f -> (safeCurrentScale + 0.04f)
                cueTravelDist > preShot.plannedDistanceSafe() * 1.35f -> (safeCurrentScale - 0.04f)
                else -> safeCurrentScale
            }.coerceIn(0.78f, 1.22f)
            nextPowerScale = ((1f - emaAlpha) * safeCurrentScale + emaAlpha * targetScaleAdjustment)
                .coerceIn(0.78f, 1.22f)
        } else {
            estimatedFriction = safeCurrentFriction
            nextFriction = safeCurrentFriction
            nextPowerScale = safeCurrentScale
        }

        val clampedBiasSample = deflectionErr.coerceIn(-10f, 10f)
        val nextCutBias = if (!pottedBall && cueTravelDist > 0.04f && abs(deflectionErr) < 28f) {
            ((1f - 0.10f) * profile.learnedCutBiasDeg + 0.10f * (-clampedBiasSample * 0.30f)).coerceIn(-6f, 6f)
        } else {
            profile.learnedCutBiasDeg
        }

        val log = ShotPhysicsEntity(
            profileId = profile.id,
            shotNumber = shotNumber,
            preCueX = preShot.cueBall.normX,
            preCueY = preShot.cueBall.normY,
            preTargetX = preShot.targetBall.normX,
            preTargetY = preShot.targetBall.normY,
            pocketId = preShot.pocket.id,
            cutAngleDeg = preShot.cutAngleDeg,
            plannedDistance = preShot.totalDistance,
            powerFraction = powerFraction,
            angleTrimDeg = angleTrimDeg,
            postCueX = postX,
            postCueY = postY,
            cueTravelDist = cueTravelDist,
            estimatedFriction = estimatedFriction,
            angleDeflectionErrorDeg = deflectionErr,
            pottedBall = pottedBall,
            keptTurn = keptTurn,
            reward = reward
        )

        val updatedProfile = profile.copy(
            learnedPowerScale = nextPowerScale,
            learnedFrictionCoef = nextFriction,
            learnedCutBiasDeg = nextCutBias,
            physicsSamplesCount = profile.physicsSamplesCount + 1,
            updatedAt = System.currentTimeMillis()
        )

        return PhysicsUpdateOutcome(log, updatedProfile)
    }

    /**
     * Infers shot geometry, force, pocket, and policy action directly from watching the human play.
     */
    fun evaluateHumanShotObservation(
        profile: TrainingProfileEntity,
        shotNumber: Int,
        preAnalysis: PoolFrameAnalysis,
        postAnalysis: PoolFrameAnalysis,
        pottedBall: Boolean,
        keptTurn: Boolean
    ): HumanObservationOutcome? {
        val preCue = preAnalysis.cueBall ?: return null
        val postCue = postAnalysis.cueBall
        val postX = postCue?.normX ?: preCue.normX
        val postY = postCue?.normY ?: preCue.normY
        val cueTravel = hypot(postX - preCue.normX, postY - preCue.normY)

        val preTargets = preAnalysis.allTableTargets
        val postTargets = postAnalysis.allTableTargets
        if (preTargets.isEmpty()) return null

        // Identify the target ball hit/potted by the human:
        // Priority 1: A pre-shot ball that disappeared from the post-shot table (potted!)
        val disappearedBall = preTargets.firstOrNull { preBall ->
            postTargets.none { hypot(it.normX - preBall.normX, it.normY - preBall.normY) < 0.032f }
        }
        // Priority 2: Fallback to ball nearest to pre->post cue trajectory or best planned target
        val chosenTarget = disappearedBall
            ?: preAnalysis.plannedShot?.targetBall
            ?: preTargets.minByOrNull { hypot(it.normX - preCue.normX, it.normY - preCue.normY) }
            ?: return null

        // Estimate which pocket the target was aimed toward
        val pockets = preAnalysis.pockets
        val chosenPocket: PoolPocket = pockets.minByOrNull { pocket ->
            val ctDx = chosenTarget.normX - preCue.normX
            val ctDy = chosenTarget.normY - preCue.normY
            val ctDist = hypot(ctDx, ctDy).coerceAtLeast(0.01f)
            val tpDx = pocket.normX - chosenTarget.normX
            val tpDy = pocket.normY - chosenTarget.normY
            val tpDist = hypot(tpDx, tpDy).coerceAtLeast(0.01f)
            val dot = ((ctDx / ctDist) * (tpDx / tpDist) + (ctDy / ctDist) * (tpDy / tpDist)).coerceIn(-1f, 1f)
            val cutDeg = (acos(dot) * 180.0 / PI).toFloat()
            cutDeg + tpDist * 28f
        } ?: PoolPocket("BR", 0.80f, 0.90f)

        val ctDx = chosenTarget.normX - preCue.normX
        val ctDy = chosenTarget.normY - preCue.normY
        val ctDist = hypot(ctDx, ctDy).coerceAtLeast(0.01f)
        val tpDx = chosenPocket.normX - chosenTarget.normX
        val tpDy = chosenPocket.normY - chosenTarget.normY
        val tpDist = hypot(tpDx, tpDy).coerceAtLeast(0.01f)
        val totalDist = ctDist + tpDist
        val dot = ((ctDx / ctDist) * (tpDx / tpDist) + (ctDy / ctDist) * (tpDy / tpDist)).coerceIn(-1f, 1f)
        val cutAngleDeg = (acos(dot) * 180.0 / PI).toFloat()

        // Infer human stroke force from observed cue travel vs planned distance
        val inferredPower = when {
            cueTravel >= 0.025f -> (0.34f + cueTravel * 0.85f).coerceIn(0.32f, 0.92f)
            pottedBall -> (0.42f + totalDist * 0.46f).coerceIn(0.36f, 0.88f)
            else -> 0.58f
        }

        val matchedAction = BotAction.POOL_SHOT_ACTIONS.minByOrNull { act ->
            abs(act.powerFraction - inferredPower) + abs(act.angleTrimDeg) * 0.04f
        } ?: BotAction.SHOT_MED_EXACT

        val angleBucket = (cutAngleDeg / 15f).toInt().coerceIn(0, 4)
        val distBucket = (totalDist * 5f).toInt().coerceIn(0, 4)
        val stateHash = "POOL_A${angleBucket}_D${distBucket}_${chosenPocket.id}"
        val stateSummary = "Human shot ${cutAngleDeg.toInt()}° | Dist ${"%.2f".format(totalDist)} -> ${chosenPocket.id}"

        val reward = when {
            pottedBall -> +5.5f
            keptTurn -> +1.6f
            else -> -0.8f
        }

        val safeCurrentFriction = profile.learnedFrictionCoef.coerceIn(0.28f, 0.78f)
        val safeCurrentScale = profile.learnedPowerScale.coerceIn(0.78f, 1.22f)
        val estimatedFriction = if (cueTravel >= 0.025f) {
            val travelPerPower = (cueTravel / inferredPower.coerceAtLeast(0.20f)).coerceIn(0.08f, 1.6f)
            (0.95f - (travelPerPower * 0.42f)).coerceIn(0.26f, 0.80f)
        } else {
            safeCurrentFriction
        }
        val nextFriction = if (cueTravel >= 0.025f) {
            ((1f - 0.14f) * safeCurrentFriction + 0.14f * estimatedFriction).coerceIn(0.28f, 0.78f)
        } else {
            safeCurrentFriction
        }

        val nextScale = if (pottedBall && inferredPower > 0.25f) {
            val idealBase = (0.40f + totalDist * 0.50f + (cutAngleDeg / 180f) * 0.14f).coerceIn(0.32f, 0.90f)
            val observedRatio = (inferredPower / idealBase).coerceIn(0.78f, 1.22f)
            ((1f - 0.15f) * safeCurrentScale + 0.15f * observedRatio).coerceIn(0.78f, 1.22f)
        } else {
            safeCurrentScale
        }

        val log = ShotPhysicsEntity(
            profileId = profile.id,
            shotNumber = shotNumber,
            preCueX = preCue.normX,
            preCueY = preCue.normY,
            preTargetX = chosenTarget.normX,
            preTargetY = chosenTarget.normY,
            pocketId = chosenPocket.id,
            cutAngleDeg = cutAngleDeg,
            plannedDistance = totalDist,
            powerFraction = inferredPower,
            angleTrimDeg = 0f,
            postCueX = postX,
            postCueY = postY,
            cueTravelDist = cueTravel,
            estimatedFriction = estimatedFriction,
            angleDeflectionErrorDeg = 0f,
            pottedBall = pottedBall,
            keptTurn = keptTurn,
            reward = reward
        )

        val updatedProfile = profile.copy(
            learnedPowerScale = nextScale,
            learnedFrictionCoef = nextFriction,
            physicsSamplesCount = profile.physicsSamplesCount + 1,
            humanShotsObserved = profile.humanShotsObserved + 1,
            updatedAt = System.currentTimeMillis()
        )

        return HumanObservationOutcome(
            shotLog = log,
            updatedProfile = updatedProfile,
            matchedAction = matchedAction,
            inferredStateHash = stateHash,
            inferredStateSummary = stateSummary,
            reward = reward,
            pocketId = chosenPocket.id,
            inferredPowerFraction = inferredPower
        )
    }

    private fun PlannedPoolShot.plannedDistanceSafe(): Float = totalDistance.coerceAtLeast(0.10f)

    private fun normalizeAngleDeg(angle: Float): Float {
        var a = angle
        while (a > 180f) a -= 360f
        while (a < -180f) a += 360f
        return a
    }
}
