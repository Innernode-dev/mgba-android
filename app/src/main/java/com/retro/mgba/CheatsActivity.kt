package com.retro.mgba

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.retro.mgba.core.NativeCore
import com.retro.mgba.data.cheats.CheatEntry
import com.retro.mgba.data.cheats.CheatRepository
import com.retro.mgba.ui.theme.*
import com.retro.mgba.util.LocaleHelper

/**
 * Per-game Cheats Manager for mGBA Android.
 * Supports GameShark, Action Replay, CodeBreaker, Game Genie, and Raw/VBA codes,
 * with live native mGBA parser validation and .cht file import.
 */
class CheatsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_GAME_TITLE = "EXTRA_GAME_TITLE"
        const val EXTRA_GAME_KEY = "EXTRA_GAME_KEY"
        const val EXTRA_PLATFORM = "EXTRA_PLATFORM"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayTitle = intent.getStringExtra(EXTRA_GAME_TITLE)?.takeIf { it.isNotBlank() } ?: "Game"
        val gameKey = intent.getStringExtra(EXTRA_GAME_KEY)?.takeIf { it.isNotBlank() }
            ?: CheatRepository.sanitizeGameTitle(displayTitle)
        val platform = intent.getIntExtra(EXTRA_PLATFORM, NativeCore.PLATFORM_GBA)

        setContent {
            RetroMgbaTheme {
                CheatsScreen(
                    displayTitle = displayTitle,
                    gameKey = gameKey,
                    platform = platform,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheatsScreen(
    displayTitle: String,
    gameKey: String,
    platform: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var cheats by remember { mutableStateOf(CheatRepository.loadCheats(context, gameKey)) }
    var editingCheat by remember { mutableStateOf<CheatEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deletingCheat by remember { mutableStateOf<CheatEntry?>(null) }

    fun persist(updated: List<CheatEntry>) {
        cheats = updated
        CheatRepository.saveCheats(context, gameKey, updated)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val imported = CheatRepository.importChtFromUri(context, uri)
        if (imported.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.cheats_import_empty), Toast.LENGTH_SHORT).show()
        } else {
            persist(cheats + imported)
            Toast.makeText(
                context,
                context.getString(R.string.cheats_import_success, imported.size),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val activeCount = remember(cheats) { cheats.count { it.enabled } }

    Scaffold(
        containerColor = OledBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.cheats_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            text = displayTitle,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = stringResource(R.string.cheats_import),
                            tint = AccentGlow
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceLowest)
            )
        },
        floatingActionButton = {
            if (cheats.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingCheat = null
                        showEditor = true
                    },
                    containerColor = AccentPrimary,
                    contentColor = TextOnAccent,
                    shape = RoundedCornerShape(16.dp),
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = {
                        Text(
                            text = stringResource(R.string.cheats_add),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                )
            }
        }
    ) { innerPadding ->
        if (cheats.isEmpty()) {
            CheatsEmptyState(
                platform = platform,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onAddClick = {
                    editingCheat = null
                    showEditor = true
                },
                onImportClick = { importLauncher.launch(arrayOf("*/*")) }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 92.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "summary_strip") {
                    CheatsStatusStrip(
                        activeCount = activeCount,
                        totalCount = cheats.size,
                        onDisableAll = {
                            persist(cheats.map { it.copy(enabled = false) })
                        }
                    )
                }

                items(cheats, key = { it.id }) { cheat ->
                    CheatRowCard(
                        cheat = cheat,
                        platform = platform,
                        onToggle = { enabled ->
                            persist(cheats.map { if (it.id == cheat.id) it.copy(enabled = enabled) else it })
                        },
                        onEdit = {
                            editingCheat = cheat
                            showEditor = true
                        },
                        onDelete = { deletingCheat = cheat }
                    )
                }
            }
        }
    }

    if (showEditor) {
        CheatEditorDialog(
            initial = editingCheat,
            platform = platform,
            onDismiss = { showEditor = false },
            onSave = { saved ->
                val updated = if (editingCheat != null) {
                    cheats.map { if (it.id == saved.id) saved else it }
                } else {
                    cheats + saved
                }
                persist(updated)
                showEditor = false
            }
        )
    }

    deletingCheat?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingCheat = null },
            containerColor = SurfaceElevated,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(
                    text = stringResource(R.string.cheats_delete_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(stringResource(R.string.cheats_delete_confirm, target.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        persist(cheats.filterNot { it.id == target.id })
                        deletingCheat = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = DestructiveRed)
                ) {
                    Text(stringResource(R.string.delete), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deletingCheat = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CheatsStatusStrip(
    activeCount: Int,
    totalCount: Int,
    onDisableAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(1.dp, SurfaceBorderSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (activeCount > 0) ActiveIndicatorGreen else TextMuted)
            )
            Text(
                text = stringResource(R.string.cheats_active_summary, activeCount, totalCount),
                color = if (activeCount > 0) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (activeCount > 0) {
            Surface(
                onClick = onDisableAll,
                shape = RoundedCornerShape(10.dp),
                color = SurfaceElevated,
                border = BorderStroke(1.dp, SurfaceBorderHighlight)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.cheats_disable_all),
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun CheatRowCard(
    cheat: CheatEntry,
    platform: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val activeSurface = Color(0xFF1A1729)
    val cardBg by animateColorAsState(
        targetValue = if (cheat.enabled) activeSurface else SurfaceCard,
        animationSpec = tween(180),
        label = "cheatCardBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (cheat.enabled) AccentPrimary.copy(alpha = 0.55f) else SurfaceBorderSubtle,
        animationSpec = tween(180),
        label = "cheatCardBorder"
    )
    val railColor by animateColorAsState(
        targetValue = if (cheat.enabled) ActiveIndicatorGreen else SurfaceBorderSubtle,
        animationSpec = tween(180),
        label = "cheatCardRail"
    )

    val previewLines = remember(cheat.lines) { cheat.lines.take(2) }
    val hiddenCount = (cheat.lines.size - previewLines.size).coerceAtLeast(0)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Hardware-style status rail on the left edge
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(railColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = cheat.name,
                            color = if (cheat.enabled) TextPrimary else TextPrimary.copy(alpha = 0.78f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (cheat.enabled) AccentPrimary.copy(alpha = 0.22f)
                                    else SurfaceElevated
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(CheatRepository.getFormatLabelRes(platform, cheat.format)),
                                color = if (cheat.enabled) AccentGlow else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Switch(
                        checked = cheat.enabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentPrimary,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = SurfaceElevated,
                            uncheckedBorderColor = SurfaceBorderHighlight
                        )
                    )
                }

                // Inset monospace hex code preview
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceLowest)
                        .border(1.dp, SurfaceBorderSubtle.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        previewLines.forEach { line ->
                            Text(
                                text = line,
                                color = if (cheat.enabled) GbcCyan else TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (hiddenCount > 0) {
                            Text(
                                text = stringResource(R.string.cheats_more_lines, hiddenCount),
                                color = TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.cheats_edit),
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = DestructiveRed.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatsEmptyState(
    platform: Int,
    modifier: Modifier = Modifier,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AccentPrimary.copy(alpha = 0.14f))
                .border(1.dp, AccentPrimary.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = null,
                tint = AccentGlow,
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.cheats_empty_title),
            color = TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(
                if (platform == NativeCore.PLATFORM_GB) R.string.cheats_empty_desc_gb
                else R.string.cheats_empty_desc_gba
            ),
            color = TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onAddClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentPrimary,
                contentColor = TextOnAccent
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.cheats_add), fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onImportClick,
            border = BorderStroke(1.dp, SurfaceBorderHighlight),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            Icon(Icons.Default.FileOpen, contentDescription = null, tint = AccentGlow, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.cheats_import), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CheatEditorDialog(
    initial: CheatEntry?,
    platform: Int,
    onDismiss: () -> Unit,
    onSave: (CheatEntry) -> Unit
) {
    val context = LocalContext.current
    val formats = remember(platform) { CheatRepository.getFormatsForPlatform(platform) }

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var codeText by remember { mutableStateOf(initial?.lines?.joinToString("\n").orEmpty()) }
    var selectedFormat by remember { mutableIntStateOf(initial?.format ?: CheatEntry.FORMAT_AUTODETECT) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val selectedOption = remember(selectedFormat, formats) {
        formats.firstOrNull { it.nativeType == selectedFormat } ?: formats.first()
    }
    val parsedLines = remember(codeText) { CheatRepository.normalizeCodeLines(codeText) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceCard,
            border = BorderStroke(1.dp, SurfaceBorderHighlight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(
                        if (initial != null) R.string.cheats_edit_title else R.string.cheats_add_title
                    ),
                    color = TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )

                // Cheat name
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.cheats_name_label)) },
                    placeholder = { Text(stringResource(R.string.cheats_name_placeholder), color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceBorderHighlight,
                        focusedLabelColor = AccentGlow,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentPrimary,
                        focusedContainerColor = SurfaceLowest,
                        unfocusedContainerColor = SurfaceLowest
                    )
                )

                // Format selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.cheats_format_label),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        formats.forEach { option ->
                            val selected = option.nativeType == selectedFormat
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedFormat = option.nativeType
                                    errorMessage = null
                                },
                                label = {
                                    Text(
                                        text = stringResource(option.labelResId),
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = SurfaceLowest,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = AccentPrimary.copy(alpha = 0.25f),
                                    selectedLabelColor = TextPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = SurfaceBorderSubtle,
                                    selectedBorderColor = AccentPrimary
                                )
                            )
                        }
                    }
                }

                // Code input header + Clipboard paste button
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.cheats_code_label),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (parsedLines.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.cheats_lines_count, parsedLines.size),
                                    color = GbcCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Surface(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                    if (!clipText.isNullOrBlank()) {
                                        codeText = if (codeText.isBlank()) clipText.trim() else "$codeText\n${clipText.trim()}"
                                        errorMessage = null
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = SurfaceElevated,
                                border = BorderStroke(1.dp, SurfaceBorderHighlight)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = null,
                                        tint = AccentGlow,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.cheats_paste),
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = codeText,
                        onValueChange = {
                            codeText = it
                            errorMessage = null
                        },
                        placeholder = {
                            Text(
                                text = selectedOption.exampleHint,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = GbcCyan,
                            lineHeight = 20.sp
                        ),
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = SurfaceBorderHighlight,
                            cursorColor = AccentPrimary,
                            focusedContainerColor = SurfaceLowest,
                            unfocusedContainerColor = SurfaceLowest
                        )
                    )
                }

                // Explicit validation error banner
                errorMessage?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DestructiveRed.copy(alpha = 0.14f))
                            .border(1.dp, DestructiveRed.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = DestructiveRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = err,
                            color = Color(0xFFFCA5A5),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val trimmedName = name.trim()
                            val lines = CheatRepository.normalizeCodeLines(codeText)
                            when {
                                trimmedName.isEmpty() -> {
                                    errorMessage = context.getString(R.string.cheats_error_empty_name)
                                }
                                lines.isEmpty() -> {
                                    errorMessage = context.getString(R.string.cheats_error_empty_code)
                                }
                                !NativeCore.validateCheatLines(platform, lines, selectedFormat) -> {
                                    errorMessage = context.getString(R.string.cheats_error_invalid_code)
                                }
                                else -> {
                                    val entry = if (initial != null) {
                                        initial.copy(
                                            name = trimmedName,
                                            lines = lines,
                                            format = selectedFormat
                                        )
                                    } else {
                                        CheatEntry(
                                            name = trimmedName,
                                            lines = lines,
                                            format = selectedFormat,
                                            enabled = true
                                        )
                                    }
                                    onSave(entry)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = TextOnAccent
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cheats_save),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
