package com.retro.mgba.overlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.retro.mgba.core.NativeCore
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Responsive touch overlay providing a virtual GBA controller.
 * - Dynamic layout adaptation for both Landscape and Portrait orientations.
 * - When controlsVisible is false: completely hidden (clean screen for gamepads/handhelds).
 * - When controlsVisible is true: all controls including Menu button are shown.
 * - Supports two D-Pad modes:
 *   1. DPAD_TYPE_CLASSIC: Authentic 4-way cross with arrow markers and 8-way diagonal detection.
 *   2. DPAD_TYPE_STICK: Virtual analog stick / wheel ("колёсико") with movable thumb nub.
 * - Multi-touch tracking with zero key sticking (strict DOWN, MOVE, UP, CANCEL handling).
 * - Zero vibration / zero haptics.
 */
class TouchOverlayView(context: Context) : View(context) {

    companion object {
        const val KEY_MENU_BUTTON = -999
        const val DPAD_TYPE_CLASSIC = 0
        const val DPAD_TYPE_STICK   = 1
    }

    var onKeyStateChanged: ((Int) -> Unit)? = null
    var onMenuClick: (() -> Unit)? = null

    // Visibility and opacity configuration
    var controlsVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var dpadType: Int = DPAD_TYPE_CLASSIC
        set(value) {
            field = value
            invalidate()
        }

