package com.retro.mgba.overlay

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.retro.mgba.core.NativeCore
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Responsive touch overlay providing a virtual GBA/GBC controller with:
 * - 4 visual themes / console skins (Modern Transparent, GBA Indigo, GBA SP Graphite, GBC Atomic Purple).
 * - Classic 4-way cross D-Pad OR virtual analog thumbstick.
 * - Optional on-screen Fast Forward (toggle) and Rewind (hold) action pills.
 * - Optional crisp haptic feedback on key-down edges.
 * - Multi-touch tracking with zero stuck keys across DOWN/MOVE/UP/CANCEL.
 */
class TouchOverlayView(context: Context) : View(context) {

    companion object {
        const val KEY_MENU_BUTTON   = -999
        const val KEY_FF_BUTTON     = -998
        const val KEY_REWIND_BUTTON = -997

        const val DPAD_TYPE_CLASSIC = 0
        const val DPAD_TYPE_STICK   = 1

        const val SKIN_MODERN_TRANSPARENT = 0
        const val SKIN_GBA_INDIGO         = 1
        const val SKIN_GBA_SP_GRAPHITE    = 2
        const val SKIN_GBC_ATOMIC_PURPLE  = 3
        const val SKIN_CUSTOM             = 4
    }

    var onKeyStateChanged: ((Int) -> Unit)? = null
    var onMenuClick: (() -> Unit)? = null
    var onFastForwardToggle: ((Boolean) -> Unit)? = null
    var onRewindStateChanged: ((Boolean) -> Unit)? = null

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
            field = value.coerceIn(0.15f, 1.0f)
            updatePaints()
            invalidate()
        }

    var consoleSkin: Int = SKIN_MODERN_TRANSPARENT
        set(value) {
            field = value.coerceIn(0, 4)
            updatePaints()
            invalidate()
        }

    var controlsScale: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.8f, 1.3f)
            if (field != clamped) {
                field = clamped
                if (width > 0 && height > 0) {
                    onSizeChanged(width, height, width, height)
                }
                invalidate()
            }
        }

    var customShellColor: Int = Color.rgb(34, 24, 62)
        set(value) {
            field = value
            updatePaints()
            invalidate()
        }

    var customButtonColor: Int = Color.rgb(66, 52, 108)
        set(value) {
            field = value
            updatePaints()
            invalidate()
        }

    var customAccentColor: Int = Color.rgb(139, 92, 246)
        set(value) {
            field = value
            updatePaints()
            invalidate()
        }

    var showQuickActions: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var touchHapticsEnabled: Boolean = false

    var isFastForwardActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var isRewindActive: Boolean = false
        private set

    private data class ButtonDef(
        val key: Int,
        val label: String,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var r: Float = 0f
    )

    // Layout geometry
    private var gameScreenBottom = 0f
    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadSize = 0f

    // Analog Stick state
    private var stickNubX = 0f
    private var stickNubY = 0f
    private var stickPointerId = -1

    // Special action buttons
    private val menuButton   = ButtonDef(KEY_MENU_BUTTON, "MENU")
    private val ffButton     = ButtonDef(KEY_FF_BUTTON, "FF")
    private val rewindButton = ButtonDef(KEY_REWIND_BUTTON, "REW")

    // GBA/GB buttons: A, B, L, R, Start, Select
    private val buttons = mutableListOf<ButtonDef>()

    // Paints
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val detailLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val speakerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val abFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val activePillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stickBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stickNubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Multi-touch tracking
    private val pointerKeys = mutableMapOf<Int, Set<Int>>()
    private var lastKeyMask = 0

    init {
        updatePaints()
    }

    private fun isPortraitHardwareSkin(): Boolean {
        return height > width && consoleSkin != SKIN_MODERN_TRANSPARENT
    }

    private fun updatePaints() {
        val opaqueSkin = isPortraitHardwareSkin()
        val alphaInt = if (opaqueSkin) 245 else (controlsOpacity * 255).toInt().coerceIn(38, 255)

        when (consoleSkin) {
            SKIN_GBA_INDIGO -> {
                fillPaint.color = Color.argb(alphaInt, 44, 46, 58)
                abFillPaint.color = Color.argb(alphaInt, 78, 82, 102)
                strokePaint.color = Color.argb(alphaInt, 128, 134, 168)
                textPaint.color = Color.argb(240, 232, 235, 252)
                pressedFill.color = Color.argb(250, 124, 92, 246)
                menuPaint.color = Color.argb(alphaInt, 38, 36, 86)
                stickBasePaint.color = Color.argb(alphaInt, 32, 34, 46)
                stickNubPaint.color = Color.argb(alphaInt, 72, 76, 98)
                chinPaint.color = Color.rgb(24, 24, 32)
                ledPaint.color = Color.rgb(34, 224, 122)
                brandPaint.color = Color.argb(145, 195, 198, 230)
                speakerDotPaint.color = Color.argb(140, 16, 14, 40)
                detailLinePaint.color = Color.argb(65, 145, 150, 225)
            }
            SKIN_GBA_SP_GRAPHITE -> {
                fillPaint.color = Color.argb(alphaInt, 36, 39, 48)
                abFillPaint.color = Color.argb(alphaInt, 50, 54, 66)
                strokePaint.color = Color.argb(alphaInt, 120, 128, 148)
                textPaint.color = Color.argb(235, 224, 228, 238)
                pressedFill.color = Color.argb(250, 6, 182, 212)
                menuPaint.color = Color.argb(alphaInt, 30, 32, 42)
                stickBasePaint.color = Color.argb(alphaInt, 24, 26, 34)
                stickNubPaint.color = Color.argb(alphaInt, 64, 68, 84)
                chinPaint.color = Color.rgb(18, 20, 26)
                ledPaint.color = Color.rgb(132, 204, 22)
                brandPaint.color = Color.argb(135, 176, 184, 204)
                speakerDotPaint.color = Color.argb(190, 10, 12, 16)
                detailLinePaint.color = Color.argb(80, 150, 158, 180)
            }
            SKIN_GBC_ATOMIC_PURPLE -> {
                fillPaint.color = Color.argb(alphaInt, 42, 34, 56)
                abFillPaint.color = Color.argb(alphaInt, 134, 28, 82)
                strokePaint.color = Color.argb(alphaInt, 174, 124, 238)
                textPaint.color = Color.argb(242, 246, 235, 255)
                pressedFill.color = Color.argb(250, 236, 72, 153)
                menuPaint.color = Color.argb(alphaInt, 56, 30, 96)
                stickBasePaint.color = Color.argb(alphaInt, 36, 24, 58)
                stickNubPaint.color = Color.argb(alphaInt, 98, 60, 152)
                chinPaint.color = Color.rgb(26, 16, 44)
                ledPaint.color = Color.rgb(239, 68, 68)
                brandPaint.color = Color.argb(155, 220, 195, 255)
                speakerDotPaint.color = Color.argb(150, 22, 10, 42)
                detailLinePaint.color = Color.argb(55, 180, 130, 255)
            }
            SKIN_CUSTOM -> {
                fillPaint.color = Color.argb(alphaInt, Color.red(customButtonColor), Color.green(customButtonColor), Color.blue(customButtonColor))
                abFillPaint.color = Color.argb(alphaInt,
                    (Color.red(customButtonColor) * 0.75f + Color.red(customAccentColor) * 0.25f).toInt().coerceIn(0, 255),
                    (Color.green(customButtonColor) * 0.75f + Color.green(customAccentColor) * 0.25f).toInt().coerceIn(0, 255),
                    (Color.blue(customButtonColor) * 0.75f + Color.blue(customAccentColor) * 0.25f).toInt().coerceIn(0, 255)
                )
                strokePaint.color = Color.argb(alphaInt, Color.red(customAccentColor), Color.green(customAccentColor), Color.blue(customAccentColor))
                textPaint.color = Color.argb(245, 245, 245, 255)
                pressedFill.color = Color.argb(250, Color.red(customAccentColor), Color.green(customAccentColor), Color.blue(customAccentColor))
                menuPaint.color = Color.argb(alphaInt, Color.red(customShellColor), Color.green(customShellColor), Color.blue(customShellColor))
                stickBasePaint.color = Color.argb(alphaInt, (Color.red(customButtonColor) * 0.7f).toInt(), (Color.green(customButtonColor) * 0.7f).toInt(), (Color.blue(customButtonColor) * 0.7f).toInt())
                stickNubPaint.color = Color.argb(alphaInt, Color.red(customAccentColor), Color.green(customAccentColor), Color.blue(customAccentColor))
                chinPaint.color = Color.rgb(
                    (Color.red(customShellColor) * 0.45f).toInt(),
                    (Color.green(customShellColor) * 0.45f).toInt(),
                    (Color.blue(customShellColor) * 0.45f).toInt()
                )
                ledPaint.color = customAccentColor
                brandPaint.color = Color.argb(165, 228, 232, 248)
                speakerDotPaint.color = Color.argb(160, 12, 12, 18)
                detailLinePaint.color = Color.argb(85, Color.red(customAccentColor), Color.green(customAccentColor), Color.blue(customAccentColor))
            }
            else -> {
                fillPaint.color = Color.argb(alphaInt, 26, 26, 48)
                abFillPaint.color = Color.argb(alphaInt, 34, 30, 62)
                strokePaint.color = Color.argb(alphaInt, 118, 116, 208)
                textPaint.color = Color.argb((alphaInt * 1.3f).toInt().coerceAtMost(255), 232, 232, 255)
                pressedFill.color = Color.argb((alphaInt * 1.45f).toInt().coerceAtMost(255), 116, 86, 240)
                menuPaint.color = Color.argb((alphaInt * 0.9f).toInt().coerceAtMost(255), 42, 42, 78)
                stickBasePaint.color = Color.argb((alphaInt * 0.6f).toInt().coerceAtMost(255), 20, 20, 36)
                stickNubPaint.color = Color.argb((alphaInt * 1.2f).toInt().coerceAtMost(255), 76, 72, 140)
            }
        }

        activePillFill.color = Color.argb(235, 6, 182, 212)
        updateShellShader()
    }

    private fun updateShellShader() {
        if (width <= 0 || height <= 0 || !isPortraitHardwareSkin()) {
            shellPaint.shader = null
            return
        }
        val (topColor, bottomColor) = when (consoleSkin) {
            SKIN_GBA_INDIGO -> Color.rgb(66, 60, 158) to Color.rgb(38, 34, 102)
            SKIN_GBA_SP_GRAPHITE -> Color.rgb(42, 46, 58) to Color.rgb(22, 24, 32)
            SKIN_GBC_ATOMIC_PURPLE -> Color.rgb(82, 44, 140) to Color.rgb(42, 20, 82)
            SKIN_CUSTOM -> customShellColor to Color.rgb(
                (Color.red(customShellColor) * 0.55f).toInt(),
                (Color.green(customShellColor) * 0.55f).toInt(),
                (Color.blue(customShellColor) * 0.55f).toInt()
            )
            else -> Color.TRANSPARENT to Color.TRANSPARENT
        }
        shellPaint.shader = LinearGradient(
            0f, gameScreenBottom, 0f, height.toFloat(),
            topColor, bottomColor, Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        buttons.clear()
        val isPortrait = h > w
        val minDim = minOf(w, h).toFloat()
        val s = controlsScale

        if (isPortrait) {
            val gameScreenHeight = (w * 160f / 240f)
            gameScreenBottom = gameScreenHeight
            val chinExtra = if (consoleSkin != SKIN_MODERN_TRANSPARENT) 30f * resources.displayMetrics.density else 8f * resources.displayMetrics.density
            val ctrlTop = gameScreenHeight + chinExtra
            val ctrlHeight = (h - ctrlTop).coerceAtLeast(minDim * 0.6f)

            // Shoulder & Utility Row: [L] [REW] [MENU] [FF] [R]
            val topRowY = ctrlTop + ctrlHeight * 0.11f
            menuButton.cx = w * 0.50f
            menuButton.cy = topRowY
            menuButton.r = minDim * 0.054f

            rewindButton.cx = w * 0.34f
            rewindButton.cy = topRowY
            rewindButton.r = minDim * 0.048f

            ffButton.cx = w * 0.66f
            ffButton.cy = topRowY
            ffButton.r = minDim * 0.048f

            buttons.add(ButtonDef(NativeCore.KEY_L, "L", w * 0.13f, topRowY, minDim * 0.072f * s))
            buttons.add(ButtonDef(NativeCore.KEY_R, "R", w * 0.87f, topRowY, minDim * 0.072f * s))

            // D-Pad on lower left
            dpadCx = w * 0.26f
            dpadCy = ctrlTop + ctrlHeight * 0.48f
            dpadSize = minDim * 0.19f * s

            // A & B Buttons on lower right (Authentic GBA diagonal layout)
            buttons.add(ButtonDef(NativeCore.KEY_B, "B", w * 0.69f, ctrlTop + ctrlHeight * 0.54f, minDim * 0.092f * s))
            buttons.add(ButtonDef(NativeCore.KEY_A, "A", w * 0.86f, ctrlTop + ctrlHeight * 0.41f, minDim * 0.092f * s))

            // Select & Start
            buttons.add(ButtonDef(NativeCore.KEY_SELECT, "SELECT", w * 0.37f, ctrlTop + ctrlHeight * 0.82f, minDim * 0.055f * s))
            buttons.add(ButtonDef(NativeCore.KEY_START,  "START",  w * 0.63f, ctrlTop + ctrlHeight * 0.82f, minDim * 0.055f * s))
        } else {
            gameScreenBottom = 0f

            // D-Pad on left border
            dpadCx = w * 0.13f
            dpadCy = h * 0.68f
            dpadSize = minDim * 0.19f * s

            // L & R Shoulders on top corners
            buttons.add(ButtonDef(NativeCore.KEY_L, "L", w * 0.07f, h * 0.22f, minDim * 0.075f * s))
            buttons.add(ButtonDef(NativeCore.KEY_R, "R", w * 0.93f, h * 0.22f, minDim * 0.075f * s))

            // A & B Buttons on right border
            buttons.add(ButtonDef(NativeCore.KEY_B, "B", w * 0.82f, h * 0.74f, minDim * 0.095f * s))
            buttons.add(ButtonDef(NativeCore.KEY_A, "A", w * 0.92f, h * 0.58f, minDim * 0.095f * s))

            // Select & Start
            buttons.add(ButtonDef(NativeCore.KEY_SELECT, "SELECT", w * 0.40f, h * 0.91f, minDim * 0.050f * s))
            buttons.add(ButtonDef(NativeCore.KEY_START,  "START",  w * 0.60f, h * 0.91f, minDim * 0.050f * s))

            // Top utility bar: [REW] [MENU] [FF]
            menuButton.cx = w * 0.50f
            menuButton.cy = h * 0.09f
            menuButton.r = minDim * 0.050f

            rewindButton.cx = w * 0.38f
            rewindButton.cy = h * 0.09f
            rewindButton.r = minDim * 0.046f

            ffButton.cx = w * 0.62f
            ffButton.cy = h * 0.09f
            ffButton.r = minDim * 0.046f
        }

        stickNubX = dpadCx
        stickNubY = dpadCy
        textPaint.textSize = minDim * 0.034f
        updatePaints()
    }

    private fun drawConsoleShell(canvas: Canvas) {
        if (!isPortraitHardwareSkin()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density
        val chinHeight = 26f * density

        // 1. Main console deck gradient below the viewport
        canvas.drawRect(0f, gameScreenBottom, w, h, shellPaint)

        // 2. Dark bezel chin right below the game screen with engraved label + Power LED
        canvas.drawRect(0f, gameScreenBottom, w, gameScreenBottom + chinHeight, chinPaint)
        canvas.drawLine(0f, gameScreenBottom + chinHeight, w, gameScreenBottom + chinHeight, detailLinePaint)

        val brandLabel = when (consoleSkin) {
            SKIN_GBA_SP_GRAPHITE -> "GAME BOY ADVANCE SP"
            SKIN_GBC_ATOMIC_PURPLE -> "GAME BOY COLOR"
            else -> "GAME BOY ADVANCE"
        }
        brandPaint.textSize = 11.5f * density
        canvas.drawText(brandLabel, w * 0.5f, gameScreenBottom + chinHeight * 0.68f, brandPaint)

        // Glowing power LED on right side of chin
        val ledX = w - 22f * density
        val ledY = gameScreenBottom + chinHeight * 0.5f
        canvas.drawCircle(ledX, ledY, 4.2f * density, ledPaint)

        // 3. Skin-specific hardware accents
        when (consoleSkin) {
            SKIN_GBA_SP_GRAPHITE -> {
                // Authentic 4x4 GBA SP center speaker grille dots
                val cx = w * 0.5f
                val cy = dpadCy + dpadSize * 0.25f
                val gap = 9f * density
                for (row in -1..2) {
                    for (col in -1..2) {
                        val gx = cx + (col - 0.5f) * gap
                        val gy = cy + (row - 0.5f) * gap
                        canvas.drawCircle(gx, gy, 2.2f * density, speakerDotPaint)
                    }
                }
            }
            SKIN_GBC_ATOMIC_PURPLE -> {
                // Subtle translucent PCB trace accents inside atomic purple shell
                val traceY = gameScreenBottom + chinHeight + 18f * density
                canvas.drawLine(w * 0.08f, traceY, w * 0.42f, traceY, detailLinePaint)
                canvas.drawLine(w * 0.58f, traceY, w * 0.92f, traceY, detailLinePaint)
                canvas.drawCircle(w * 0.42f, traceY, 4f * density, detailLinePaint)
                canvas.drawCircle(w * 0.58f, traceY, 4f * density, detailLinePaint)
                // Diagonal speaker vents bottom-right
                for (i in 0..4) {
                    val sx = w * 0.78f + i * 7f * density
                    val sy = h - 38f * density
                    canvas.drawLine(sx, sy, sx + 10f * density, sy - 20f * density, detailLinePaint)
                }
            }
            SKIN_GBA_INDIGO -> {
                // Classic angled speaker slots near bottom-right
                for (i in 0..4) {
                    val sx = w * 0.76f + i * 7.5f * density
                    val sy = h - 36f * density
                    canvas.drawLine(sx, sy, sx + 12f * density, sy - 18f * density, detailLinePaint)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!controlsVisible) return

        // 0. Draw Portrait Console Hardware Skin Deck if enabled
        drawConsoleShell(canvas)

        val pressedKeys = pointerKeys.values.flatten().toSet()
        val baseTextSize = minOf(width, height) * 0.034f

        // 1. Draw Menu, Rewind, and Fast Forward pills
        drawUtilityPill(canvas, menuButton, KEY_MENU_BUTTON in pressedKeys, false, baseTextSize)
        if (showQuickActions) {
            drawUtilityPill(canvas, rewindButton, isRewindActive, isRewindActive, baseTextSize)
            drawUtilityPill(canvas, ffButton, KEY_FF_BUTTON in pressedKeys, isFastForwardActive, baseTextSize)
        }

        // 2. Draw D-Pad (Classic Cross OR Analog Stick)
        if (dpadType == DPAD_TYPE_STICK) {
            val baseRadius = dpadSize
            val nubRadius = dpadSize * 0.42f

            canvas.drawCircle(dpadCx, dpadCy, baseRadius, stickBasePaint)
            canvas.drawCircle(dpadCx, dpadCy, baseRadius, strokePaint)
            canvas.drawLine(dpadCx - baseRadius * 0.8f, dpadCy, dpadCx + baseRadius * 0.8f, dpadCy, strokePaint)
            canvas.drawLine(dpadCx, dpadCy - baseRadius * 0.8f, dpadCx, dpadCy + baseRadius * 0.8f, strokePaint)

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
                canvas.drawRoundRect(rect, 12f, 12f, if (pressed) pressedFill else fillPaint)
                canvas.drawRoundRect(rect, 12f, 12f, strokePaint)
            }

            drawArm(RectF(dpadCx - arm, dpadCy - thick / 2f, dpadCx, dpadCy + thick / 2f), leftPressed)
            drawArm(RectF(dpadCx, dpadCy - thick / 2f, dpadCx + arm, dpadCy + thick / 2f), rightPressed)
            drawArm(RectF(dpadCx - thick / 2f, dpadCy - arm, dpadCx + thick / 2f, dpadCy), upPressed)
            drawArm(RectF(dpadCx - thick / 2f, dpadCy, dpadCx + thick / 2f, dpadCy + arm), downPressed)

            canvas.drawCircle(dpadCx, dpadCy, thick / 2f, fillPaint)
            canvas.drawCircle(dpadCx, dpadCy, thick / 2f, strokePaint)

            val arrowSize = dpadSize * 0.27f
            textPaint.textSize = arrowSize
            canvas.drawText("◀", dpadCx - arm * 0.58f, dpadCy + arrowSize * 0.36f, textPaint)
            canvas.drawText("▶", dpadCx + arm * 0.58f, dpadCy + arrowSize * 0.36f, textPaint)
            canvas.drawText("▲", dpadCx, dpadCy - arm * 0.44f, textPaint)
            canvas.drawText("▼", dpadCx, dpadCy + arm * 0.76f, textPaint)
            textPaint.textSize = baseTextSize
        }

        // 3. Draw GBA/GB Buttons (A, B, L, R, Start, Select)
        buttons.forEach { btn ->
            val pressed = btn.key in pressedKeys
            val isAB = btn.key == NativeCore.KEY_A || btn.key == NativeCore.KEY_B
            val isStartSelect = btn.key == NativeCore.KEY_START || btn.key == NativeCore.KEY_SELECT

            if (isStartSelect) {
                val rect = RectF(btn.cx - btn.r * 1.45f, btn.cy - btn.r * 0.62f, btn.cx + btn.r * 1.45f, btn.cy + btn.r * 0.62f)
                canvas.drawRoundRect(rect, btn.r * 0.62f, btn.r * 0.62f, if (pressed) pressedFill else fillPaint)
                canvas.drawRoundRect(rect, btn.r * 0.62f, btn.r * 0.62f, strokePaint)
            } else {
                val normalFill = if (isAB) abFillPaint else fillPaint
                canvas.drawCircle(btn.cx, btn.cy, btn.r, if (pressed) pressedFill else normalFill)
                canvas.drawCircle(btn.cx, btn.cy, btn.r, strokePaint)
            }

            val btnTextSize = if (btn.label.length > 2) baseTextSize * 0.62f else baseTextSize
            textPaint.textSize = btnTextSize
            canvas.drawText(btn.label, btn.cx, btn.cy + btnTextSize * 0.36f, textPaint)
            textPaint.textSize = baseTextSize
        }
    }

    private fun drawUtilityPill(
        canvas: Canvas,
        btn: ButtonDef,
        pressed: Boolean,
        activeHighlight: Boolean,
        baseTextSize: Float
    ) {
        val pillW = btn.r * 1.35f
        val pillH = btn.r * 0.78f
        val rect = RectF(btn.cx - pillW, btn.cy - pillH, btn.cx + pillW, btn.cy + pillH)
        val paint = when {
            activeHighlight -> activePillFill
            pressed -> pressedFill
            else -> menuPaint
        }
        canvas.drawRoundRect(rect, pillH, pillH, paint)
        canvas.drawRoundRect(rect, pillH, pillH, strokePaint)

        val labelSize = baseTextSize * 0.68f
        textPaint.textSize = labelSize
        canvas.drawText(btn.label, btn.cx, btn.cy + labelSize * 0.35f, textPaint)
        textPaint.textSize = baseTextSize
    }

    private fun triggerHaptic() {
        if (touchHapticsEnabled) {
            performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
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
                handlePointerDownKeys(event.getPointerId(0), keys)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val keys = resolveKeys(event.getX(pIdx), event.getY(pIdx), pId)
                handlePointerDownKeys(pId, keys)
            }
            MotionEvent.ACTION_MOVE -> {
                val activeIds = HashSet<Int>(event.pointerCount)
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    activeIds.add(id)
                    val keys = resolveKeys(event.getX(i), event.getY(i), id)
                    if (KEY_MENU_BUTTON !in keys && KEY_FF_BUTTON !in keys) {
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

        // Compute GBA key mask + live Rewind hold state
        var mask = 0
        var rewindHeld = false
        for (set in pointerKeys.values) {
            for (k in set) {
                when (k) {
                    KEY_REWIND_BUTTON -> rewindHeld = true
                    KEY_MENU_BUTTON, KEY_FF_BUTTON -> Unit
                    else -> mask = mask or k
                }
            }
        }

        if (rewindHeld != isRewindActive) {
            isRewindActive = rewindHeld
            onRewindStateChanged?.invoke(rewindHeld)
        }

        if (mask != lastKeyMask) {
            // Fire haptics only when a new hardware button bit is newly pressed
            if ((mask and lastKeyMask.inv()) != 0) {
                triggerHaptic()
            }
            lastKeyMask = mask
            onKeyStateChanged?.invoke(mask)
        }

        invalidate()
        return true
    }

    private fun handlePointerDownKeys(pointerId: Int, keys: Set<Int>) {
        when {
            KEY_MENU_BUTTON in keys -> {
                triggerHaptic()
                onMenuClick?.invoke()
            }
            KEY_FF_BUTTON in keys -> {
                triggerHaptic()
                isFastForwardActive = !isFastForwardActive
                onFastForwardToggle?.invoke(isFastForwardActive)
            }
            else -> {
                if (KEY_REWIND_BUTTON in keys) triggerHaptic()
                pointerKeys[pointerId] = keys
            }
        }
    }

    private fun resolveKeys(x: Float, y: Float, pointerId: Int): Set<Int> {
        val result = mutableSetOf<Int>()

        // 1. Utility pills (Menu, Rewind, Fast Forward)
        if (hypot(x - menuButton.cx, y - menuButton.cy) <= menuButton.r * 1.4f) {
            result.add(KEY_MENU_BUTTON)
            return result
        }
        if (showQuickActions) {
            if (hypot(x - ffButton.cx, y - ffButton.cy) <= ffButton.r * 1.4f) {
                result.add(KEY_FF_BUTTON)
                return result
            }
            if (hypot(x - rewindButton.cx, y - rewindButton.cy) <= rewindButton.r * 1.4f) {
                result.add(KEY_REWIND_BUTTON)
                return result
            }
        }

        // 2. D-Pad
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

        // 3. Action & Shoulder Buttons
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
        if (isRewindActive) {
            isRewindActive = false
            onRewindStateChanged?.invoke(false)
        }
        if (lastKeyMask != 0) {
            lastKeyMask = 0
            onKeyStateChanged?.invoke(0)
            invalidate()
        }
    }
}
