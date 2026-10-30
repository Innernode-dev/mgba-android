package com.retro.mgba

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.retro.mgba.core.NativeCore
import com.retro.mgba.data.storage.StorageHelper
import com.retro.mgba.overlay.TouchOverlayView
import java.io.File
import kotlin.concurrent.thread

/**
 * Production-grade Fullscreen Emulation Activity for mGBA.
 *
 * Implements:
 * - Upstream mGBA 0.10.5 core with AAudio low-latency sound and OpenGL ES 3.0 viewport.
 * - Dynamic Landscape & Portrait orientation support.
 * - Camera cutout SHORT_EDGES support (symmetrically centered display).
 * - Full battery SRAM persistence (.sav files written to user-accessible /mGBA/saves/ folder).
 * - Multi-slot Savestates (.ss files stored per-game in /mGBA/states/<game>/ folder).
 * - Instant physical gamepad input (0 ms latency dispatchKeyEvent with remapping).
 * - Configurable Hotkeys (Quick Save, Quick Load, Pause Menu).
 * - Separate D-Pad and Analog Stick input masks.
 * - Touch virtual overlay with 8-way cross D-Pad OR virtual stick ("колёсико"), zero vibration.
 * - In-Game Pause dialog with Quick Save/Load, Settings shortcut, and clean exit.
 */
class EmulationActivity : ComponentActivity(), SurfaceHolder.Callback {

    private val core = NativeCore()
    private var emulationThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    @Volatile private var isPausedByMenu = false

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: TouchOverlayView
    private lateinit var prefs: SharedPreferences

    // Separate input masks so D-Pad and Analog Stick never cancel each other out
    @Volatile private var touchKeyMask   = 0
    @Volatile private var padButtonsMask = 0
    @Volatile private var padStickMask   = 0

    // Save & State paths
    private lateinit var sanitizedGameTitle: String
    private lateinit var sramFilePath: String
    private lateinit var stateDirPath: String

    private var currentAspectRatio = NativeCore.ASPECT_RATIO_NATIVE
    private var currentShaderFilter = NativeCore.FILTER_NEAREST

    // Configured Gamepad Keycodes
    private var btnA = KeyEvent.KEYCODE_BUTTON_A
    private var btnB = KeyEvent.KEYCODE_BUTTON_B
    private var btnL = KeyEvent.KEYCODE_BUTTON_L1
    private var btnR = KeyEvent.KEYCODE_BUTTON_R1
    private var btnStart = KeyEvent.KEYCODE_BUTTON_START
    private var btnSelect = KeyEvent.KEYCODE_BUTTON_SELECT
    private var btnUp = KeyEvent.KEYCODE_DPAD_UP
    private var btnDown = KeyEvent.KEYCODE_DPAD_DOWN
    private var btnLeft = KeyEvent.KEYCODE_DPAD_LEFT
    private var btnRight = KeyEvent.KEYCODE_DPAD_RIGHT

    // Configured Hotkeys
    private var hotkeySave = KeyEvent.KEYCODE_BUTTON_THUMBL
    private var hotkeyLoad = KeyEvent.KEYCODE_BUTTON_THUMBR
    private var hotkeyPause = 0
    private var layoutProfile = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Display cutout support: draw under camera notches so screen is symmetrically centered
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 2. Immersive Fullscreen
        applyImmersiveFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 3. Resolve Game Title and Save Paths
        val rawTitle = intent.getStringExtra("EXTRA_GAME_TITLE")
            ?: intent.data?.lastPathSegment?.substringBeforeLast('.')
            ?: "game"
        sanitizedGameTitle = rawTitle.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()

        val saveDir = StorageHelper.getSaveDir(this)
        val stateBaseDir = StorageHelper.getStateDir(this)
        val gameStateDir = File(stateBaseDir, sanitizedGameTitle).also { it.mkdirs() }
        val sramFile = File(saveDir, "$sanitizedGameTitle.sav")

        sramFilePath = sramFile.absolutePath
        stateDirPath = gameStateDir.absolutePath

        // 4. View Hierarchy: SurfaceView underneath, TouchOverlayView on top
        val root = FrameLayout(this)