    var controlsOpacity: Float = 0.55f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            updatePaints()
            invalidate()
        }

    private data class ButtonDef(
        val key: Int,
        val label: String,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var r: Float = 0f
    )

    // D-Pad configuration
    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadSize = 0f

    // Analog Stick / Wheel state
    private var stickNubX = 0f
    private var stickNubY = 0f
    private var stickPointerId = -1

    // Menu button
    private val menuButton = ButtonDef(KEY_MENU_BUTTON, "MENU", 0f, 0f, 0f)

    // Standard Buttons: A, B, LB, RB, Start, Select
    private val buttons = mutableListOf<ButtonDef>()

    // Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stickBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stickNubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Multi-touch tracking: pointerId -> Set of active keys pressed by that finger
    private val pointerKeys = mutableMapOf<Int, Set<Int>>()
    private var lastKeyMask = 0

    init {
        updatePaints()
    }

    private fun updatePaints() {
        val alphaInt = (controlsOpacity * 255).toInt()
        fillPaint.color = Color.argb(alphaInt, 26, 26, 48)
        strokePaint.color = Color.argb(alphaInt, 110, 110, 200)
        textPaint.color = Color.argb((alphaInt * 1.3f).toInt().coerceAtMost(255), 230, 230, 255)
        pressedFill.color = Color.argb((alphaInt * 1.4f).toInt().coerceAtMost(255), 80, 80, 210)
        menuPaint.color = Color.argb((alphaInt * 0.9f).toInt().coerceAtMost(255), 45, 45, 80)
        stickBasePaint.color = Color.argb((alphaInt * 0.6f).toInt().coerceAtMost(255), 20, 20, 36)
        stickNubPaint.color = Color.argb((alphaInt * 1.2f).toInt().coerceAtMost(255), 70, 70, 130)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        buttons.clear()
        val isPortrait = h > w
        val minDim = minOf(w, h).toFloat()

        if (isPortrait) {
            // === PORTRAIT ORIENTATION ===
            val gameScreenHeight = (w * 160f / 240f)
            val ctrlTop = gameScreenHeight + (8f * resources.displayMetrics.density)
            val ctrlHeight = h - ctrlTop

            // Menu & Shoulder Row
            menuButton.cx = w * 0.50f
            menuButton.cy = ctrlTop + ctrlHeight * 0.10f
            menuButton.r = minDim * 0.058f

            buttons.add(ButtonDef(NativeCore.KEY_L, "LB", w * 0.16f, ctrlTop + ctrlHeight * 0.10f, minDim * 0.075f))
            buttons.add(ButtonDef(NativeCore.KEY_R, "RB", w * 0.84f, ctrlTop + ctrlHeight * 0.10f, minDim * 0.075f))

            // D-Pad on lower left
            dpadCx = w * 0.26f
            dpadCy = ctrlTop + ctrlHeight * 0.45f
            dpadSize = minDim * 0.19f

            // A & B Buttons on lower right (Authentic GBA diagonal layout)
            buttons.add(ButtonDef(NativeCore.KEY_B, "B", w * 0.70f, ctrlTop + ctrlHeight * 0.52f, minDim * 0.095f))
            buttons.add(ButtonDef(NativeCore.KEY_A, "A", w * 0.86f, ctrlTop + ctrlHeight * 0.38f, minDim * 0.095f))

            // Select & Start
            buttons.add(ButtonDef(NativeCore.KEY_SELECT, "SELECT", w * 0.38f, ctrlTop + ctrlHeight * 0.80f, minDim * 0.055f))
            buttons.add(ButtonDef(NativeCore.KEY_START,  "START",  w * 0.62f, ctrlTop + ctrlHeight * 0.80f, minDim * 0.055f))

        } else {
            // === LANDSCAPE ORIENTATION ===
            // D-Pad on left border
            dpadCx = w * 0.13f
            dpadCy = h * 0.68f
            dpadSize = minDim * 0.19f

            // LB & RB Shoulders on top corners
            buttons.add(ButtonDef(NativeCore.KEY_L, "LB", w * 0.07f, h * 0.22f, minDim * 0.075f))
            buttons.add(ButtonDef(NativeCore.KEY_R, "RB", w * 0.93f, h * 0.22f, minDim * 0.075f))

            // A & B Buttons on right border
            buttons.add(ButtonDef(NativeCore.KEY_B, "B", w * 0.82f, h * 0.74f, minDim * 0.095f))
            buttons.add(ButtonDef(NativeCore.KEY_A, "A", w * 0.92f, h * 0.58f, minDim * 0.095f))

            // Select & Start
            buttons.add(ButtonDef(NativeCore.KEY_SELECT, "SELECT", w * 0.40f, h * 0.92f, minDim * 0.050f))
            buttons.add(ButtonDef(NativeCore.KEY_START,  "START",  w * 0.60f, h * 0.92f, minDim * 0.050f))

            // Menu button at top center
            menuButton.cx = w * 0.50f
            menuButton.cy = h * 0.08f
            menuButton.r = minDim * 0.050f
        }

        stickNubX = dpadCx
        stickNubY = dpadCy
        textPaint.textSize = minDim * 0.034f
    }

    override fun onDraw(canvas: Canvas) {
        // When controls are disabled, hide all elements (including menu button)
        if (!controlsVisible) return

        val pressedKeys = pointerKeys.values.flatten().toSet()

        // 1. Draw Menu button
        val menuPressed = KEY_MENU_BUTTON in pressedKeys
        canvas.drawCircle(menuButton.cx, menuButton.cy, menuButton.r, if (menuPressed) pressedFill else menuPaint)
        canvas.drawCircle(menuButton.cx, menuButton.cy, menuButton.r, strokePaint)
        val menuTextSize = textPaint.textSize * 0.75f
        val oldTextSize = textPaint.textSize
        textPaint.textSize = menuTextSize
        canvas.drawText("MENU", menuButton.cx, menuButton.cy + menuTextSize * 0.35f, textPaint)
        textPaint.textSize = oldTextSize

        // 2. Draw D-Pad (Classic Cross OR Analog Stick / Wheel)
        if (dpadType == DPAD_TYPE_STICK) {
            val baseRadius = dpadSize
            val nubRadius = dpadSize * 0.42f

            // Base circular pad
            canvas.drawCircle(dpadCx, dpadCy, baseRadius, stickBasePaint)
            canvas.drawCircle(dpadCx, dpadCy, baseRadius, strokePaint)

            // Crosshair guidelines inside stick base
            canvas.drawLine(dpadCx - baseRadius * 0.8f, dpadCy, dpadCx + baseRadius * 0.8f, dpadCy, strokePaint)
            canvas.drawLine(dpadCx, dpadCy - baseRadius * 0.8f, dpadCx, dpadCy + baseRadius * 0.8f, strokePaint)

            // Movable thumb knob / nub
            val isStickActive = stickPointerId != -1
            canvas.drawCircle(stickNubX, stickNubY, nubRadius, if (isStickActive) pressedFill else stickNubPaint)
            canvas.drawCircle(stickNubX, stickNubY, nubRadius, strokePaint)
            canvas.drawCircle(stickNubX, stickNubY, nubRadius * 0.28f, strokePaint)

        } else {
            val arm = dpadSize
            val thick = arm * 0.38f

            val leftPressed  = NativeCore.KEY_LEFT  in pressedKeys
            val rightPressed = NativeCore.KEY_RIGHT in pressedKeys
            val upPressed    = NativeCore.KEY_UP    in pressedKeys
            val downPressed  = NativeCore.KEY_DOWN  in pressedKeys

            fun drawArm(rect: RectF, pressed: Boolean) {
                canvas.drawRoundRect(rect, 10f, 10f, if (pressed) pressedFill else fillPaint)
                canvas.drawRoundRect(rect, 10f, 10f, strokePaint)
            }

            // Horizontal bar
            drawArm(RectF(dpadCx - arm, dpadCy - thick / 2f, dpadCx, dpadCy + thick / 2f), leftPressed)
            drawArm(RectF(dpadCx, dpadCy - thick / 2f, dpadCx + arm, dpadCy + thick / 2f), rightPressed)

            // Vertical bar
            drawArm(RectF(dpadCx - thick / 2f, dpadCy - arm, dpadCx + thick / 2f, dpadCy), upPressed)
            drawArm(RectF(dpadCx - thick / 2f, dpadCy, dpadCx + thick / 2f, dpadCy + arm), downPressed)

            // Center cap
            canvas.drawCircle(dpadCx, dpadCy, thick / 2f, fillPaint)
            canvas.drawCircle(dpadCx, dpadCy, thick / 2f, strokePaint)

            // Arrow icons
            val arrowSize = dpadSize * 0.28f
            textPaint.textSize = arrowSize
            canvas.drawText("◀", dpadCx - arm * 0.58f, dpadCy + arrowSize * 0.36f, textPaint)
            canvas.drawText("▶", dpadCx + arm * 0.58f, dpadCy + arrowSize * 0.36f, textPaint)
            canvas.drawText("▲", dpadCx, dpadCy - arm * 0.44f, textPaint)
            canvas.drawText("▼", dpadCx, dpadCy + arm * 0.76f, textPaint)

            textPaint.textSize = minOf(width, height) * 0.034f
        }

        // 3. Draw Buttons (A, B, LB, RB, Start, Select)
        buttons.forEach { btn ->
            val pressed = btn.key in pressedKeys
            canvas.drawCircle(btn.cx, btn.cy, btn.r, if (pressed) pressedFill else fillPaint)
            canvas.drawCircle(btn.cx, btn.cy, btn.r, strokePaint)

            val btnTextSize = if (btn.label.length > 2) textPaint.textSize * 0.65f else textPaint.textSize
            textPaint.textSize = btnTextSize
            canvas.drawText(btn.label, btn.cx, btn.cy + btnTextSize * 0.36f, textPaint)
            textPaint.textSize = minOf(width, height) * 0.034f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!controlsVisible) return false

        val action = event.actionMasked
        val pIdx   = event.actionIndex
        val pId    = event.getPointerId(pIdx)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                pointerKeys.clear()
                val keys = resolveKeys(event.getX(0), event.getY(0), event.getPointerId(0))
                if (KEY_MENU_BUTTON in keys) {
                    onMenuClick?.invoke()
                } else {
                    pointerKeys[event.getPointerId(0)] = keys
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val keys = resolveKeys(event.getX(pIdx), event.getY(pIdx), pId)
                if (KEY_MENU_BUTTON in keys) {
                    onMenuClick?.invoke()
                } else {
                    pointerKeys[pId] = keys
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val activeIds = HashSet<Int>(event.pointerCount)
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    activeIds.add(id)
                    val keys = resolveKeys(event.getX(i), event.getY(i), id)
                    if (KEY_MENU_BUTTON !in keys) {
                        pointerKeys[id] = keys
                    }
                }
                pointerKeys.keys.retainAll(activeIds)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pointerKeys.remove(pId)
                if (pId == stickPointerId) {
                    stickPointerId = -1
                    stickNubX = dpadCx
                    stickNubY = dpadCy
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerKeys.clear()
                stickPointerId = -1
                stickNubX = dpadCx
                stickNubY = dpadCy
            }
        }

        var mask = 0
        for (set in pointerKeys.values) {
            for (k in set) {
                if (k != KEY_MENU_BUTTON) mask = mask or k
            }
        }

        if (mask != lastKeyMask) {
            lastKeyMask = mask
            onKeyStateChanged?.invoke(mask)
        }

        invalidate()
        return true
    }

    private fun resolveKeys(x: Float, y: Float, pointerId: Int): Set<Int> {
        val result = mutableSetOf<Int>()

        // 1. Check Menu Button
        if (hypot(x - menuButton.cx, y - menuButton.cy) <= menuButton.r * 1.4f) {
            result.add(KEY_MENU_BUTTON)
            return result
        }

        // 2. Check D-Pad
        val dx = x - dpadCx
        val dy = y - dpadCy
        val dist = hypot(dx, dy)

        if (dpadType == DPAD_TYPE_STICK) {
            val maxTravel = dpadSize * 0.65f
            if (dist <= dpadSize * 1.40f || pointerId == stickPointerId) {
                stickPointerId = pointerId
                if (dist > maxTravel) {
                    stickNubX = dpadCx + (dx / dist) * maxTravel
                    stickNubY = dpadCy + (dy / dist) * maxTravel
                } else {
                    stickNubX = x
                    stickNubY = y
                }

                if (dist >= dpadSize * 0.18f) {
                    val angle = atan2(dy, dx) * (180.0 / Math.PI)
                    mapAngleToDirections(angle, result)
                }
            }
        } else {
            if (dist >= dpadSize * 0.15f && dist <= dpadSize * 1.40f) {
                val angle = atan2(dy, dx) * (180.0 / Math.PI)
                mapAngleToDirections(angle, result)
            }
        }

        // 3. Check Action and Shoulder Buttons
        buttons.forEach { btn ->
            val d = hypot(x - btn.cx, y - btn.cy)
            val hitRadius = if (btn.label.length > 2) btn.r * 1.5f else btn.r * 1.35f
            if (d <= hitRadius) {
                result.add(btn.key)
            }
        }

        return result
    }

    private fun mapAngleToDirections(angle: Double, out: MutableSet<Int>) {
        if (angle >= -22.5 && angle < 22.5) {
            out.add(NativeCore.KEY_RIGHT)
        } else if (angle >= 22.5 && angle < 67.5) {
            out.add(NativeCore.KEY_RIGHT)
            out.add(NativeCore.KEY_DOWN)
        } else if (angle >= 67.5 && angle < 112.5) {
            out.add(NativeCore.KEY_DOWN)
        } else if (angle >= 112.5 && angle < 157.5) {
            out.add(NativeCore.KEY_DOWN)
            out.add(NativeCore.KEY_LEFT)
        } else if (angle >= 157.5 || angle < -157.5) {
            out.add(NativeCore.KEY_LEFT)
        } else if (angle >= -157.5 && angle < -112.5) {
            out.add(NativeCore.KEY_LEFT)
            out.add(NativeCore.KEY_UP)
        } else if (angle >= -112.5 && angle < -67.5) {
            out.add(NativeCore.KEY_UP)
        } else if (angle >= -67.5 && angle < -22.5) {
            out.add(NativeCore.KEY_UP)
            out.add(NativeCore.KEY_RIGHT)
        }
    }

    fun clearAllKeys() {
        pointerKeys.clear()
        stickPointerId = -1
        stickNubX = dpadCx
        stickNubY = dpadCy
        if (lastKeyMask != 0) {
            lastKeyMask = 0
            onKeyStateChanged?.invoke(0)
            invalidate()
        }
    }
}
