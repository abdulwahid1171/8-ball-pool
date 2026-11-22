package com.example.data.repository

import com.example.data.db.ExperienceEntity
import com.example.data.db.NeuroBotDao
import com.example.data.db.QTableEntryEntity
import com.example.data.db.ShotPhysicsEntity
import com.example.data.db.TrainingProfileEntity
import com.example.data.db.TrainingSessionEntity
import kotlinx.coroutines.flow.Flow

class BotMemoryRepository(private val dao: NeuroBotDao) {

    val profiles: Flow<List<TrainingProfileEntity>> = dao.observeProfiles()

    fun observeQEntries(profileId: Long): Flow<List<QTableEntryEntity>> =
        dao.observeQEntries(profileId)

    fun observeRecentExperiences(profileId: Long, limit: Int = 60): Flow<List<ExperienceEntity>> =
        dao.observeRecentExperiences(profileId, limit)

    fun observeExperienceCount(profileId: Long): Flow<Int> =
        dao.observeExperienceCount(profileId)

    fun observeSessions(profileId: Long): Flow<List<TrainingSessionEntity>> =
        dao.observeSessions(profileId)

    fun observeShotPhysics(profileId: Long, limit: Int = 40): Flow<List<ShotPhysicsEntity>> =
        dao.observeShotPhysics(profileId, limit)

    suspend fun ensureDefaultProfiles() {
        val existing = dao.getProfilesOnce()
        if (existing.isEmpty()) {
            dao.insertProfile(
                TrainingProfileEntity(
                    name = "8-Ball Pool Auto-Bot",
                    targetPackage = "com.miniclip.eightballpool",
                    description = "Reads table felt, white cue ball, target balls, green turn border, and executes calibrated aim + power bar shots.",
                    botMode = "POOL_AIM",
                    isLandscapeGame = true,
                    learningRate = 0.30f,
                    discountFactor = 0.90f,
                    explorationRate = 0.22f,
                    autoDecayEpsilon = true,
                    actionIntervalMs = 700L
                )
            )
            dao.insertProfile(
                TrainingProfileEntity(
                    name = "Universal Tap & Swipe Bot",
                    targetPackage = "any.game.screen",
                    description = "Reads external mobile game screens via MediaProjection + Accessibility and learns optimal taps & swipes.",
                    botMode = "GENERAL_TAP",
                    isLandscapeGame = false,
                    learningRate = 0.25f,
                    discountFactor = 0.88f,
                    explorationRate = 0.28f,
                    autoDecayEpsilon = true,
                    actionIntervalMs = 450L
                )
            )
        }
    }

    suspend fun getProfile(profileId: Long): TrainingProfileEntity? =
        dao.getProfileById(profileId)

    suspend fun createProfile(profile: TrainingProfileEntity): Long =
        dao.insertProfile(profile)

    suspend fun updateProfile(profile: TrainingProfileEntity) =
        dao.updateProfile(profile)

    suspend fun deleteProfile(profileId: Long) =
        dao.deleteProfile(profileId)

    suspend fun getQEntriesOnce(profileId: Long): List<QTableEntryEntity> =
        dao.getQEntriesForProfile(profileId)

    suspend fun upsertQEntries(entries: List<QTableEntryEntity>) {
        if (entries.isNotEmpty()) dao.upsertQEntries(entries)
    }

    suspend fun insertExperiences(experiences: List<ExperienceEntity>) {
        if (experiences.isNotEmpty()) dao.insertExperiences(experiences)
    }

    suspend fun getRecentExperiencesOnce(profileId: Long, limit: Int = 256): List<ExperienceEntity> =
        dao.getRecentExperiencesOnce(profileId, limit)

    suspend fun insertSession(session: TrainingSessionEntity): Long =
        dao.insertSession(session)

    suspend fun insertShotPhysics(item: ShotPhysicsEntity): Long =
        dao.insertShotPhysics(item)

    suspend fun getShotPhysicsOnce(profileId: Long, limit: Int = 50): List<ShotPhysicsEntity> =
        dao.getShotPhysicsOnce(profileId, limit)

