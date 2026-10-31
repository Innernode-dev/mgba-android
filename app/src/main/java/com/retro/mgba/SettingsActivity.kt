package com.retro.mgba

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.retro.mgba.core.NativeCore
import com.retro.mgba.data.db.AppDatabase
import com.retro.mgba.data.db.GameEntity
import com.retro.mgba.data.storage.StorageHelper
import com.retro.mgba.overlay.TouchOverlayView
import com.retro.mgba.ui.library.CartridgeCanvas
import com.retro.mgba.ui.theme.*
import com.retro.mgba.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings Activity with nested sub-menus:
 * 1. Графика (Размер экрана, Сглаживание, Поворот)
 * 2. Управление (Экранные кнопки, Стик/Крестовина, Раскладка Xbox, Геймпад, Горячие клавиши)
 * 3. Менеджер сохранений (Список, Импорт, Экспорт, Удаление, Путь к папке)
 * 4. Менеджер обложек (Список, Загрузка, Замена, Удаление, Путь к папке)
 * 5. О приложении
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var keyCaptureCallback: ((Int) -> Unit)? = null

    companion object {
        const val PREFS_NAME = "mgba_emu_prefs"
        const val KEY_ASPECT_RATIO = "aspect_ratio"
        const val KEY_SHADER_FILTER = "shader_filter"
        const val KEY_ORIENTATION = "screen_orientation"
        const val KEY_CONTROLS_VISIBLE = "controls_visible"
        const val KEY_DPAD_TYPE = "dpad_type"
        const val KEY_CONTROLS_OPACITY = "controls_opacity"
        const val KEY_LAYOUT_PROFILE = "layout_profile"
        const val KEY_AUDIO_ENABLED = "audio_enabled"

        // Gamepad Remapping (Xbox / Switch layout)
        const val KEY_BTN_A = "btn_a"
        const val KEY_BTN_B = "btn_b"
        const val KEY_BTN_L = "btn_l"
        const val KEY_BTN_R = "btn_r"
        const val KEY_BTN_START = "btn_start"
        const val KEY_BTN_SELECT = "btn_select"
        const val KEY_BTN_UP = "btn_up"
        const val KEY_BTN_DOWN = "btn_down"
        const val KEY_BTN_LEFT = "btn_left"
        const val KEY_BTN_RIGHT = "btn_right"

        // Hotkeys
        const val KEY_HOTKEY_SAVE_CODE = "hotkey_save_code"
        const val KEY_HOTKEY_LOAD_CODE = "hotkey_load_code"
        const val KEY_HOTKEY_PAUSE_CODE = "hotkey_pause_code"

        fun getReadableKeyName(context: Context, keyCode: Int): String = when (keyCode) {
            0 -> context.getString(R.string.key_disabled)
            KeyEvent.KEYCODE_BUTTON_A -> context.getString(R.string.key_btn_a)
            KeyEvent.KEYCODE_BUTTON_B -> context.getString(R.string.key_btn_b)
            KeyEvent.KEYCODE_BUTTON_X -> context.getString(R.string.key_btn_x)
            KeyEvent.KEYCODE_BUTTON_Y -> context.getString(R.string.key_btn_y)
            KeyEvent.KEYCODE_BUTTON_L1 -> context.getString(R.string.key_lb)
            KeyEvent.KEYCODE_BUTTON_R1 -> context.getString(R.string.key_rb)
            KeyEvent.KEYCODE_BUTTON_L2 -> context.getString(R.string.key_lt)
            KeyEvent.KEYCODE_BUTTON_R2 -> context.getString(R.string.key_rt)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> context.getString(R.string.key_ls)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> context.getString(R.string.key_rs)
            KeyEvent.KEYCODE_BUTTON_START -> context.getString(R.string.key_start)
            KeyEvent.KEYCODE_BUTTON_SELECT -> context.getString(R.string.key_select_btn)
            KeyEvent.KEYCODE_BUTTON_MODE -> "XBOX / GUIDE"
            KeyEvent.KEYCODE_DPAD_UP -> context.getString(R.string.key_dpad_up)
            KeyEvent.KEYCODE_DPAD_DOWN -> context.getString(R.string.key_dpad_down)
            KeyEvent.KEYCODE_DPAD_LEFT -> context.getString(R.string.key_dpad_left)
            KeyEvent.KEYCODE_DPAD_RIGHT -> context.getString(R.string.key_dpad_right)
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }

        fun getReadableKeyName(keyCode: Int): String = when (keyCode) {
            0 -> "Disabled"
            KeyEvent.KEYCODE_BUTTON_A -> "Button A"
            KeyEvent.KEYCODE_BUTTON_B -> "Button B"
            KeyEvent.KEYCODE_BUTTON_X -> "Button X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Button Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
            KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
            KeyEvent.KEYCODE_BUTTON_L2 -> "LT"
            KeyEvent.KEYCODE_BUTTON_R2 -> "RT"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "LS"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "RS"
            KeyEvent.KEYCODE_BUTTON_START -> "START"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "SELECT"
            KeyEvent.KEYCODE_BUTTON_MODE -> "XBOX / GUIDE"
            KeyEvent.KEYCODE_DPAD_UP -> "D-Pad Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "D-Pad Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "D-Pad Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Pad Right"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.retro.mgba.util.LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            RetroMgbaTheme {
                NestedSettingsScreen(
                    prefs = prefs,
                    onBackActivity = { finish() },
                    onRequestKeyCapture = { callback ->
                        keyCaptureCallback = callback
                    },
                    onCancelKeyCapture = {
                        keyCaptureCallback = null
                    }
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val callback = keyCaptureCallback
        if (callback != null && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                keyCaptureCallback = null
                callback.invoke(event.keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val callback = keyCaptureCallback
        if (callback != null) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val code = when {
                hatX < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
                hatX > 0.5f  -> KeyEvent.KEYCODE_DPAD_RIGHT
                hatY < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
                hatY > 0.5f  -> KeyEvent.KEYCODE_DPAD_DOWN
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f || event.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.5f -> KeyEvent.KEYCODE_BUTTON_L2
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f || event.getAxisValue(MotionEvent.AXIS_GAS) > 0.5f -> KeyEvent.KEYCODE_BUTTON_R2
                else -> 0
            }
            if (code != 0) {
                keyCaptureCallback = null
                callback.invoke(code)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}

enum class SettingsSubMenu {
    MAIN,
    GRAPHICS,
    CONTROLS,
    SAVES_MANAGER,
    COVERS_MANAGER,
    ABOUT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NestedSettingsScreen(
    prefs: SharedPreferences,
    onBackActivity: () -> Unit,
    onRequestKeyCapture: ((Int) -> Unit) -> Unit,
    onCancelKeyCapture: () -> Unit
) {
    val context = LocalContext.current
    var currentSubMenu by remember { mutableStateOf(SettingsSubMenu.MAIN) }

    // Intercept system Back press: return to Main Settings first, then finish
    BackHandler(enabled = currentSubMenu != SettingsSubMenu.MAIN) {
        currentSubMenu = SettingsSubMenu.MAIN
    }

    val topBarTitle = when (currentSubMenu) {
        SettingsSubMenu.MAIN -> stringResource(R.string.btn_settings)
        SettingsSubMenu.GRAPHICS -> stringResource(R.string.menu_graphics)
        SettingsSubMenu.CONTROLS -> stringResource(R.string.menu_controls)
        SettingsSubMenu.SAVES_MANAGER -> stringResource(R.string.menu_saves)
        SettingsSubMenu.COVERS_MANAGER -> stringResource(R.string.menu_covers)
        SettingsSubMenu.ABOUT -> stringResource(R.string.menu_about)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = topBarTitle,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentSubMenu == SettingsSubMenu.MAIN) {
                                onBackActivity()
                            } else {
                                currentSubMenu = SettingsSubMenu.MAIN
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OledBackground)
            )
        },
        containerColor = OledBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentSubMenu) {
                SettingsSubMenu.MAIN -> {
                    MainSettingsMenu(
                        onNavigate = { currentSubMenu = it }
                    )
                }
                SettingsSubMenu.GRAPHICS -> {
                    GraphicsSubMenu(prefs = prefs)
                }
                SettingsSubMenu.CONTROLS -> {
                    ControlsSubMenu(
                        prefs = prefs,
                        onRequestKeyCapture = onRequestKeyCapture,
                        onCancelKeyCapture = onCancelKeyCapture
                    )
                }
                SettingsSubMenu.SAVES_MANAGER -> {
                    SavesManagerSubMenu(context = context)
                }
                SettingsSubMenu.COVERS_MANAGER -> {
                    CoversManagerSubMenu(context = context)
                }
                SettingsSubMenu.ABOUT -> {
                    AboutSubMenu()
                }
            }
        }
    }
}

// =============================================================================
// MAIN MENU
// =============================================================================

@Composable
fun MainSettingsMenu(onNavigate: (SettingsSubMenu) -> Unit) {
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column {
                SettingsNavigationRow(
                    title = stringResource(R.string.menu_graphics),
                    subtitle = stringResource(R.string.screen_size) + ", " + stringResource(R.string.smoothing),
                    icon = Icons.Filled.Tv,
                    onClick = { onNavigate(SettingsSubMenu.GRAPHICS) }
                )
                HorizontalDivider(color = SurfaceBorderSubtle)
                SettingsNavigationRow(
                    title = stringResource(R.string.menu_controls),
                    subtitle = stringResource(R.string.touch_controls) + ", " + stringResource(R.string.gamepad_mapping),
                    icon = Icons.Filled.SportsEsports,
                    onClick = { onNavigate(SettingsSubMenu.CONTROLS) }
                )
                HorizontalDivider(color = SurfaceBorderSubtle)
                SettingsNavigationRow(
                    title = stringResource(R.string.menu_saves),
                    subtitle = stringResource(R.string.menu_saves_subtitle),
                    icon = Icons.Filled.FolderSpecial,
                    onClick = { onNavigate(SettingsSubMenu.SAVES_MANAGER) }
                )
                HorizontalDivider(color = SurfaceBorderSubtle)
                SettingsNavigationRow(
                    title = stringResource(R.string.menu_covers),
                    subtitle = stringResource(R.string.menu_covers_subtitle),
                    icon = Icons.Filled.Image,
                    onClick = { onNavigate(SettingsSubMenu.COVERS_MANAGER) }
                )
                HorizontalDivider(color = SurfaceBorderSubtle)
                SettingsNavigationRow(
                    title = stringResource(R.string.app_language),
                    subtitle = LocaleHelper.getLanguageDisplayLabel(context),
                    icon = Icons.Filled.Language,
                    onClick = { showLanguageDialog = true }
                )
                HorizontalDivider(color = SurfaceBorderSubtle)
                SettingsNavigationRow(
                    title = stringResource(R.string.menu_about),
                    subtitle = stringResource(R.string.menu_about_subtitle),
                    icon = Icons.Filled.Info,
                    onClick = { onNavigate(SettingsSubMenu.ABOUT) }
                )
            }
        }
    }

    if (showLanguageDialog) {
        val options = LocaleHelper.SUPPORTED_LANGUAGES.map { it.second }
        val currentCode = LocaleHelper.getSelectedLanguage(context)
        val selectedIndex = LocaleHelper.SUPPORTED_LANGUAGES.indexOfFirst { it.first == currentCode }.coerceAtLeast(0)

        SingleChoiceDialog(
            title = stringResource(R.string.app_language),
            options = options,
            selectedIndex = selectedIndex,
            onSelect = { index ->
                val targetCode = LocaleHelper.SUPPORTED_LANGUAGES[index].first
                LocaleHelper.setLanguage(context, targetCode)
                showLanguageDialog = false
                (context as? Activity)?.recreate()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

// =============================================================================
// SUB-MENU 1: GRAPHICS
// =============================================================================

@Composable
fun GraphicsSubMenu(prefs: SharedPreferences) {
    var aspectRatio by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_ASPECT_RATIO, NativeCore.ASPECT_RATIO_NATIVE)) }
    var shaderFilter by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_SHADER_FILTER, NativeCore.FILTER_NEAREST)) }
    var orientation by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_ORIENTATION, 0)) }

    var showAspectDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showOrientationDialog by remember { mutableStateOf(false) }

    val aspectOptions = listOf(
        stringResource(R.string.aspect_ratio_native),
        stringResource(R.string.aspect_ratio_integer),
        stringResource(R.string.aspect_ratio_stretch)
    )

    val filterOptions = listOf(
        stringResource(R.string.shader_filter_nearest),
        stringResource(R.string.shader_filter_bilinear),
        stringResource(R.string.shader_filter_lcd)
    )

    val orientationOptions = listOf(
        stringResource(R.string.orientation_auto),
        stringResource(R.string.orientation_landscape),
        stringResource(R.string.orientation_portrait)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column {
                SettingsRow(
                    title = stringResource(R.string.screen_size),
                    subtitle = aspectOptions.getOrElse(aspectRatio) { aspectOptions[0] },
                    onClick = { showAspectDialog = true }
                )
                HorizontalDivider(color = SurfaceBorderSubtle)
                SettingsRow(
                    title = stringResource(R.string.smoothing),
                    subtitle = filterOptions.getOrElse(shaderFilter) { filterOptions[0] },
                    onClick = { showFilterDialog = true }
                )
                HorizontalDivider(color = SurfaceBorderSubtle)
                SettingsRow(
                    title = stringResource(R.string.screen_rotation),
                    subtitle = orientationOptions.getOrElse(orientation) { orientationOptions[0] },
                    onClick = { showOrientationDialog = true }
                )
            }
        }
    }

    if (showAspectDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.screen_size),
            options = aspectOptions,
            selectedIndex = aspectRatio,
            onSelect = {
                aspectRatio = it
                prefs.edit().putInt(SettingsActivity.KEY_ASPECT_RATIO, it).apply()
                showAspectDialog = false
            },
            onDismiss = { showAspectDialog = false }
        )
    }

    if (showFilterDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.smoothing),
            options = filterOptions,
            selectedIndex = shaderFilter,
            onSelect = {
                shaderFilter = it
                prefs.edit().putInt(SettingsActivity.KEY_SHADER_FILTER, it).apply()
                showFilterDialog = false
            },
            onDismiss = { showFilterDialog = false }
        )
    }

    if (showOrientationDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.screen_rotation),
            options = orientationOptions,
            selectedIndex = orientation,
            onSelect = {
                orientation = it
                prefs.edit().putInt(SettingsActivity.KEY_ORIENTATION, it).apply()
                showOrientationDialog = false
            },
            onDismiss = { showOrientationDialog = false }
        )
    }
}

