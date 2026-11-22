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
    val updatedProfile: TrainingProfileEntity,
    val outcomeLabel: String
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
 * Estimates real table physics (friction, power-to-distance scale, cut deflection bias)
 * in isotropic screen coordinates, explicitly classifies mistakes (missed turn lost vs
 * white-ball scratch), and learns from both Bot shots and live Human shots.
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
        reward: Float,
        scratched: Boolean = false,
        aspectRatio: Float = 2.0f
    ): PhysicsUpdateOutcome {
        val postX = postCueBall?.normX ?: preShot.cueBall.normX
        val postY = postCueBall?.normY ?: preShot.cueBall.normY

        val cueTravelDist = hypot((postX - preShot.cueBall.normX) * aspectRatio, postY - preShot.cueBall.normY)

        val expectedAngleDeg = atan2(
            (preShot.aimPointY - preShot.cueBall.normY).toDouble(),
            ((preShot.aimPointX - preShot.cueBall.normX) * aspectRatio).toDouble()
        ) * 180.0 / PI

        val actualAngleDeg = if (cueTravelDist > 0.03f && !scratched) {
            atan2(
                (postY - preShot.cueBall.normY).toDouble(),
                ((postX - preShot.cueBall.normX) * aspectRatio).toDouble()
            ) * 180.0 / PI
        } else {
            expectedAngleDeg
        }

        val deflectionErr = normalizeAngleDeg((actualAngleDeg - expectedAngleDeg).toFloat())

        val safeCurrentFriction = profile.learnedFrictionCoef.coerceIn(0.28f, 0.78f)
        val safeCurrentScale = profile.learnedPowerScale.coerceIn(0.78f, 1.22f)

        val estimatedFriction: Float
        val nextFriction: Float
        val nextPowerScale: Float
        if (cueTravelDist >= 0.03f && !scratched) {
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
        val nextCutBias = if (!pottedBall && !scratched && cueTravelDist > 0.045f && abs(deflectionErr) < 25f) {
            ((1f - 0.12f) * profile.learnedCutBiasDeg + 0.12f * (-clampedBiasSample * 0.28f)).coerceIn(-6f, 6f)
        } else {
            profile.learnedCutBiasDeg
        }

        val outcomeLabel = when {
            scratched -> "CUE_SCRATCH"
            pottedBall -> "POTTED"
            keptTurn -> "KEPT_TURN"
            cueTravelDist < 0.025f -> "MISSED_NO_HIT"
            abs(deflectionErr) > 16f -> "MISSED_AIM_ANGLE"
            else -> "MISSED_TURN_LOST"
        }

        val isMistake = scratched || (!pottedBall && !keptTurn)

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
            reward = reward,
            outcomeLabel = outcomeLabel
        )

        val updatedProfile = profile.copy(
            learnedPowerScale = nextPowerScale,
            learnedFrictionCoef = nextFriction,
            learnedCutBiasDeg = nextCutBias,
            physicsSamplesCount = profile.physicsSamplesCount + 1,
            totalMistakesLogged = profile.totalMistakesLogged + (if (isMistake) 1 else 0),
            scratchesCount = profile.scratchesCount + (if (scratched) 1 else 0),
            updatedAt = System.currentTimeMillis()
        )

        return PhysicsUpdateOutcome(log, updatedProfile, outcomeLabel)
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
        keptTurn: Boolean,
        scratched: Boolean = false
    ): HumanObservationOutcome? {
        val aspectRatio = preAnalysis.aspectRatio
        val preCue = preAnalysis.cueBall ?: return null
        val postCue = postAnalysis.cueBall
        val postX = postCue?.normX ?: preCue.normX
        val postY = postCue?.normY ?: preCue.normY
        val cueTravel = hypot((postX - preCue.normX) * aspectRatio, postY - preCue.normY)

        val preTargets = preAnalysis.allTableTargets
        val postTargets = postAnalysis.allTableTargets
        if (preTargets.isEmpty()) return null

        val disappearedBall = preTargets.firstOrNull { preBall ->
            postTargets.none { hypot((it.normX - preBall.normX) * aspectRatio, it.normY - preBall.normY) < 0.042f }
        }
        val chosenTarget = disappearedBall
            ?: preAnalysis.plannedShot?.targetBall
            ?: preTargets.minByOrNull { hypot((it.normX - preCue.normX) * aspectRatio, it.normY - preCue.normY) }
            ?: return null

        val pockets = preAnalysis.pockets
        val chosenPocket: PoolPocket = pockets.minByOrNull { pocket ->
            val ctDx = (chosenTarget.normX - preCue.normX) * aspectRatio
            val ctDy = chosenTarget.normY - preCue.normY
            val ctDist = hypot(ctDx, ctDy).coerceAtLeast(0.01f)
            val tpDx = (pocket.normX - chosenTarget.normX) * aspectRatio
            val tpDy = pocket.normY - chosenTarget.normY
            val tpDist = hypot(tpDx, tpDy).coerceAtLeast(0.01f)
            val dot = ((ctDx / ctDist) * (tpDx / tpDist) + (ctDy / ctDist) * (tpDy / tpDist)).coerceIn(-1f, 1f)
            val cutDeg = (acos(dot) * 180.0 / PI).toFloat()
            cutDeg + tpDist * 22f
        } ?: PoolPocket("BR", 0.80f, 0.90f)

        val ctDx = (chosenTarget.normX - preCue.normX) * aspectRatio
        val ctDy = chosenTarget.normY - preCue.normY
        val ctDist = hypot(ctDx, ctDy).coerceAtLeast(0.01f)
        val tpDx = (chosenPocket.normX - chosenTarget.normX) * aspectRatio
        val tpDy = chosenPocket.normY - chosenTarget.normY
        val tpDist = hypot(tpDx, tpDy).coerceAtLeast(0.01f)
        val totalDist = ctDist + tpDist
        val dot = ((ctDx / ctDist) * (tpDx / tpDist) + (ctDy / ctDist) * (tpDy / tpDist)).coerceIn(-1f, 1f)
        val cutAngleDeg = (acos(dot) * 180.0 / PI).toFloat()

        val inferredPower = when {
            cueTravel >= 0.03f && !scratched -> (0.34f + cueTravel * 0.72f).coerceIn(0.32f, 0.92f)
            pottedBall -> (0.40f + totalDist * 0.36f).coerceIn(0.36f, 0.88f)
            else -> 0.56f
        }

        val matchedAction = BotAction.POOL_SHOT_ACTIONS.minByOrNull { act ->
            abs(act.powerFraction - inferredPower) + abs(act.angleTrimDeg) * 0.04f
        } ?: BotAction.SHOT_MED_EXACT

        val angleBucket = (cutAngleDeg / 15f).toInt().coerceIn(0, 4)
        val distBucket = (totalDist * 3.5f).toInt().coerceIn(0, 4)
        val stateHash = "POOL_A${angleBucket}_D${distBucket}_${chosenPocket.id}"
        val stateSummary = "Human shot ${cutAngleDeg.toInt()}° | Dist ${"%.2f".format(totalDist)} -> ${chosenPocket.id}"

        val reward = when {
            scratched -> -3.5f
            pottedBall -> +5.5f
            keptTurn -> +1.6f
            else -> -0.8f
        }
        val outcomeLabel = when {
            scratched -> "HUMAN_SCRATCH"
            pottedBall -> "HUMAN_POTTED"
            keptTurn -> "HUMAN_KEPT"
            else -> "HUMAN_MISSED"
        }

        val safeCurrentFriction = profile.learnedFrictionCoef.coerceIn(0.28f, 0.78f)
        val safeCurrentScale = profile.learnedPowerScale.coerceIn(0.78f, 1.22f)
        val estimatedFriction = if (cueTravel >= 0.03f && !scratched) {
            val travelPerPower = (cueTravel / inferredPower.coerceAtLeast(0.20f)).coerceIn(0.08f, 1.6f)
            (0.95f - (travelPerPower * 0.42f)).coerceIn(0.26f, 0.80f)
        } else {
            safeCurrentFriction
        }
        val nextFriction = if (cueTravel >= 0.03f && !scratched) {
            ((1f - 0.14f) * safeCurrentFriction + 0.14f * estimatedFriction).coerceIn(0.28f, 0.78f)
        } else {
            safeCurrentFriction
        }

        val nextScale = if (pottedBall && inferredPower > 0.25f) {
            val idealBase = (0.38f + totalDist * 0.36f + (cutAngleDeg / 180f) * 0.14f).coerceIn(0.32f, 0.88f)
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
            reward = reward,
            outcomeLabel = outcomeLabel
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
