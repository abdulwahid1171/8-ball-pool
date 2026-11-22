package com.example.domain.physics

import com.example.data.db.ShotPhysicsEntity
import com.example.data.db.TrainingProfileEntity
import com.example.domain.vision.DetectedBall
import com.example.domain.vision.PlannedPoolShot
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

data class PhysicsUpdateOutcome(
    val shotLog: ShotPhysicsEntity,
    val updatedProfile: TrainingProfileEntity
)

/**
 * Estimates real table physics (friction, power-to-distance scale, and cut deflection bias)
 * from pre-shot and post-shot screen observations so the bot self-calibrates over time
 * and exports clean telemetry for offline analysis.
 */
object PhysicsEstimator {

    fun computeCalibratedPower(basePower: Float, profile: TrainingProfileEntity): Float {
        return (basePower * profile.learnedPowerScale).coerceIn(0.28f, 0.96f)
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

        // Expected angle from cue -> ghost-aim point vs actual cue ball travel direction
        val expectedAngleDeg = atan2(
            (preShot.aimPointY - preShot.cueBall.normY).toDouble(),
            (preShot.aimPointX - preShot.cueBall.normX).toDouble()
        ) * 180.0 / PI

        val actualAngleDeg = if (cueTravelDist > 0.02f) {
            atan2(
                (postY - preShot.cueBall.normY).toDouble(),
                (postX - preShot.cueBall.normX).toDouble()
            ) * 180.0 / PI
        } else {
            expectedAngleDeg
        }

        val deflectionErr = normalizeAngleDeg((actualAngleDeg - expectedAngleDeg).toFloat())

        // Effective friction estimate: higher travel per unit power => lower felt friction
        val travelPerPower = (cueTravelDist / powerFraction.coerceAtLeast(0.15f)).coerceIn(0.05f, 1.8f)
        val estimatedFriction = (1.0f - (travelPerPower * 0.45f)).coerceIn(0.15f, 0.92f)

        // Exponential moving average update for the profile's learned physics model
        val emaAlpha = 0.20f
        val nextFriction = (1f - emaAlpha) * profile.learnedFrictionCoef + emaAlpha * estimatedFriction

        val targetScaleAdjustment = when {
            pottedBall -> profile.learnedPowerScale // keep successful power scale
            cueTravelDist < preShot.plannedDistanceSafe() * 0.40f -> (profile.learnedPowerScale + 0.06f)
            cueTravelDist > preShot.plannedDistanceSafe() * 1.35f -> (profile.learnedPowerScale - 0.05f)
            else -> profile.learnedPowerScale
        }.coerceIn(0.65f, 1.45f)
        val nextPowerScale = (1f - emaAlpha) * profile.learnedPowerScale + emaAlpha * targetScaleAdjustment

        val clampedBiasSample = deflectionErr.coerceIn(-12f, 12f)
        val nextCutBias = if (!pottedBall && cueTravelDist > 0.03f) {
            ((1f - 0.12f) * profile.learnedCutBiasDeg + 0.12f * (-clampedBiasSample * 0.35f)).coerceIn(-8f, 8f)
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

    private fun PlannedPoolShot.plannedDistanceSafe(): Float = totalDistance.coerceAtLeast(0.10f)

    private fun normalizeAngleDeg(angle: Float): Float {
        var a = angle
        while (a > 180f) a -= 360f
        while (a < -180f) a += 360f
        return a
    }
}