// =============================================================================
// SUB-MENU 2: CONTROLS
// =============================================================================

@Composable
fun ControlsSubMenu(
    prefs: SharedPreferences,
    onRequestKeyCapture: ((Int) -> Unit) -> Unit,
    onCancelKeyCapture: () -> Unit
) {
    val context = LocalContext.current
    var controlsVisible by remember { mutableStateOf(prefs.getBoolean(SettingsActivity.KEY_CONTROLS_VISIBLE, true)) }
    var dpadType by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_DPAD_TYPE, TouchOverlayView.DPAD_TYPE_CLASSIC)) }
    var controlsOpacity by remember { mutableStateOf(prefs.getFloat(SettingsActivity.KEY_CONTROLS_OPACITY, 0.55f)) }
    var layoutProfile by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_LAYOUT_PROFILE, 0)) }

    // Gamepad mappings
    var btnA by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_A, KeyEvent.KEYCODE_BUTTON_A)) }
    var btnB by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_B, KeyEvent.KEYCODE_BUTTON_B)) }
    var btnL by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_L, KeyEvent.KEYCODE_BUTTON_L1)) }
    var btnR by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_R, KeyEvent.KEYCODE_BUTTON_R1)) }
    var btnStart by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_START, KeyEvent.KEYCODE_BUTTON_START)) }
    var btnSelect by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_SELECT, KeyEvent.KEYCODE_BUTTON_SELECT)) }
    var btnUp by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_UP, KeyEvent.KEYCODE_DPAD_UP)) }
    var btnDown by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)) }
    var btnLeft by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_LEFT, KeyEvent.KEYCODE_DPAD_LEFT)) }
    var btnRight by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_BTN_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)) }

    // Hotkeys
    var hotkeySave by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_HOTKEY_SAVE_CODE, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
    var hotkeyLoad by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_HOTKEY_LOAD_CODE, KeyEvent.KEYCODE_BUTTON_THUMBR)) }
    var hotkeyPause by remember { mutableStateOf(prefs.getInt(SettingsActivity.KEY_HOTKEY_PAUSE_CODE, 0)) }

    var showDpadTypeDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }

    var keyListeningTargetName by remember { mutableStateOf<String?>(null) }
    var keyListeningPrefKey by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Сенсорное управление
        SettingsCategoryHeader(title = stringResource(R.string.touch_controls_header), icon = Icons.Filled.TouchApp)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column {
                SettingsSwitchRow(
                    title = stringResource(R.string.touch_controls_visible),
                    subtitle = if (controlsVisible) stringResource(R.string.touch_controls_visible_desc_on) else stringResource(R.string.touch_controls_visible_desc_off),
                    checked = controlsVisible,
                    onCheckedChange = {
                        controlsVisible = it
                        prefs.edit().putBoolean(SettingsActivity.KEY_CONTROLS_VISIBLE, it).apply()
                    }
                )

                if (controlsVisible) {
                    HorizontalDivider(color = SurfaceBorderSubtle)
                    SettingsRow(
                        title = stringResource(R.string.dpad_style),
                        subtitle = if (dpadType == TouchOverlayView.DPAD_TYPE_STICK) stringResource(R.string.dpad_style_stick) else stringResource(R.string.dpad_style_classic),
                        onClick = { showDpadTypeDialog = true }
                    )
                    HorizontalDivider(color = SurfaceBorderSubtle)
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.button_opacity),
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${(controlsOpacity * 100).toInt()}%",
                                color = AccentPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = controlsOpacity,
                            onValueChange = {
                                controlsOpacity = it
                                prefs.edit().putFloat(SettingsActivity.KEY_CONTROLS_OPACITY, it).apply()
                            },
                            valueRange = 0.15f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentPrimary,
                                activeTrackColor = AccentPrimary,
                                inactiveTrackColor = Color(0xFF262638)
                            )
                        )
                    }
                }

                HorizontalDivider(color = SurfaceBorderSubtle)
                SettingsRow(
                    title = stringResource(R.string.ab_layout),
                    subtitle = if (layoutProfile == 0) stringResource(R.string.ab_layout_standard) else stringResource(R.string.ab_layout_swapped),
                    onClick = { showProfileDialog = true }
                )
            }
        }

        // Section: Назначение кнопок геймпада (Xbox)
        SettingsCategoryHeader(title = stringResource(R.string.gamepad_mapping), icon = Icons.Filled.SportsEsports)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column {
                val mappingRows = listOf(
                    Triple(stringResource(R.string.key_btn_a), SettingsActivity.KEY_BTN_A, btnA),
                    Triple(stringResource(R.string.key_btn_b), SettingsActivity.KEY_BTN_B, btnB),
                    Triple(stringResource(R.string.key_lb), SettingsActivity.KEY_BTN_L, btnL),
                    Triple(stringResource(R.string.key_rb), SettingsActivity.KEY_BTN_R, btnR),
                    Triple(stringResource(R.string.key_start), SettingsActivity.KEY_BTN_START, btnStart),
                    Triple(stringResource(R.string.key_select_btn), SettingsActivity.KEY_BTN_SELECT, btnSelect),
                    Triple(stringResource(R.string.key_dpad_up), SettingsActivity.KEY_BTN_UP, btnUp),
                    Triple(stringResource(R.string.key_dpad_down), SettingsActivity.KEY_BTN_DOWN, btnDown),
                    Triple(stringResource(R.string.key_dpad_left), SettingsActivity.KEY_BTN_LEFT, btnLeft),
                    Triple(stringResource(R.string.key_dpad_right), SettingsActivity.KEY_BTN_RIGHT, btnRight),
                )

                mappingRows.forEachIndexed { index, (label, key, currentVal) ->
                    SettingsRow(
                        title = label,
                        subtitle = SettingsActivity.getReadableKeyName(context, currentVal),
                        onClick = {
                            keyListeningTargetName = label
                            keyListeningPrefKey = key
                            onRequestKeyCapture { capturedCode ->
                                prefs.edit().putInt(key, capturedCode).apply()
                                when (key) {
                                    SettingsActivity.KEY_BTN_A -> btnA = capturedCode
                                    SettingsActivity.KEY_BTN_B -> btnB = capturedCode
                                    SettingsActivity.KEY_BTN_L -> btnL = capturedCode
                                    SettingsActivity.KEY_BTN_R -> btnR = capturedCode
                                    SettingsActivity.KEY_BTN_START -> btnStart = capturedCode
                                    SettingsActivity.KEY_BTN_SELECT -> btnSelect = capturedCode
                                    SettingsActivity.KEY_BTN_UP -> btnUp = capturedCode
                                    SettingsActivity.KEY_BTN_DOWN -> btnDown = capturedCode
                                    SettingsActivity.KEY_BTN_LEFT -> btnLeft = capturedCode
                                    SettingsActivity.KEY_BTN_RIGHT -> btnRight = capturedCode
                                }
                                keyListeningTargetName = null
                                keyListeningPrefKey = null
                            }
                        }
                    )
                    if (index < mappingRows.size - 1) {
                        HorizontalDivider(color = SurfaceBorderSubtle)
                    }
                }

                HorizontalDivider(color = SurfaceBorderSubtle)
                Box(modifier = Modifier.padding(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            prefs.edit()
                                .putInt(SettingsActivity.KEY_BTN_A, KeyEvent.KEYCODE_BUTTON_A)
                                .putInt(SettingsActivity.KEY_BTN_B, KeyEvent.KEYCODE_BUTTON_B)
                                .putInt(SettingsActivity.KEY_BTN_L, KeyEvent.KEYCODE_BUTTON_L1)
                                .putInt(SettingsActivity.KEY_BTN_R, KeyEvent.KEYCODE_BUTTON_R1)
                                .putInt(SettingsActivity.KEY_BTN_START, KeyEvent.KEYCODE_BUTTON_START)
                                .putInt(SettingsActivity.KEY_BTN_SELECT, KeyEvent.KEYCODE_BUTTON_SELECT)
                                .putInt(SettingsActivity.KEY_BTN_UP, KeyEvent.KEYCODE_DPAD_UP)
                                .putInt(SettingsActivity.KEY_BTN_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
                                .putInt(SettingsActivity.KEY_BTN_LEFT, KeyEvent.KEYCODE_DPAD_LEFT)
                                .putInt(SettingsActivity.KEY_BTN_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
                                .apply()

                            btnA = KeyEvent.KEYCODE_BUTTON_A
                            btnB = KeyEvent.KEYCODE_BUTTON_B
                            btnL = KeyEvent.KEYCODE_BUTTON_L1
                            btnR = KeyEvent.KEYCODE_BUTTON_R1
                            btnStart = KeyEvent.KEYCODE_BUTTON_START
                            btnSelect = KeyEvent.KEYCODE_BUTTON_SELECT
                            btnUp = KeyEvent.KEYCODE_DPAD_UP
                            btnDown = KeyEvent.KEYCODE_DPAD_DOWN
                            btnLeft = KeyEvent.KEYCODE_DPAD_LEFT
                            btnRight = KeyEvent.KEYCODE_DPAD_RIGHT
                            Toast.makeText(context, context.getString(R.string.toast_controls_reset), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = stringResource(R.string.reset_controls_default), fontSize = 13.sp)
                    }
                }
            }
        }

        // Section: Быстрые клавиши
        SettingsCategoryHeader(title = stringResource(R.string.hotkeys), icon = Icons.Filled.Bolt)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column {
                val hotkeyRows = listOf(
                    Triple(stringResource(R.string.quick_save), SettingsActivity.KEY_HOTKEY_SAVE_CODE, hotkeySave),
                    Triple(stringResource(R.string.quick_load), SettingsActivity.KEY_HOTKEY_LOAD_CODE, hotkeyLoad),
                    Triple(stringResource(R.string.pause_menu), SettingsActivity.KEY_HOTKEY_PAUSE_CODE, hotkeyPause),
                )

                hotkeyRows.forEachIndexed { index, (label, key, currentVal) ->
                    SettingsRow(
                        title = label,
                        subtitle = SettingsActivity.getReadableKeyName(context, currentVal),
                        onClick = {
                            keyListeningTargetName = label
                            keyListeningPrefKey = key
                            onRequestKeyCapture { capturedCode ->
                                prefs.edit().putInt(key, capturedCode).apply()
                                when (key) {
                                    SettingsActivity.KEY_HOTKEY_SAVE_CODE -> hotkeySave = capturedCode
                                    SettingsActivity.KEY_HOTKEY_LOAD_CODE -> hotkeyLoad = capturedCode
                                    SettingsActivity.KEY_HOTKEY_PAUSE_CODE -> hotkeyPause = capturedCode
                                }
                                keyListeningTargetName = null
                                keyListeningPrefKey = null
                            }
                        }
                    )
                    if (index < hotkeyRows.size - 1) {
                        HorizontalDivider(color = SurfaceBorderSubtle)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showDpadTypeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.dpad_style),
            options = listOf(stringResource(R.string.dpad_style_classic), stringResource(R.string.dpad_style_stick)),
            selectedIndex = dpadType,
            onSelect = {
                dpadType = it
                prefs.edit().putInt(SettingsActivity.KEY_DPAD_TYPE, it).apply()
                showDpadTypeDialog = false
            },
            onDismiss = { showDpadTypeDialog = false }
        )
    }

    if (showProfileDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.ab_layout),
            options = listOf(stringResource(R.string.ab_layout_standard), stringResource(R.string.ab_layout_swapped)),
            selectedIndex = layoutProfile,
            onSelect = {
                layoutProfile = it
                prefs.edit().putInt(SettingsActivity.KEY_LAYOUT_PROFILE, it).apply()
                showProfileDialog = false
            },
            onDismiss = { showProfileDialog = false }
        )
    }

    // Fullscreen interactive key capture overlay
    if (keyListeningTargetName != null && keyListeningPrefKey != null) {
        val targetName = keyListeningTargetName!!
        val prefKey = keyListeningPrefKey!!
        var showManualList by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE60C0C0E))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Gamepad,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.key_capture_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.key_capture_action, targetName),
                        color = AccentPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.key_capture_prompt),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    if (!showManualList) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { showManualList = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = stringResource(R.string.key_capture_manual), fontSize = 13.sp)
                        }
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        val xboxButtons = listOf(
                            KeyEvent.KEYCODE_BUTTON_A,
                            KeyEvent.KEYCODE_BUTTON_B,
                            KeyEvent.KEYCODE_BUTTON_X,
                            KeyEvent.KEYCODE_BUTTON_Y,
                            KeyEvent.KEYCODE_BUTTON_L1,
                            KeyEvent.KEYCODE_BUTTON_R1,
                            KeyEvent.KEYCODE_BUTTON_L2,
                            KeyEvent.KEYCODE_BUTTON_R2,
                            KeyEvent.KEYCODE_BUTTON_THUMBL,
                            KeyEvent.KEYCODE_BUTTON_THUMBR,
                            KeyEvent.KEYCODE_BUTTON_START,
                            KeyEvent.KEYCODE_BUTTON_SELECT,
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT
                        )

                        xboxButtons.forEach { code ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        prefs.edit().putInt(prefKey, code).apply()
                                        when (prefKey) {
                                            SettingsActivity.KEY_BTN_A -> btnA = code
                                            SettingsActivity.KEY_BTN_B -> btnB = code
                                            SettingsActivity.KEY_BTN_L -> btnL = code
                                            SettingsActivity.KEY_BTN_R -> btnR = code
                                            SettingsActivity.KEY_BTN_START -> btnStart = code
                                            SettingsActivity.KEY_BTN_SELECT -> btnSelect = code
                                            SettingsActivity.KEY_BTN_UP -> btnUp = code
                                            SettingsActivity.KEY_BTN_DOWN -> btnDown = code
                                            SettingsActivity.KEY_BTN_LEFT -> btnLeft = code
                                            SettingsActivity.KEY_BTN_RIGHT -> btnRight = code
                                            SettingsActivity.KEY_HOTKEY_SAVE_CODE -> hotkeySave = code
                                            SettingsActivity.KEY_HOTKEY_LOAD_CODE -> hotkeyLoad = code
                                            SettingsActivity.KEY_HOTKEY_PAUSE_CODE -> hotkeyPause = code
                                        }
                                        onCancelKeyCapture()
                                        keyListeningTargetName = null
                                        keyListeningPrefKey = null
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = SettingsActivity.getReadableKeyName(context, code),
                                    color = TextPrimary,
                                    fontSize = 13.sp
                                )
                                Text(text = stringResource(R.string.select), color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(color = SurfaceBorderSubtle)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                prefs.edit().putInt(prefKey, 0).apply()
                                when (prefKey) {
                                    SettingsActivity.KEY_BTN_A -> btnA = 0
                                    SettingsActivity.KEY_BTN_B -> btnB = 0
                                    SettingsActivity.KEY_BTN_L -> btnL = 0
                                    SettingsActivity.KEY_BTN_R -> btnR = 0
                                    SettingsActivity.KEY_BTN_START -> btnStart = 0
                                    SettingsActivity.KEY_BTN_SELECT -> btnSelect = 0
                                    SettingsActivity.KEY_BTN_UP -> btnUp = 0
                                    SettingsActivity.KEY_BTN_DOWN -> btnDown = 0
                                    SettingsActivity.KEY_BTN_LEFT -> btnLeft = 0
                                    SettingsActivity.KEY_BTN_RIGHT -> btnRight = 0
                                    SettingsActivity.KEY_HOTKEY_SAVE_CODE -> hotkeySave = 0
                                    SettingsActivity.KEY_HOTKEY_LOAD_CODE -> hotkeyLoad = 0
                                    SettingsActivity.KEY_HOTKEY_PAUSE_CODE -> hotkeyPause = 0
                                }
                                onCancelKeyCapture()
                                keyListeningTargetName = null
                                keyListeningPrefKey = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = stringResource(R.string.disable), color = Color(0xFFEF4444), fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                onCancelKeyCapture()
                                keyListeningTargetName = null
                                keyListeningPrefKey = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262638))
                        ) {
                            Text(text = stringResource(R.string.cancel), color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// SUB-MENU 3: SAVES MANAGER (Встроенный менеджер сохранений)
// =============================================================================

@Composable
fun SavesManagerSubMenu(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    var savesList by remember { mutableStateOf<List<StorageHelper.SaveItem>>(emptyList()) }
    var saveToDelete by remember { mutableStateOf<StorageHelper.SaveItem?>(null) }
    var fileToExport by remember { mutableStateOf<File?>(null) }

    fun refreshList() {
        coroutineScope.launch {
            val items = withContext(Dispatchers.IO) {
                StorageHelper.listAllSaves(context)
            }
            savesList = items
        }
    }

    LaunchedEffect(Unit) {
        refreshList()
    }

    // 1. SAF Import Save Launcher
    val importSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    StorageHelper.importSave(context, uri)
                }
                if (ok) {
                    Toast.makeText(context, context.getString(R.string.toast_save_imported), Toast.LENGTH_SHORT).show()
                    refreshList()
                } else {
                    Toast.makeText(context, context.getString(R.string.toast_save_import_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 2. SAF Export Save Launcher (CreateDocument)
    val exportSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destUri: Uri? ->
        val file = fileToExport
        if (destUri != null && file != null) {
            coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    StorageHelper.exportSave(context, file, destUri)
                }
                if (ok) {
                    Toast.makeText(context, context.getString(R.string.toast_save_exported, file.name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.toast_save_export_error), Toast.LENGTH_SHORT).show()
                }
                fileToExport = null
            }
        }
    }

    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val baseDirPath = remember { StorageHelper.getBaseDir(context).absolutePath }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Path Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.saves_data_folder),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = baseDirPath,
                    color = AccentPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { importSaveLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.saves_import_btn), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.saves_list_header, savesList.size),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (savesList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.saves_empty),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(savesList, key = { it.file.absolutePath }) { item ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.displayName,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val badgeColor = if (item.isBatterySave) AccentPrimary else Color(0xFF10B981)
                                    val badgeText = if (item.isBatterySave) stringResource(R.string.saves_battery_badge) else stringResource(R.string.saves_state_badge)
                                    Box(
                                        modifier = Modifier
                                            .background(badgeColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(text = badgeText, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    val kb = (item.sizeBytes / 1024L).coerceAtLeast(1L)
                                    Text(text = "$kb KB", color = TextSecondary, fontSize = 11.sp)
                                    Text(text = dateFormat.format(Date(item.lastModified)), color = TextSecondary, fontSize = 11.sp)
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // Export Button (CreateDocument)
                                IconButton(
                                    onClick = {
                                        fileToExport = item.file
                                        exportSaveLauncher.launch(item.file.name)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = stringResource(R.string.saves_export_tooltip),
                                        tint = AccentPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Share Button
                                IconButton(
                                    onClick = {
                                        try {
                                            val shareUri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                item.file
                                            )
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/octet-stream"
                                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.saves_share_title)))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, context.getString(R.string.toast_save_share_error), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = stringResource(R.string.saves_share_tooltip),
                                        tint = TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Delete Button
                                IconButton(
                                    onClick = { saveToDelete = item }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.saves_delete_tooltip),
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (saveToDelete != null) {
        val target = saveToDelete!!
        AlertDialog(
            onDismissRequest = { saveToDelete = null },
            containerColor = SurfaceElevated,
            title = {
                Text(text = stringResource(R.string.saves_delete_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Text(
                    text = stringResource(R.string.saves_delete_confirm, target.displayName),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        target.file.delete()
                        Toast.makeText(context, context.getString(R.string.toast_save_deleted), Toast.LENGTH_SHORT).show()
                        saveToDelete = null
                        refreshList()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(text = stringResource(R.string.delete), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { saveToDelete = null }) {
                    Text(text = stringResource(R.string.cancel), color = AccentPrimary)
                }
            }
        )
    }
}

// =============================================================================
// SUB-MENU 4: COVERS MANAGER (Встроенный менеджер обложек)
// =============================================================================

@Composable
fun CoversManagerSubMenu(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    var gamesList by remember { mutableStateOf<List<GameEntity>>(emptyList()) }
    var selectedGameForCover by remember { mutableStateOf<GameEntity?>(null) }
    var gameToDeleteCover by remember { mutableStateOf<GameEntity?>(null) }

    fun refreshGames() {
        coroutineScope.launch {
            val games = withContext(Dispatchers.IO) {
                db.gameDao().getAllGamesSync()
            }
            gamesList = games
        }
    }

    LaunchedEffect(Unit) {
        refreshGames()
    }

    // Gallery Picker for Cover
    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { pickedUri: Uri? ->
        val game = selectedGameForCover
        if (pickedUri != null && game != null) {
            coroutineScope.launch {
                val savedUri = withContext(Dispatchers.IO) {
                    val uriStr = StorageHelper.saveCustomCover(context, game.uri, pickedUri)
                    if (uriStr != null) {
                        db.gameDao().update(game.copy(customCoverUri = uriStr))
                    }
                    uriStr
                }
                if (savedUri != null) {
                    Toast.makeText(context, context.getString(R.string.toast_cover_updated, game.title), Toast.LENGTH_SHORT).show()
                    refreshGames()
                } else {
                    Toast.makeText(context, context.getString(R.string.toast_cover_load_error), Toast.LENGTH_SHORT).show()
                }
                selectedGameForCover = null
            }
        }
    }

    val coversPath = remember { StorageHelper.getCoversDir(context).absolutePath }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Path Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.covers_data_folder),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = coversPath,
                    color = AccentPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.covers_games_count, gamesList.size),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (gamesList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.covers_empty),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(gamesList, key = { it.uri }) { game ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Thumbnail Preview (Square 1:1)
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E1E28)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!game.customCoverUri.isNullOrBlank()) {
                                    AsyncImage(
                                        model = game.customCoverUri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    CartridgeCanvas(system = game.system, title = game.title)
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Title & System Info
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = game.title,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(game.badgeColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(text = game.system, color = game.badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    val statusText = if (!game.customCoverUri.isNullOrBlank()) stringResource(R.string.covers_status_custom) else stringResource(R.string.covers_status_cartridge)
                                    val statusColor = if (!game.customCoverUri.isNullOrBlank()) AccentPrimary else TextMuted
                                    Text(text = statusText, color = statusColor, fontSize = 11.sp)
                                }
                            }

                            // Actions
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = {
                                        selectedGameForCover = game
                                        coverPickerLauncher.launch("image/*")
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (game.customCoverUri.isNullOrBlank()) stringResource(R.string.covers_btn_upload) else stringResource(R.string.covers_btn_replace),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (!game.customCoverUri.isNullOrBlank()) {
                                    IconButton(onClick = { gameToDeleteCover = game }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.covers_btn_delete),
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (gameToDeleteCover != null) {
        val targetGame = gameToDeleteCover!!
        AlertDialog(
            onDismissRequest = { gameToDeleteCover = null },
            containerColor = SurfaceElevated,
            title = {
                Text(text = stringResource(R.string.covers_delete_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Text(
                    text = stringResource(R.string.covers_delete_confirm, targetGame.title),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                StorageHelper.deleteCustomCover(context, targetGame.uri)
                                db.gameDao().update(targetGame.copy(customCoverUri = null))
                            }
                            Toast.makeText(context, context.getString(R.string.toast_cover_reset), Toast.LENGTH_SHORT).show()
                            gameToDeleteCover = null
                            refreshGames()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(text = stringResource(R.string.delete), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { gameToDeleteCover = null }) {
                    Text(text = stringResource(R.string.cancel), color = AccentPrimary)
                }
            }
        )
    }
}

// =============================================================================
// SUB-MENU 5: ABOUT
// =============================================================================

@Composable
fun AboutSubMenu() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App & Core Details
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.about_app_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Box(
                        modifier = Modifier
                            .background(AccentPrimary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "v1.0.0",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about_app_specs),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }

        // Developers & Authors Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.about_team_title),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Lead Developer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Code,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Inner Dev",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = stringResource(R.string.about_lead_role),
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Co-Author & QA Tester
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lightbulb,
                        contentDescription = null,
                        tint = FavoriteGold,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "KatOriginal",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = stringResource(R.string.about_qa_role),
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = SurfaceBorderSubtle)
                Spacer(modifier = Modifier.height(14.dp))

                // Contact & Feedback Button
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:innernode.dev@gmail.com")
                                putExtra(Intent.EXTRA_SUBJECT, "mGBA Android — Feedback / Bug Report")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "innernode.dev@gmail.com", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Email,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.about_contact_btn), fontSize = 13.sp)
                }
            }
        }

        // Community & License
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.about_source_title),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.about_source_desc),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = SurfaceBorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.about_license_desc),
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// =============================================================================
// UI REUSABLE COMPONENTS
// =============================================================================

@Composable
fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(AccentPrimary.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AccentPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun SettingsCategoryHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AccentPrimary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title,
            color = AccentPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentPrimary,
                uncheckedTrackColor = Color(0xFF262638)
            )
        )
    }
}

@Composable
fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        modifier = Modifier.widthIn(max = 420.dp),
        title = {
            Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEachIndexed { index, text ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(index) }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onSelect(index) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AccentPrimary,
                                unselectedColor = TextSecondary
                            )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = text,
                            color = if (index == selectedIndex) AccentPrimary else TextPrimary,
                            fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel), color = AccentPrimary)
            }
        }
    )
}
