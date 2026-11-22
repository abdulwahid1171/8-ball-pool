package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Compact expandable floating bot controller attached via TYPE_ACCESSIBILITY_OVERLAY
 * (requires zero SYSTEM_ALERT_WINDOW permission). Starts as a small draggable 50dp orb
 * with live Force % and expands on tap into a control card with ON/OFF, Force meter,
 * Force presets, and ball/turn telemetry.
 */
class BotFloatingOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootContainer: FrameLayout? = null
    private var miniOrbView: LinearLayout? = null
    private var expandedCardView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAttached = false
    private var isExpanded = false

    // Mini orb views
    private var miniForceText: TextView? = null
    private var miniStatusDot: TextView? = null
    private var miniBgDrawable: GradientDrawable? = null

    // Expanded panel views
    private var headerStatusText: TextView? = null
    private var visionStatusText: TextView? = null
    private var forcePercentText: TextView? = null
    private var forceProgressBar: ProgressBar? = null
    private var lastForceDetailText: TextView? = null
    private var onOffButton: TextView? = null
    private var presetAutoBtn: TextView? = null
    private var presetLowBtn: TextView? = null
    private var presetMedBtn: TextView? = null
    private var presetHighBtn: TextView? = null

    private var currentTelemetry: LiveBotTelemetry = BotServiceBridge.telemetry.value

    fun showOrUpdate(telemetry: LiveBotTelemetry) {
        currentTelemetry = telemetry
        mainHandler.post {
            if (!telemetry.isScreenCaptureActive) {
                hideInternal()
                return@post
            }
            if (!isAttached) {
                attachInternal()
            }
            bindTelemetry(telemetry)
        }
    }

    fun hide() {
        mainHandler.post {
            hideInternal()
        }
    }

    private fun attachInternal() {
        if (isAttached) return
        val container = FrameLayout(context)

        val orb = buildMiniOrb()
        val card = buildExpandedCard()
        card.visibility = if (isExpanded) View.VISIBLE else View.GONE
        orb.visibility = if (isExpanded) View.GONE else View.VISIBLE

        container.addView(orb)
        container.addView(card)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(76)
        }

        try {
            windowManager.addView(container, params)
            rootContainer = container
            miniOrbView = orb
            expandedCardView = card
            layoutParams = params
            isAttached = true
        } catch (_: Exception) {
            // Ignore if Accessibility window token is not yet ready
        }
    }

    private fun hideInternal() {
        if (!isAttached) return
        try {
            rootContainer?.let { windowManager.removeViewImmediate(it) }
        } catch (_: Exception) {
        }
        rootContainer = null
        miniOrbView = null
        expandedCardView = null
        layoutParams = null
        isAttached = false
        isExpanded = false
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        miniOrbView?.visibility = if (isExpanded) View.GONE else View.VISIBLE
        expandedCardView?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        clampParamsInsideScreen()
    }

    private fun buildMiniOrb(): LinearLayout {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E60B1220"))
            setStroke(dp(2), Color.parseColor("#10B981"))
        }
        miniBgDrawable = bg

        val orb = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg
            elevation = dp(6).toFloat()
            val sizePx = dp(50)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val dot = TextView(context).apply {
            text = "BOT"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        miniStatusDot = dot

        val forceTxt = TextView(context).apply {
            text = "AUTO"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        miniForceText = forceTxt

        orb.addView(dot)
        orb.addView(forceTxt)
        attachDragAndTapListener(orb, onTap = { toggleExpanded() })
        return orb
    }

    private fun buildExpandedCard(): LinearLayout {
        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#ED0B1220"))
            setStroke(dp(1) + 1, Color.parseColor("#38BDF8"))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(248), FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        // Top Header (Draggable + Minimize)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        val titleTxt = TextView(context).apply {
            text = "NeuroBot • ON"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerStatusText = titleTxt

        val minimizeBtn = makePillButton(
            label = "—",
            bgColor = "#1E293B",
            textColor = "#E2E8F0",
            padH = 10,
            padV = 4
        ).apply {
            setOnClickListener { toggleExpanded() }
        }

        headerRow.addView(titleTxt)
        headerRow.addView(minimizeBtn)
        attachDragAndTapListener(headerRow, onTap = { toggleExpanded() })
        card.addView(headerRow)

        // Vision & Turn line
        val visionTxt = TextView(context).apply {
            text = "Cue: Scanning... | Turn: WAIT"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(6))
        }
        visionStatusText = visionTxt
        card.addView(visionTxt)

        // Force / Power Meter Section
        val forceHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val forceTitle = TextView(context).apply {
            text = "Shot Force (فورس)"
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val forceVal = TextView(context).apply {
            text = "0%"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        }
        forcePercentText = forceVal
        forceHeaderRow.addView(forceTitle)
        forceHeaderRow.addView(forceVal)
        card.addView(forceHeaderRow)

        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 60
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        forceProgressBar = bar
        card.addView(bar)

        val lastForceTxt = TextView(context).apply {
            text = "Last Shot: --% | Cut: --° | Friction: 0.52"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, dp(8))
        }
        lastForceDetailText = lastForceTxt
        card.addView(lastForceTxt)

        // Force Presets Row
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val pAuto = makePresetChip("Auto", null).also { presetAutoBtn = it }
        val pLow = makePresetChip("35%", 0.35f).also { presetLowBtn = it }
        val pMed = makePresetChip("65%", 0.65f).also { presetMedBtn = it }
        val pHigh = makePresetChip("90%", 0.90f).also { presetHighBtn = it }
        presetRow.addView(pAuto)
        presetRow.addView(spaceX(4))
        presetRow.addView(pLow)
        presetRow.addView(spaceX(4))
        presetRow.addView(pMed)
        presetRow.addView(spaceX(4))
        presetRow.addView(pHigh)
        card.addView(presetRow)

        // ON/OFF, Shoot Now, Stop Controls Row
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val toggleBtn = makePillButton(
            label = "Pause",
            bgColor = "#F59E0B",
            textColor = "#070B14",
            padH = 10,
            padV = 7
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            setOnClickListener {
                sendServiceAction(ScreenCaptureService.ACTION_TOGGLE_PAUSE)
            }
        }
        onOffButton = toggleBtn

        val shootBtn = makePillButton(
            label = "Shoot",
            bgColor = "#06B6D4",
            textColor = "#070B14",
            padH = 8,
            padV = 7
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                sendServiceAction(ScreenCaptureService.ACTION_SHOOT_NOW)
            }
        }

        val stopBtn = makePillButton(
            label = "OFF",
            bgColor = "#EF4444",
            textColor = "#FFFFFF",
            padH = 8,
            padV = 7
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f)
            setOnClickListener {
                sendServiceAction(ScreenCaptureService.ACTION_STOP)
            }
        }

        btnRow.addView(toggleBtn)
        btnRow.addView(spaceX(6))
        btnRow.addView(shootBtn)
        btnRow.addView(spaceX(6))
        btnRow.addView(stopBtn)
        card.addView(btnRow)

        return card
    }

    private fun makePresetChip(label: String, forceValue: Float?): TextView {
        return makePillButton(
            label = label,
            bgColor = "#1E293B",
            textColor = "#CBD5E1",
            padH = 6,
            padV = 5
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                BotServiceBridge.setManualPowerOverride(forceValue)
            }
        }
    }

    private fun makePillButton(
        label: String,
        bgColor: String,
        textColor: String,
        padH: Int,
        padV: Int
    ): TextView {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(bgColor))
        }
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor(textColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = bg
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        }
    }

    private fun bindTelemetry(t: LiveBotTelemetry) {
        val isPaused = t.isLiveBotPaused
        val accentColor = if (isPaused) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")

        miniBgDrawable?.setStroke(dp(2), accentColor)
        miniStatusDot?.text = if (isPaused) "OFF" else "ON"
        miniStatusDot?.setTextColor(accentColor)

        val displayForce = t.manualPowerOverride ?: t.plannedPowerFraction.takeIf { it > 0.01f } ?: t.lastShotPowerFraction
        val forcePct = (displayForce * 100f).toInt().coerceIn(0, 100)
        miniForceText?.text = if (forcePct > 0) "${forcePct}%" else "AUTO"

        headerStatusText?.text = if (isPaused) "NeuroBot • PAUSED" else "NeuroBot • ACTIVE"
        headerStatusText?.setTextColor(accentColor)

        val turnLabel = if (t.isMyTurnDetected) "YOUR TURN" else "WAIT"
        val cueMark = if (t.isCueBallDetected) "Cue✓" else "Cue✗"
        val pocketStr = t.plannedPocketId ?: "-"
        visionStatusText?.text = "${t.captureSizeLabel} • $turnLabel | $cueMark | Balls:${t.detectedTargetBallsCount} | Pkt:$pocketStr"

        val modeSuffix = if (t.manualPowerOverride != null) "Manual" else "AI Auto"
        forcePercentText?.text = "${forcePct}% ($modeSuffix)"
        forceProgressBar?.progress = forcePct

        val lastPct = (t.lastShotPowerFraction * 100f).toInt()
        lastForceDetailText?.text =
            "Last: ${if (lastPct > 0) "$lastPct%" else "--"} • Cut: ${t.plannedCutAngleDeg.toInt()}° • Fric: ${"%.2f".format(t.estimatedFriction)}"

        onOffButton?.text = if (isPaused) "Turn ON" else "Pause"
        val btnBg = onOffButton?.background as? GradientDrawable
        btnBg?.setColor(Color.parseColor(if (isPaused) "#10B981" else "#F59E0B"))

        highlightPreset(presetAutoBtn, t.manualPowerOverride == null)
        highlightPreset(presetLowBtn, t.manualPowerOverride != null && abs(t.manualPowerOverride - 0.35f) < 0.05f)
        highlightPreset(presetMedBtn, t.manualPowerOverride != null && abs(t.manualPowerOverride - 0.65f) < 0.05f)
        highlightPreset(presetHighBtn, t.manualPowerOverride != null && abs(t.manualPowerOverride - 0.90f) < 0.05f)
    }

    private fun highlightPreset(btn: TextView?, selected: Boolean) {
        val bg = btn?.background as? GradientDrawable ?: return
        bg.setColor(Color.parseColor(if (selected) "#0EA5E9" else "#1E293B"))
        btn.setTextColor(Color.parseColor(if (selected) "#070B14" else "#CBD5E1"))
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(context, ScreenCaptureService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun attachDragAndTapListener(view: View, onTap: () -> Unit) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    clampParamsInsideScreen()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampParamsInsideScreen() {
        val params = layoutParams ?: return
        val container = rootContainer ?: return
        val dm = context.resources.displayMetrics
        val maxW = (dm.widthPixels - dp(56)).coerceAtLeast(100)
        val maxH = (dm.heightPixels - dp(56)).coerceAtLeast(100)
        params.x = params.x.coerceIn(0, maxW)
        params.y = params.y.coerceIn(0, maxH)
        try {
            windowManager.updateViewLayout(container, params)
        } catch (_: Exception) {
        }
    }

    private fun spaceX(widthDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