    suspend fun resetProfileMemory(profileId: Long) {
        dao.clearQEntries(profileId)
        dao.clearExperiences(profileId)
        dao.clearSessions(profileId)
        dao.clearShotPhysics(profileId)
        val profile = dao.getProfileById(profileId) ?: return
        dao.updateProfile(
            profile.copy(
                totalEpisodes = 0,
                totalSteps = 0,
                bestScore = 0,
                lastScore = 0,
                totalReplayUpdates = 0,
                learnedPowerScale = 1.0f,
                learnedFrictionCoef = 0.52f,
                learnedCutBiasDeg = 0.0f,
                physicsSamplesCount = 0,
                explorationRate = 0.25f,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun exportProfileBrainJson(profileId: Long): String {
        val profile = dao.getProfileById(profileId)
        val qEntries = dao.getQEntriesForProfile(profileId)
        val experiences = dao.getRecentExperiencesOnce(profileId, 20)
        val physicsLogs = dao.getShotPhysicsOnce(profileId, 20)

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"reportType\": \"NeuroBot_Physics_And_Training_Dump\",\n")
        sb.append("  \"profile\": \"${profile?.name ?: "Unknown"}\",\n")
        sb.append("  \"botMode\": \"${profile?.botMode ?: ""}\",\n")
        sb.append("  \"targetPackage\": \"${profile?.targetPackage ?: ""}\",\n")
        sb.append("  \"calibration\": {\n")
        sb.append("    \"tableBounds\": [${"%.3f".format(profile?.tableLeft ?: 0f)}, ${"%.3f".format(profile?.tableTop ?: 0f)}, ${"%.3f".format(profile?.tableRight ?: 0f)}, ${"%.3f".format(profile?.tableBottom ?: 0f)}],\n")
        sb.append("    \"powerBarX\": ${"%.3f".format(profile?.powerBarX ?: 0f)},\n")
        sb.append("    \"turnBox\": [${"%.3f".format(profile?.turnBoxX ?: 0f)}, ${"%.3f".format(profile?.turnBoxY ?: 0f)}]\n")
        sb.append("  },\n")
        sb.append("  \"learnedPhysics\": {\n")
        sb.append("    \"samples\": ${profile?.physicsSamplesCount ?: 0},\n")
        sb.append("    \"powerScale\": ${"%.3f".format(profile?.learnedPowerScale ?: 1f)},\n")
        sb.append("    \"feltFrictionCoef\": ${"%.3f".format(profile?.learnedFrictionCoef ?: 0.52f)},\n")
        sb.append("    \"cutDeflectionBiasDeg\": ${"%.2f".format(profile?.learnedCutBiasDeg ?: 0f)}\n")
        sb.append("  },\n")
        sb.append("  \"trainingStats\": {\n")
        sb.append("    \"totalRounds\": ${profile?.totalEpisodes ?: 0},\n")
        sb.append("    \"totalSteps\": ${profile?.totalSteps ?: 0},\n")
        sb.append("    \"replayUpdates\": ${profile?.totalReplayUpdates ?: 0},\n")
        sb.append("    \"learnedQPairs\": ${qEntries.size}\n")
        sb.append("  },\n")
        sb.append("  \"topQPolicies\": [\n")
        val topQ = qEntries.sortedByDescending { it.qValue }.take(12)
        topQ.forEachIndexed { idx, entry ->
            val comma = if (idx < topQ.lastIndex) "," else ""
            sb.append("    {\"state\": \"${entry.stateHash}\", \"action\": \"${entry.actionName}\", \"q\": ${"%.3f".format(entry.qValue)}, \"visits\": ${entry.visitCount}}$comma\n")
        }
        sb.append("  ],\n")
        sb.append("  \"shotPhysicsTelemetry\": [\n")
        physicsLogs.forEachIndexed { idx, s ->
            val comma = if (idx < physicsLogs.lastIndex) "," else ""
            sb.append(
                "    {\"shot\": ${s.shotNumber}, \"pocket\": \"${s.pocketId}\", \"cutDeg\": ${"%.1f".format(s.cutAngleDeg)}, " +
                    "\"power\": ${"%.2f".format(s.powerFraction)}, \"preCue\": [${"%.3f".format(s.preCueX)},${"%.3f".format(s.preCueY)}], " +
                    "\"postCue\": [${"%.3f".format(s.postCueX)},${"%.3f".format(s.postCueY)}], \"travel\": ${"%.3f".format(s.cueTravelDist)}, " +
                    "\"friction\": ${"%.3f".format(s.estimatedFriction)}, \"deflectErrDeg\": ${"%.1f".format(s.angleDeflectionErrorDeg)}, " +
                    "\"potted\": ${s.pottedBall}, \"reward\": ${"%.2f".format(s.reward)}}$comma\n"
            )
        }
        sb.append("  ],\n")
        sb.append("  \"recentExperienceNotes\": [\n")
        experiences.take(10).forEachIndexed { idx, e ->
            val comma = if (idx < minOf(9, experiences.lastIndex)) "," else ""
            val safeNote = e.screenNotes.replace("\"", "'")
            sb.append("    \"Ep${e.episodeNumber}#${e.stepIndex} ${e.actionName} r=${"%.2f".format(e.reward)} ($safeNote)\"$comma\n")
        }
        sb.append("  ]\n}")
        return sb.toString()
    }
}
