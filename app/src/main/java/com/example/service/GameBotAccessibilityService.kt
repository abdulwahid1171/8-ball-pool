package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GameBotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GameBotAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BotServiceBridge.setAccessibilityConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        val root = rootInActiveWindow
        val texts = mutableListOf<String>()
        var clickableCount = 0

        if (root != null) {
            clickableCount = collectUiSummary(root, texts, maxTexts = 18)
        }

        val resolvedPkg = pkg ?: root?.packageName?.toString() ?: return
        if (resolvedPkg != packageName) {
            BotServiceBridge.updateForegroundUiContext(
                packageName = resolvedPkg,
                clickableNodes = clickableCount,
                uiTexts = texts
            )
        } else if (texts.isNotEmpty()) {
            BotServiceBridge.updateForegroundUiContext(
                packageName = resolvedPkg,
                clickableNodes = clickableCount,
                uiTexts = texts
            )
        }
    }

    override fun onInterrupt() {
        BotServiceBridge.appendLog("Accessibility service interrupted.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        BotServiceBridge.setAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        BotServiceBridge.setAccessibilityConnected(false)
        super.onDestroy()
    }

    fun dispatchNormalizedTap(normX: Float, normY: Float, onResult: ((Boolean) -> Unit)? = null) {
        val (w, h) = getScreenSizePx()
        val px = (normX.coerceIn(0.02f, 0.98f) * w)
        val py = (normY.coerceIn(0.02f, 0.98f) * h)

        val path = Path().apply {
            moveTo(px, py)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onResult?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onResult?.invoke(false)
            }
        }, null)
    }

    fun dispatchNormalizedSwipe(
        startNormX: Float,
        startNormY: Float,
        endNormX: Float,
        endNormY: Float,
        durationMs: Long = 220L,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val (w, h) = getScreenSizePx()
        val sx = startNormX.coerceIn(0.05f, 0.95f) * w
        val sy = startNormY.coerceIn(0.05f, 0.95f) * h
        val ex = endNormX.coerceIn(0.05f, 0.95f) * w
        val ey = endNormY.coerceIn(0.05f, 0.95f) * h

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(80L, 600L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onResult?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onResult?.invoke(false)
            }
        }, null)
    }

    private fun getScreenSizePx(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
        }
    }

    private fun collectUiSummary(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        maxTexts: Int,
        depth: Int = 0
    ): Int {
        if (depth > 8) return 0
        var clickable = if (node.isClickable) 1 else 0
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty() && texts.size < maxTexts) {
            texts.add(txt.take(36))
        } else {
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && texts.size < maxTexts) {
                texts.add(desc.take(36))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            clickable += collectUiSummary(child, texts, maxTexts, depth + 1)
            @Suppress("DEPRECATION")
            child.recycle()
        }
        return clickable
    }
}