        surfaceView = SurfaceView(this).also {
            root.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        overlayView = TouchOverlayView(this).also { overlay ->
            overlay.onKeyStateChanged = { mask ->
                touchKeyMask = mask
                pushKeys()
            }
            overlay.onMenuClick = {
                showPauseMenu()
            }
            root.addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        setContentView(root)
        surfaceView.holder.addCallback(this)

        applyUserSettings()
    }

    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val surface = holder.surface
        if (core.handle == 0L) {
            startEmulation(surface, width, height)
        } else {
            core.setSurface(surface)
            core.setSurfaceSize(width, height)
            core.setAspectRatioMode(currentAspectRatio)
            surfaceReady = true
            if (!isPausedByMenu && !running) {
                resumeEmulationThread()
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopEmulationThread()
        core.setSurface(null)
    }

    // --- Emulation Lifecycle ---

    private fun startEmulation(surface: Surface, width: Int, height: Int) {
        val gameUri = intent.data ?: run {
            finish(); return
        }

        if (!core.create(48000, 2048)) {
            Toast.makeText(this, "Не удалось инициализировать ядро mGBA", Toast.LENGTH_LONG).show()
            finish(); return
        }

        core.setSurface(surface)
        core.setSurfaceSize(width, height)
        core.setAspectRatioMode(currentAspectRatio)
        core.setShaderFilter(currentShaderFilter)

        val loaded = loadRom(gameUri)
        if (!loaded) {
            Toast.makeText(this, "Не удалось загрузить ROM-файл", Toast.LENGTH_LONG).show()
            core.destroy(); finish(); return
        }

        // Hardware reset first, then bind & restore SRAM so reset doesn't overwrite loaded battery save
        core.reset()
        core.setSramPath(sramFilePath)

        surfaceReady = true
        resumeEmulationThread()
    }

    private fun loadRom(uri: Uri): Boolean {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                core.loadRomFd(pfd.fd)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun resumeEmulationThread() {
        if (running) return
        running = true
        emulationThread = thread(name = "mGBA-emu", isDaemon = true) {
            emulationLoop()
        }
    }

    private fun stopEmulationThread() {
        running = false
        emulationThread?.interrupt()
        try {
            emulationThread?.join(600)
        } catch (_: InterruptedException) {}
        emulationThread = null
    }

    private fun emulationLoop() {
        var nextFrameTime = System.nanoTime()
        var lastSramSyncTimeMs = System.currentTimeMillis()
        val targetFps = 60.0
        val frameNanos = (1_000_000_000L / targetFps).toLong()

        while (running) {
            if (!surfaceReady || isPausedByMenu) {
                try { Thread.sleep(12) } catch (_: InterruptedException) { break }
                nextFrameTime = System.nanoTime()
                continue
            }

            val now = System.nanoTime()
            if (now >= nextFrameTime) {
                core.runFrame()
                nextFrameTime += frameNanos
                if (now - nextFrameTime > frameNanos * 3) {
                    nextFrameTime = now + frameNanos
                }

                // Periodic battery SRAM flush (every 1.5 seconds if marked dirty by game)
                val curTimeMs = System.currentTimeMillis()
                if (curTimeMs - lastSramSyncTimeMs >= 1500) {
                    lastSramSyncTimeMs = curTimeMs
                    if (core.isSramDirty()) {
                        core.flushSram(sramFilePath)
                    }
                }
            } else {
                val sleepMs = (nextFrameTime - now) / 1_000_000L
                if (sleepMs > 0) {
                    try { Thread.sleep(sleepMs.coerceAtMost(14)) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    // --- Input Subsystem ---

    private fun pushKeys() {
        core.setKeys(touchKeyMask or padButtonsMask or padStickMask)
    }

    /**
     * Intercepts physical gamepad keys at window dispatch level.
     * Guarantees 0 ms latency without Android UI focus overhead.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // 1. Pause Menu (Back Key or Configured Pause Hotkey)
        if (keyCode == KeyEvent.KEYCODE_BACK || (hotkeyPause != 0 && keyCode == hotkeyPause)) {
            if (event.action == KeyEvent.ACTION_UP) {
                showPauseMenu()
            }
            return true
        }

        // 2. Hotkey: Quick Save (Save State to slot 0)
        if (hotkeySave != 0 && keyCode == hotkeySave) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val ok = core.saveState(0, stateDirPath)
                Toast.makeText(this, if (ok) "💾 Сохранено (слот 0)" else "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        // 3. Hotkey: Quick Load (Load State from slot 0)
        if (hotkeyLoad != 0 && keyCode == hotkeyLoad) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val ok = core.loadState(0, stateDirPath)
                Toast.makeText(this, if (ok) "📂 Загружено (слот 0)" else "Слот 0 пуст", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        // 4. Gamepad Action Buttons (with user remapping and layout profile)
        val bit = gamepadKeyToBit(keyCode)
        if (bit != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    padButtonsMask = padButtonsMask or bit
                    pushKeys()
                }
                KeyEvent.ACTION_UP -> {
                    padButtonsMask = padButtonsMask and bit.inv()
                    pushKeys()
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun gamepadKeyToBit(keyCode: Int): Int? {
        val mappedBit = when (keyCode) {
            btnA -> NativeCore.KEY_A
            btnB -> NativeCore.KEY_B
            btnL -> NativeCore.KEY_L
            btnR -> NativeCore.KEY_R
            btnStart -> NativeCore.KEY_START
            btnSelect -> NativeCore.KEY_SELECT
            btnUp -> NativeCore.KEY_UP
            btnDown -> NativeCore.KEY_DOWN
            btnLeft -> NativeCore.KEY_LEFT
            btnRight -> NativeCore.KEY_RIGHT
            else -> null
        } ?: return null

        // Apply Nintendo layout profile inversion if selected (swaps A and B)
        return if (layoutProfile == 1) {
            when (mappedBit) {
                NativeCore.KEY_A -> NativeCore.KEY_B
                NativeCore.KEY_B -> NativeCore.KEY_A
                else -> mappedBit
            }
        } else {
            mappedBit
        }
    }

    /**
     * Intercepts Gamepad Analog Sticks (Left Thumbstick and D-Pad Hat Axes).
     * Calculates 8-way directional deadzone mapping independently from buttons.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) {
            return super.onGenericMotionEvent(event)
        }

        val axisX = event.getAxisValue(MotionEvent.AXIS_X)
        val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        val effX = if (kotlin.math.abs(hatX) > 0.2f) hatX else axisX
        val effY = if (kotlin.math.abs(hatY) > 0.2f) hatY else axisY

        val deadZone = 0.35f
        var stickMask = 0

        if (effX < -deadZone) stickMask = stickMask or NativeCore.KEY_LEFT
        if (effX >  deadZone) stickMask = stickMask or NativeCore.KEY_RIGHT
        if (effY < -deadZone) stickMask = stickMask or NativeCore.KEY_UP
        if (effY >  deadZone) stickMask = stickMask or NativeCore.KEY_DOWN

        if (stickMask != padStickMask) {
            padStickMask = stickMask
            pushKeys()
        }

        return true
    }

    // --- In-Game Pause & Settings Menu ---

    private fun showPauseMenu() {
        if (isFinishing || isDestroyed) return
        isPausedByMenu = true
        overlayView.clearAllKeys()

        val items = arrayOf(
            "▶  Продолжить игру",
            "💾  Быстрое сохранение",
            "📂  Быстрая загрузка",
            "⚙  Настройки",
            "🚪  Сохранить и выйти в меню"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("mGBA — Пауза")
            .setItems(items) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> { // Resume
                        isPausedByMenu = false
                        applyImmersiveFullscreen()
                    }
                    1 -> { // Quick Save
                        val ok = core.saveState(0, stateDirPath)
                        Toast.makeText(this, if (ok) "Сохранено в слот 0" else "Ошибка сохранения", Toast.LENGTH_SHORT).show()
                        isPausedByMenu = false
                        applyImmersiveFullscreen()
                    }
                    2 -> { // Quick Load
                        val ok = core.loadState(0, stateDirPath)
                        Toast.makeText(this, if (ok) "Загружено из слота 0" else "Слот 0 пуст", Toast.LENGTH_SHORT).show()
                        isPausedByMenu = false
                        applyImmersiveFullscreen()
                    }
                    3 -> { // Open Settings Screen
                        isPausedByMenu = true
                        startActivity(Intent(this@EmulationActivity, SettingsActivity::class.java))
                    }
                    4 -> { // Save and Exit cleanly back to library
                        core.saveState(0, stateDirPath)
                        core.flushSram(sramFilePath)
                        finish()
                    }
                }
            }
            .setOnCancelListener {
                isPausedByMenu = false
                applyImmersiveFullscreen()
            }
            .show()
    }

    private fun applyUserSettings() {
        val p = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Aspect Ratio
        currentAspectRatio = p.getInt(SettingsActivity.KEY_ASPECT_RATIO, NativeCore.ASPECT_RATIO_NATIVE)
        if (core.handle != 0L) {
            core.setAspectRatioMode(currentAspectRatio)
        }

        // 2. Shader Filter
        currentShaderFilter = p.getInt(SettingsActivity.KEY_SHADER_FILTER, NativeCore.FILTER_NEAREST)
        if (core.handle != 0L) {
            core.setShaderFilter(currentShaderFilter)
        }

        // 3. Touch Overlay Visibility, D-Pad Type & Opacity
        if (::overlayView.isInitialized) {
            overlayView.controlsVisible = p.getBoolean(SettingsActivity.KEY_CONTROLS_VISIBLE, true)
            overlayView.dpadType = p.getInt(SettingsActivity.KEY_DPAD_TYPE, TouchOverlayView.DPAD_TYPE_CLASSIC)
            overlayView.controlsOpacity = p.getFloat(SettingsActivity.KEY_CONTROLS_OPACITY, 0.55f)
        }

        // 4. Orientation
        when (p.getInt(SettingsActivity.KEY_ORIENTATION, 0)) {
            1 -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

        // 5. Audio
        val audioEnabled = p.getBoolean(SettingsActivity.KEY_AUDIO_ENABLED, true)
        if (core.handle != 0L) {
            core.setAudioMute(!audioEnabled)
        }

        // 6. Controls & Hotkeys
        layoutProfile = p.getInt(SettingsActivity.KEY_LAYOUT_PROFILE, 0)
        btnA = p.getInt(SettingsActivity.KEY_BTN_A, KeyEvent.KEYCODE_BUTTON_A)
        btnB = p.getInt(SettingsActivity.KEY_BTN_B, KeyEvent.KEYCODE_BUTTON_B)
        btnL = p.getInt(SettingsActivity.KEY_BTN_L, KeyEvent.KEYCODE_BUTTON_L1)
        btnR = p.getInt(SettingsActivity.KEY_BTN_R, KeyEvent.KEYCODE_BUTTON_R1)
        btnStart = p.getInt(SettingsActivity.KEY_BTN_START, KeyEvent.KEYCODE_BUTTON_START)
        btnSelect = p.getInt(SettingsActivity.KEY_BTN_SELECT, KeyEvent.KEYCODE_BUTTON_SELECT)
        btnUp = p.getInt(SettingsActivity.KEY_BTN_UP, KeyEvent.KEYCODE_DPAD_UP)
        btnDown = p.getInt(SettingsActivity.KEY_BTN_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        btnLeft = p.getInt(SettingsActivity.KEY_BTN_LEFT, KeyEvent.KEYCODE_DPAD_LEFT)
        btnRight = p.getInt(SettingsActivity.KEY_BTN_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)

        hotkeySave = p.getInt(SettingsActivity.KEY_HOTKEY_SAVE_CODE, KeyEvent.KEYCODE_BUTTON_THUMBL)
        hotkeyLoad = p.getInt(SettingsActivity.KEY_HOTKEY_LOAD_CODE, KeyEvent.KEYCODE_BUTTON_THUMBR)
        hotkeyPause = p.getInt(SettingsActivity.KEY_HOTKEY_PAUSE_CODE, 0)
    }

    // --- Android Lifecycle Handling ---

    override fun onPause() {
        super.onPause()
        stopEmulationThread()
        if (core.handle != 0L) {
            core.saveState(0, stateDirPath)
            core.flushSram(sramFilePath)
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        applyUserSettings()
        isPausedByMenu = false
        if (core.handle != 0L && surfaceReady && !running) {
            resumeEmulationThread()
        }
    }

    override fun onDestroy() {
        stopEmulationThread()
        if (core.handle != 0L) {
            core.flushSram(sramFilePath)
            core.destroy()
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveFullscreen()
        overlayView.requestLayout()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
        }
    }
}
