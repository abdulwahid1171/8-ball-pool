package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NeuroBotApplication
import com.example.R
import com.example.data.db.TrainingProfileEntity
import com.example.data.db.TrainingSessionEntity
import com.example.domain.physics.PhysicsEstimator
import com.example.domain.rl.BotAction
import com.example.domain.vision.PoolVisionAnalyzer
import com.example.domain.vision.ScreenFrameAnalyzer
import com.example.domain.vision.ScreenPerception
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_BOT_CAPTURE"
        const val ACTION_TOGGLE_PAUSE = "com.example.action.TOGGLE_PAUSE_BOT"
        const val ACTION_RUN_REPLAY = "com.example.action.RUN_REPLAY_BOT"
        const val ACTION_STOP = "com.example.action.STOP_BOT_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        private const val CHANNEL_ID = "neurobot_live_channel"
        private const val NOTIF_ID = 4201
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var automationJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var isPaused = false
    private var activeProfileId = 1L
    private var lastPerception: ScreenPerception? = null
    private var lastAction: BotAction? = null
    private var currentEpisode = 1
    private var currentStep = 0
    private var currentEpisodeReward = 0f
    private var episodeStartMs = System.currentTimeMillis()
    private var staleFrames = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                activeProfileId = intent.getLongExtra(EXTRA_PROFILE_ID, 1L)

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundWithType(buildNotification("Starting live external game reader..."))
                startProjection(resultCode, resultData)
            }

            ACTION_TOGGLE_PAUSE -> {
                isPaused = !isPaused
                BotServiceBridge.setScreenCaptureState(
                    active = mediaProjection != null,
                    running = !isPaused,
                    paused = isPaused
                )
                BotServiceBridge.appendLog(if (isPaused) "Live Bot paused from notification." else "Live Bot resumed.")
                updateNotification(if (isPaused) "Paused — tap Resume to continue." else "Watching game screen & learning...")
            }

            ACTION_RUN_REPLAY -> {
                serviceScope.launch {
                    val app = application as NeuroBotApplication
                    val profile = app.repository.getProfile(activeProfileId)
                    if (profile != null) {
                        val actions = if (profile.botMode == "POOL_AIM") {
                            BotAction.POOL_SHOT_ACTIONS
                        } else {
                            BotAction.LIVE_SCREEN_ACTIONS
                        }
                        val result = app.trainingEngine.runExperienceReplay(
                            profileId = profile.id,
                            learningRate = profile.learningRate,
                            discountFactor = profile.discountFactor,
                            availableActions = actions
                        )
                        app.repository.updateProfile(
                            profile.copy(
                                totalReplayUpdates = profile.totalReplayUpdates + result.qUpdatesApplied,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        BotServiceBridge.appendLog(
                            "Self-Train Replay: ${result.qUpdatesApplied} Q-updates from ${result.experiencesReplayed} saved memories."
                        )
                    }
                }
            }

            ACTION_STOP -> stopCaptureAndService()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        releaseProjectionResources()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: run {
            stopCaptureAndService()
            return
        }
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                BotServiceBridge.appendLog("Screen projection stopped by system.")
                stopCaptureAndService()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                super.onCapturedContentResize(width, height)
                reconfigureVirtualDisplay(width, height)
            }
        }, Handler(Looper.getMainLooper()))

        val (screenW, screenH, densityDpi) = getDisplayMetricsInfo()
        val captureW = (screenW / 3).coerceIn(240, 720)
        val captureH = (screenH / 3).coerceIn(240, 720)

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "NeuroBotVision",
            captureW,
            captureH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        isPaused = false
        BotServiceBridge.setScreenCaptureState(active = true, running = true, paused = false)
        BotServiceBridge.appendLog("Screen Reader active (${captureW}x${captureH}). Switch to your game now!")
        startAutomationLoop()
    }

    private fun reconfigureVirtualDisplay(newWidth: Int, newHeight: Int) {
        val display = virtualDisplay ?: return
        val captureW = (newWidth / 3).coerceIn(240, 720)
        val captureH = (newHeight / 3).coerceIn(240, 720)
        val densityDpi = resources.displayMetrics.densityDpi
        try {
            val newReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
            display.resize(captureW, captureH, densityDpi)
            display.surface = newReader.surface
            imageReader?.close()
            imageReader = newReader
            BotServiceBridge.appendLog("Screen orientation/size adapted to ${captureW}x${captureH}.")
        } catch (_: Exception) {
        }
    }

    private fun startAutomationLoop() {
        automationJob?.cancel()
        automationJob = serviceScope.launch {
            val app = application as NeuroBotApplication
            app.trainingEngine.ensureLoaded(activeProfileId)
            val initialProfile = app.repository.getProfile(activeProfileId)
            currentEpisode = (initialProfile?.totalEpisodes ?: 0) + 1
            currentStep = 0
            currentEpisodeReward = 0f
            episodeStartMs = System.currentTimeMillis()
            lastPerception = null
            lastAction = null

            while (isActive) {
                val profile = app.repository.getProfile(activeProfileId)
                val interval = (profile?.actionIntervalMs ?: 600L).coerceIn(200L, 3000L)

                if (!isPaused && profile != null) {
                    if (profile.botMode == "POOL_AIM") {
                        runPoolShotCycle(app, profile)
                    } else {
                        runGeneralTapCycle(app, profile)
                    }
                }
                delay(interval)
            }
        }
    }

    private suspend fun runPoolShotCycle(
        app: NeuroBotApplication,
        profile: TrainingProfileEntity
    ) {
        val bitmap = captureLatestBitmap() ?: return
        val analysis = PoolVisionAnalyzer.analyze(bitmap, profile)
        bitmap.recycle()

        val shot = analysis.plannedShot
        BotServiceBridge.updatePoolObservation(
            visionGrid = analysis.visionGrid,
            stateHash = shot?.stateHash ?: "POOL_SCANNING",
            isMyTurn = analysis.isMyTurn,
            targetsCount = analysis.targetBalls.size,
            cueX = analysis.cueBall?.normX,
            cueY = analysis.cueBall?.normY,
            targetX = shot?.targetBall?.normX,
            targetY = shot?.targetBall?.normY,
            pocketId = shot?.pocket?.id,
            summary = analysis.summary
        )

        val telemetry = BotServiceBridge.telemetry.value
        val isOverOwnApp = telemetry.activeForegroundPackage == packageName
        val a11y = GameBotAccessibilityService.instance

        if (!analysis.isMyTurn || shot == null || isOverOwnApp || a11y == null) {
            return
        }

        val decision = app.trainingEngine.selectAction(
            profileId = profile.id,
            stateHash = shot.stateHash,
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            epsilon = profile.explorationRate
        )

        val combinedTrimDeg = decision.action.angleTrimDeg + profile.learnedCutBiasDeg
        val (aimX, aimY) = PoolVisionAnalyzer.computeAimPointWithTrim(shot, combinedTrimDeg)
        // 1. Swipe from cue ball toward computed ghost-ball aim point
        a11y.dispatchNormalizedSwipe(
            startNormX = shot.cueBall.normX,
            startNormY = shot.cueBall.normY,
            endNormX = aimX,
            endNormY = aimY,
            durationMs = 260L
        )
        delay(340L)

        // 2. Pull the vertical power bar downward proportional to Q-learned + Physics-calibrated shot power
        val rawPower = ((shot.basePowerFraction + decision.action.powerFraction) * 0.5f)
        val powerFrac = PhysicsEstimator.computeCalibratedPower(rawPower, profile)
        val pullEndY = profile.powerBarTopY + powerFrac * (profile.powerBarBottomY - profile.powerBarTopY)
        a11y.dispatchNormalizedSwipe(
            startNormX = profile.powerBarX,
            startNormY = profile.powerBarTopY,
            endNormX = profile.powerBarX,
            endNormY = pullEndY,
            durationMs = 360L
        )

        // 3. Wait for balls to roll & settle, then read post-shot outcome + physics
        delay(3400L)
        val postBitmap = captureLatestBitmap()
        var stepReward = 0.2f
        var nextStateHash = shot.stateHash
        var outcomeNote = "Shot settled"
        var roundEnded = false
        var pottedBall = false
        var keptTurn = false
        var postCueBall: com.example.domain.vision.DetectedBall? = null

        if (postBitmap != null) {
            val postAnalysis = PoolVisionAnalyzer.analyze(postBitmap, profile)
            postBitmap.recycle()
            postCueBall = postAnalysis.cueBall
            nextStateHash = postAnalysis.plannedShot?.stateHash ?: "POOL_POST"

            val pottedIncreased = postAnalysis.pottedOccupiedRatio > analysis.pottedOccupiedRatio + 0.05f
            val targetsReduced = postAnalysis.targetBalls.size < analysis.targetBalls.size
            keptTurn = postAnalysis.isMyTurn
            pottedBall = pottedIncreased || (keptTurn && targetsReduced)

            when {
                pottedBall -> {
                    stepReward = +5.0f
                    outcomeNote = "Ball potted! Turn kept (+5.0)"
                }
                keptTurn -> {
                    stepReward = +1.4f
                    outcomeNote = "Turn retained (+1.4)"
                }
                else -> {
                    stepReward = -1.6f
                    outcomeNote = "Turn lost (-1.6)"
                    roundEnded = true
                }
            }
        }

        currentStep++
        currentEpisodeReward += stepReward

        val physicsOutcome = PhysicsEstimator.evaluatePostShotPhysics(
            profile = profile,
            shotNumber = profile.physicsSamplesCount + 1,
            preShot = shot,
            powerFraction = powerFrac,
            angleTrimDeg = combinedTrimDeg,
            postCueBall = postCueBall,
            pottedBall = pottedBall,
            keptTurn = keptTurn,
            reward = stepReward
        )
        app.repository.insertShotPhysics(physicsOutcome.shotLog)
        app.repository.updateProfile(physicsOutcome.updatedProfile)

        app.trainingEngine.recordStep(
            profileId = profile.id,
            episodeNumber = currentEpisode,
            stepIndex = currentStep,
            stateHash = shot.stateHash,
            stateSummary = shot.stateSummary,
            action = decision.action,
            reward = stepReward,
            nextStateHash = nextStateHash,
            isTerminal = roundEnded,
            availableActions = BotAction.POOL_SHOT_ACTIONS,
            learningRate = profile.learningRate,
            discountFactor = profile.discountFactor,
            source = "LIVE_POOL",
            screenNotes = "$outcomeNote | Pwr ${(powerFrac * 100).toInt()}% | Friction ${"%.2f".format(physicsOutcome.shotLog.estimatedFriction)}"
        )

        BotServiceBridge.updateLiveDecision(
            visionGrid = analysis.visionGrid,
            stateHash = shot.stateHash,
            action = decision.action,
            reward = stepReward,
            episodeCount = currentEpisode,
            stepCount = currentStep,
            episodeReward = currentEpisodeReward,
            note = "Shot #$currentStep [${decision.action.shortCode}] -> ${shot.pocket.id} ($outcomeNote)"
        )

        updateNotification("Shot #$currentStep • ${shot.pocket.id} • $outcomeNote")

        if (roundEnded || currentStep % 4 == 0) {
            finalizeLiveEpisode(app, profile.id, currentEpisodeReward, BotAction.POOL_SHOT_ACTIONS, "LIVE_POOL")
            if (roundEnded) {
                currentEpisode++
                currentStep = 0
                currentEpisodeReward = 0f
                episodeStartMs = System.currentTimeMillis()
            }
        }
    }

    private suspend fun runGeneralTapCycle(
        app: NeuroBotApplication,
        profile: TrainingProfileEntity
    ) {
        val bitmap = captureLatestBitmap() ?: return
        val perception = ScreenFrameAnalyzer.analyzeBitmap(
            bitmap = bitmap,
            previous = lastPerception,
            hudTopY = profile.hudTopY,
            hudBottomY = profile.hudBottomY
        )
        bitmap.recycle()

        val telemetry = BotServiceBridge.telemetry.value
        val prev = lastPerception
        val prevAction = lastAction

        if (perception.fullDelta < 0.01f) staleFrames++ else staleFrames = 0

        var stepReward = 0f
        if (prev != null && prevAction != null) {
            val assessment = ScreenFrameAnalyzer.evaluateScreenTransition(
                previous = prev,
                current = perception,
                uiTexts = telemetry.detectedUiTexts,
                staleFramesCount = staleFrames
            )
            stepReward = assessment.reward
            currentEpisodeReward += stepReward

            app.trainingEngine.recordStep(
                profileId = profile.id,
                episodeNumber = currentEpisode,
                stepIndex = currentStep,
                stateHash = prev.stateHash,
                stateSummary = prev.summary,
                action = prevAction,
                reward = stepReward,
                nextStateHash = perception.stateHash,
                isTerminal = assessment.isTerminal,
                availableActions = BotAction.LIVE_SCREEN_ACTIONS,
                learningRate = profile.learningRate,
                discountFactor = profile.discountFactor,
                source = "LIVE_SCREEN",
                screenNotes = "${telemetry.activeForegroundPackage}: ${assessment.reason}"
            )

            if (assessment.isTerminal) {
                finalizeLiveEpisode(app, profile.id, currentEpisodeReward, BotAction.LIVE_SCREEN_ACTIONS, "LIVE_SCREEN")
                currentEpisode++
                currentStep = 0
                currentEpisodeReward = 0f
                episodeStartMs = System.currentTimeMillis()
            }
        }

        val decision = app.trainingEngine.selectAction(
            profileId = profile.id,
            stateHash = perception.stateHash,
            availableActions = BotAction.LIVE_SCREEN_ACTIONS,
            epsilon = profile.explorationRate
        )

        val a11y = GameBotAccessibilityService.instance
        val isOverOwnApp = telemetry.activeForegroundPackage == packageName
        if (a11y != null && !isOverOwnApp) {
            when (decision.action) {
                BotAction.MOVE_LEFT -> a11y.dispatchNormalizedTap(profile.tapLeftX, profile.tapLeftY)
                BotAction.MOVE_RIGHT -> a11y.dispatchNormalizedTap(profile.tapRightX, profile.tapRightY)
                BotAction.TAP_CENTER -> a11y.dispatchNormalizedTap(profile.tapCenterX, profile.tapCenterY)
                BotAction.SWIPE_UP -> a11y.dispatchNormalizedSwipe(
                    profile.tapCenterX, 0.72f, profile.tapCenterX, 0.34f
                )
                BotAction.SWIPE_DOWN -> a11y.dispatchNormalizedSwipe(
                    profile.tapCenterX, 0.38f, profile.tapCenterX, 0.76f
                )
                else -> Unit
            }
        }

        currentStep++
        lastPerception = perception
        lastAction = decision.action

        val logLine = "Round $currentEpisode #$currentStep [${decision.action.shortCode}] r=${"%.2f".format(stepReward)} (${telemetry.activeForegroundPackage.substringAfterLast('.')})"
        BotServiceBridge.updateLiveDecision(
            visionGrid = perception.gridCells,
            stateHash = perception.stateHash,
            action = decision.action,
            reward = stepReward,
            episodeCount = currentEpisode,
            stepCount = currentStep,
            episodeReward = currentEpisodeReward,
            note = logLine
        )

        if (currentStep % 10 == 0) {
            app.trainingEngine.flushToDatabase(profile.id)
            updateNotification("Round $currentEpisode • Step $currentStep • ${decision.action.labelEn}")
        }
    }

    private suspend fun finalizeLiveEpisode(
        app: NeuroBotApplication,
        profileId: Long,
        episodeReward: Float,
        actions: List<BotAction>,
        source: String
    ) {
        app.trainingEngine.flushToDatabase(profileId)
        val profile = app.repository.getProfile(profileId) ?: return
        val approxScore = (episodeReward * 10f).roundToInt().coerceAtLeast(0)
        val nextEpsilon = if (profile.autoDecayEpsilon) {
            (profile.explorationRate * 0.95f).coerceAtLeast(0.05f)
        } else {
            profile.explorationRate
        }
        app.repository.insertSession(
            TrainingSessionEntity(
                profileId = profileId,
                episodeNumber = currentEpisode,
                stepsCount = currentStep,
                totalReward = episodeReward,
                score = approxScore,
                explorationRateUsed = profile.explorationRate,
                source = source,
                durationMs = System.currentTimeMillis() - episodeStartMs
            )
        )
        val replay = app.trainingEngine.runExperienceReplay(
            profileId = profileId,
            learningRate = profile.learningRate,
            discountFactor = profile.discountFactor,
            availableActions = actions,
            batchSize = 36,
            epochs = 2
        )
        app.repository.updateProfile(
            profile.copy(
                totalEpisodes = profile.totalEpisodes + 1,
                totalSteps = profile.totalSteps + currentStep,
                bestScore = maxOf(profile.bestScore, approxScore),
                lastScore = approxScore,
                explorationRate = nextEpsilon,
                totalReplayUpdates = profile.totalReplayUpdates + replay.qUpdatesApplied,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun captureLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val fullBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                fullBitmap
            } else {
                val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height)
                fullBitmap.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun getDisplayMetricsInfo(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val densityDpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width().coerceAtLeast(360), bounds.height().coerceAtLeast(640), densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels.coerceAtLeast(360), metrics.heightPixels.coerceAtLeast(640), metrics.densityDpi)
        }
    }

    private fun stopCaptureAndService() {
        automationJob?.cancel()
        automationJob = null
        releaseProjectionResources()
        BotServiceBridge.setScreenCaptureState(active = false, running = false, paused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun releaseProjectionResources() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_bot_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_bot_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val replayIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_RUN_REPLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseLabel = if (isPaused) getString(R.string.action_resume_bot) else getString(R.string.action_pause_bot)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title_active))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, pauseLabel, pauseIntent)
            .addAction(0, getString(R.string.action_train_replay), replayIntent)
            .addAction(0, getString(R.string.action_stop_bot), stopIntent)
            .build()
    }

    override fun onDestroy() {
        automationJob?.cancel()
        releaseProjectionResources()
        serviceScope.cancel()
        super.onDestroy()
    }
}
