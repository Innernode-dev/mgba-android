package com.retro.mgba.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro.mgba.ui.navigation.handheldFocusable
import com.retro.mgba.ui.theme.AccentPrimary
import com.retro.mgba.ui.theme.OledBackground
import com.retro.mgba.ui.theme.SurfaceBorderSubtle
import com.retro.mgba.ui.theme.SurfaceElevated
import com.retro.mgba.ui.theme.TextMuted
import com.retro.mgba.ui.theme.TextPrimary
import com.retro.mgba.ui.theme.TextSecondary

/**
 * DuckStation Library Top Bar:
 * - App Logo & Version
 * - Search text field with quick clear button
 * - Sort Dropdown menu
 * - Add ROM Folder button
 * - Settings shortcut
 */
@Composable
fun LibraryTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    currentSort: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    onAddFolderClick: () -> Unit,
    onRescanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OledBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Upper Title & Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App branding
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "mGBA",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .background(AccentPrimary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "CORE 0.10.5",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentPrimary
                    )
                }
            }

            // Right Actions: Add Folder, Rescan, Sort, Settings
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Add Game Folder Button (Compact with glowing accent)
                IconButton(
                    onClick = onAddFolderClick,
                    modifier = Modifier
                        .size(36.dp)
                        .background(AccentPrimary.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                        .handheldFocusable(onActivate = onAddFolderClick)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = "Добавить папку",
                        tint = AccentPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Rescan Button
                IconButton(
                    onClick = onRescanClick,
                    modifier = Modifier
                        .size(36.dp)
                        .handheldFocusable(onActivate = onRescanClick)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = "Обновить папки",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Sort Dropdown Anchor
                Box {
                    IconButton(
                        onClick = { sortMenuExpanded = true },
                        modifier = Modifier
                            .size(36.dp)
                            .handheldFocusable(onActivate = { sortMenuExpanded = true })
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Сортировка",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                        modifier = Modifier.background(SurfaceElevated)
                    ) {
                        SortOrder.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = option.displayName,
                                            color = if (option == currentSort) AccentPrimary else TextPrimary,
                                            fontWeight = if (option == currentSort) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (option == currentSort) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = "Selected",
                                                tint = AccentPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onSortChange(option)
                                    sortMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Settings Button
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .size(36.dp)
                        .handheldFocusable(onActivate = onSettingsClick)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Настройки",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search Bar with Clear Button
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    text = "Поиск игр, регионов, кодов...",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                AnimatedVisibility(
                    visible = searchQuery.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    IconButton(onClick = onClearSearch) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear search",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceElevated,
                unfocusedContainerColor = SurfaceElevated,
                focusedBorderColor = AccentPrimary,
                unfocusedBorderColor = SurfaceBorderSubtle,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .handheldFocusable()
        )
    }
}
