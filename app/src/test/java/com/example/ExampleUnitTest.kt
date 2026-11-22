package com.example

import com.example.data.db.ExperienceEntity
import com.example.data.db.NeuroBotDao
import com.example.data.db.QTableEntryEntity
import com.example.data.db.ShotPhysicsEntity
import com.example.data.db.TrainingProfileEntity
import com.example.data.db.TrainingSessionEntity
import com.example.data.repository.BotMemoryRepository
import com.example.domain.physics.PhysicsEstimator
import com.example.domain.rl.BotAction
import com.example.domain.rl.SelfTrainingEngine
import com.example.domain.vision.DetectedBall
import com.example.domain.vision.DetectedBallKind
import com.example.domain.vision.PlannedPoolShot
import com.example.domain.vision.PoolPocket
import com.example.domain.vision.PoolVisionAnalyzer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun poolVisionAnalyzer_computesTrimmedAimPointWithinTableBounds() {
        val shot = PlannedPoolShot(
            cueBall = DetectedBall(0.65f, 0.58f, DetectedBallKind.CUE, 0),
            targetBall = DetectedBall(0.49f, 0.65f, DetectedBallKind.TARGET, 0),
            pocket = PoolPocket("BL", 0.18f, 0.91f),
            aimPointX = 0.51f,
            aimPointY = 0.64f,
            cutAngleDeg = 12f,
            totalDistance = 0.58f,
            basePowerFraction = 0.72f,
            stateHash = "POOL_A0_D2_BL",
            stateSummary = "Test shot"
        )
        val (ax, ay) = PoolVisionAnalyzer.computeAimPointWithTrim(shot, angleTrimDeg = -2.8f)
        assertTrue(ax in 0.16f..0.84f)
        assertTrue(ay in 0.18f..0.94f)
    }

    @Test
    fun physicsEstimator_measuresTravelFrictionAndExportsReport() = runTest {
        val fakeDao = InMemoryNeuroBotDao()
        val repo = BotMemoryRepository(fakeDao)
        repo.ensureDefaultProfiles()
        val profile = repo.getProfile(1L)!!

        val shot = PlannedPoolShot(
            cueBall = DetectedBall(0.60f, 0.55f, DetectedBallKind.CUE, 0),
            targetBall = DetectedBall(0.42f, 0.62f, DetectedBallKind.TARGET, 0),
            pocket = PoolPocket("BL", 0.18f, 0.91f),
            aimPointX = 0.44f,
            aimPointY = 0.61f,
            cutAngleDeg = 14f,
            totalDistance = 0.48f,
            basePowerFraction = 0.65f,
            stateHash = "POOL_A0_D2_BL",
            stateSummary = "Cut 14"
        )

        val outcome = PhysicsEstimator.evaluatePostShotPhysics(
            profile = profile,
            shotNumber = 1,
            preShot = shot,
            powerFraction = 0.65f,
            angleTrimDeg = 0f,
            postCueBall = DetectedBall(0.45f, 0.60f, DetectedBallKind.CUE, 0),
            pottedBall = true,
            keptTurn = true,
            reward = 5.0f
        )

        assertTrue(outcome.shotLog.cueTravelDist > 0.10f)
        assertEquals(1, outcome.updatedProfile.physicsSamplesCount)

        repo.insertShotPhysics(outcome.shotLog)
        repo.updateProfile(outcome.updatedProfile)
        val report = repo.exportProfileBrainJson(1L)
        assertTrue(report.contains("NeuroBot_Physics_And_Training_Dump"))
        assertTrue(report.contains("shotPhysicsTelemetry"))
    }

    @Test
    fun selfTrainingEngine_learnsPoolShotPolicyAndReplaysSavedMemory() = runTest {
        val fakeDao = InMemoryNeuroBotDao()
        val repo = BotMemoryRepository(fakeDao)
        repo.ensureDefaultProfiles()

        val engine = SelfTrainingEngine(repo)
        engine.ensureLoaded(1L)

        val qGood = engine.recordStep(
            profileId = 1L,
            episodeNumber = 1,
            stepIndex = 1,
            stateHash = "POOL_A1_D2_BL",
            stateSummary = "Cut 15 deg -> BL",
            action = BotAction.SHOT_MED_EXACT,
            reward = 5.0f,
            nextStateHash = "POOL_A0_D1_TR",
            isTerminal = false,
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            learningRate = 0.4f,
            discountFactor = 0.9f,
            source = "LIVE_POOL"
        )
        val qMiss = engine.recordStep(
            profileId = 1L,
            episodeNumber = 1,
            stepIndex = 2,
            stateHash = "POOL_A1_D2_BL",
            stateSummary = "Cut 15 deg -> BL",
            action = BotAction.SHOT_POWER_EXACT,
            reward = -1.6f,
            nextStateHash = "POOL_WAIT",
            isTerminal = true,
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            learningRate = 0.4f,
            discountFactor = 0.9f,
            source = "LIVE_POOL"
        )

        assertTrue(qGood > 0f)
        assertTrue(qMiss < 0f)

        val decision = engine.selectAction(
            profileId = 1L,
            stateHash = "POOL_A1_D2_BL",
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            epsilon = 0.0f
        )
        assertEquals(BotAction.SHOT_MED_EXACT, decision.action)

        engine.flushToDatabase(1L)
        assertEquals(2, repo.getQEntriesOnce(1L).size)
        assertEquals(2, repo.getRecentExperiencesOnce(1L, 50).size)

        val replayResult = engine.runExperienceReplay(
            profileId = 1L,
            learningRate = 0.3f,
            discountFactor = 0.9f,
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            batchSize = 10,
            epochs = 2
        )
        assertEquals(2, replayResult.experiencesReplayed)
        assertEquals(4, replayResult.qUpdatesApplied)
    }

    private class InMemoryNeuroBotDao : NeuroBotDao {
        private val profilesList = mutableListOf<TrainingProfileEntity>()
        private val qList = mutableListOf<QTableEntryEntity>()
        private val expList = mutableListOf<ExperienceEntity>()
        private val sessionList = mutableListOf<TrainingSessionEntity>()
        private val physicsList = mutableListOf<ShotPhysicsEntity>()
        private var nextProfileId = 1L
        private var nextExpId = 1L
        private var nextSessionId = 1L
        private var nextPhysicsId = 1L

        override fun observeProfiles(): Flow<List<TrainingProfileEntity>> = MutableStateFlow(profilesList.toList())
        override suspend fun getProfilesOnce(): List<TrainingProfileEntity> = profilesList.toList()
        override suspend fun getProfileById(profileId: Long): TrainingProfileEntity? =
            profilesList.firstOrNull { it.id == profileId }

        override suspend fun insertProfile(profile: TrainingProfileEntity): Long {
            val id = if (profile.id == 0L) nextProfileId++ else profile.id
            profilesList.removeAll { it.id == id }
            profilesList.add(profile.copy(id = id))
            return id
        }

        override suspend fun updateProfile(profile: TrainingProfileEntity) {
            profilesList.removeAll { it.id == profile.id }
            profilesList.add(profile)
        }

        override suspend fun deleteProfile(profileId: Long) {
            profilesList.removeAll { it.id == profileId }
        }

        override fun observeQEntries(profileId: Long): Flow<List<QTableEntryEntity>> =
            MutableStateFlow(qList.filter { it.profileId == profileId })

        override suspend fun getQEntriesForProfile(profileId: Long): List<QTableEntryEntity> =
            qList.filter { it.profileId == profileId }

        override suspend fun upsertQEntries(entries: List<QTableEntryEntity>) {
            entries.forEach { item ->
                qList.removeAll {
                    it.profileId == item.profileId &&
                        it.stateHash == item.stateHash &&
                        it.actionName == item.actionName
                }
                qList.add(item)
            }
        }

        override suspend fun clearQEntries(profileId: Long) {
            qList.removeAll { it.profileId == profileId }
        }

        override fun observeRecentExperiences(profileId: Long, limit: Int): Flow<List<ExperienceEntity>> =
            MutableStateFlow(expList.filter { it.profileId == profileId }.takeLast(limit))

        override suspend fun getRecentExperiencesOnce(profileId: Long, limit: Int): List<ExperienceEntity> =
            expList.filter { it.profileId == profileId }.takeLast(limit)

        override suspend fun insertExperiences(items: List<ExperienceEntity>) {
            items.forEach { expList.add(it.copy(id = nextExpId++)) }
        }

        override fun observeExperienceCount(profileId: Long): Flow<Int> =
            MutableStateFlow(expList.count { it.profileId == profileId })

        override suspend fun clearExperiences(profileId: Long) {
            expList.removeAll { it.profileId == profileId }
        }

        override fun observeSessions(profileId: Long): Flow<List<TrainingSessionEntity>> =
            MutableStateFlow(sessionList.filter { it.profileId == profileId })

        override suspend fun insertSession(session: TrainingSessionEntity): Long {
            val id = nextSessionId++
            sessionList.add(session.copy(id = id))
            return id
        }

        override suspend fun clearSessions(profileId: Long) {
            sessionList.removeAll { it.profileId == profileId }
        }

        override fun observeShotPhysics(profileId: Long, limit: Int): Flow<List<ShotPhysicsEntity>> =
            MutableStateFlow(physicsList.filter { it.profileId == profileId }.takeLast(limit))

        override suspend fun getShotPhysicsOnce(profileId: Long, limit: Int): List<ShotPhysicsEntity> =
            physicsList.filter { it.profileId == profileId }.takeLast(limit)

        override suspend fun insertShotPhysics(item: ShotPhysicsEntity): Long {
            val id = nextPhysicsId++
            physicsList.add(item.copy(id = id))
            return id
        }

        override suspend fun clearShotPhysics(profileId: Long) {
            physicsList.removeAll { it.profileId == profileId }
        }
    }
}
