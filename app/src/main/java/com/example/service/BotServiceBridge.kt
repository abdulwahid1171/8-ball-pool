package com.example.service

import com.example.domain.rl.BotAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LiveBotTelemetry(
    val isAccessibilityConnected: Boolean = false,
    val isScreenCaptureActive: Boolean = false,
    val isLiveBotRunning: Boolean = false,
    val isLiveBotPaused: Boolean = false,
    val activeForegroundPackage: String = "None",
    val clickableNodeCount: Int = 0,
    val detectedUiTexts: List<String> = emptyList(),
    val latestVisionGrid: List<Float> = List(36) { 0f },
    val latestStateHash: String = "IDLE",
    val latestAction: BotAction? = null,
    val latestReward: Float = 0f,
    val liveEpisodeCount: Int = 0,
    val liveStepCount: Int = 0,
    val liveEpisodeReward: Float = 0f,
    val isMyTurnDetected: Boolean = false,
    val detectedTargetBallsCount: Int = 0,
    val poolAnalysisSummary: String = "Waiting for external game screen...",
    val cueBallX: Float? = null,
    val cueBallY: Float? = null,
    val targetBallX: Float? = null,
    val targetBallY: Float? = null,
    val plannedPocketId: String? = null,
    val liveLogLines: List<String> = emptyList()
)

object BotServiceBridge {

    private val _telemetry = MutableStateFlow(LiveBotTelemetry())
    val telemetry: StateFlow<LiveBotTelemetry> = _telemetry.asStateFlow()

    fun setAccessibilityConnected(connected: Boolean) {
        _telemetry.update { it.copy(isAccessibilityConnected = connected) }
        appendLog(if (connected) "Accessibility gesture controller connected." else "Accessibility service disconnected.")
    }

    fun setScreenCaptureState(active: Boolean, running: Boolean, paused: Boolean) {
        _telemetry.update {
            it.copy(
                isScreenCaptureActive = active,
                isLiveBotRunning = running,
                isLiveBotPaused = paused
            )
        }
    }

    fun updateForegroundUiContext(
        packageName: String,
        clickableNodes: Int,
        uiTexts: List<String>
    ) {
        _telemetry.update {
            it.copy(
                activeForegroundPackage = packageName,
                clickableNodeCount = clickableNodes,
                detectedUiTexts = uiTexts
            )
        }
    }

    fun updatePoolObservation(
        visionGrid: List<Float>,
        stateHash: String,
        isMyTurn: Boolean,
        targetsCount: Int,
        cueX: Float?,
        cueY: Float?,
        targetX: Float?,
        targetY: Float?,
        pocketId: String?,
        summary: String
    ) {
        _telemetry.update {
            it.copy(
                latestVisionGrid = visionGrid,
                latestStateHash = stateHash,
                isMyTurnDetected = isMyTurn,
                detectedTargetBallsCount = targetsCount,
                cueBallX = cueX,
                cueBallY = cueY,
                targetBallX = targetX,
                targetBallY = targetY,
                plannedPocketId = pocketId,
                poolAnalysisSummary = summary
            )
        }
    }

    fun updateLiveDecision(
        visionGrid: List<Float>,
        stateHash: String,
        action: BotAction,
        reward: Float,
        episodeCount: Int,
        stepCount: Int,
        episodeReward: Float,
        note: String
    ) {
        _telemetry.update { current ->
            val updatedLogs = (listOf(note) + current.liveLogLines).take(25)
            current.copy(
                latestVisionGrid = visionGrid,
                latestStateHash = stateHash,
                latestAction = action,
                latestReward = reward,
                liveEpisodeCount = episodeCount,
                liveStepCount = stepCount,
                liveEpisodeReward = episodeReward,
                liveLogLines = updatedLogs
            )
        }
    }

    fun appendLog(message: String) {
        _telemetry.update { current ->
            val updated = (listOf(message) + current.liveLogLines).take(25)
            current.copy(liveLogLines = updated)
        }
    }
}
