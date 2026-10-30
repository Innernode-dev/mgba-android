package com.retro.mgba.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.retro.mgba.data.db.GameEntity
import com.retro.mgba.ui.theme.AccentPrimary
import com.retro.mgba.ui.theme.DestructiveRed
import com.retro.mgba.ui.theme.FavoriteGold
import com.retro.mgba.ui.theme.SurfaceBorderSubtle
import com.retro.mgba.ui.theme.SurfaceCard
import com.retro.mgba.ui.theme.SurfaceElevated
import com.retro.mgba.ui.theme.TextMuted
import com.retro.mgba.ui.theme.TextPrimary
import com.retro.mgba.ui.theme.TextSecondary

/**
 * Long-press Context Menu for Game Cards.
 */
@Composable
fun GameContextMenu(
    game: GameEntity,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAssignCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onOpenDetails: () -> Unit,
    onRemove: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(SurfaceElevated)
            .width(220.dp)
    ) {
        // Play / Resume
        DropdownMenuItem(
            text = { Text("Играть", color = TextPrimary, fontWeight = FontWeight.Medium) },
            leadingIcon = {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = AccentPrimary)
            },
            onClick = {
                onDismiss()
                onPlay()
            }
        )

        // Toggle Favorite
        DropdownMenuItem(
            text = {
                Text(
                    text = if (game.isFavorite) "Убрать из избранного" else "В избранное",
                    color = TextPrimary
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (game.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = null,
                    tint = FavoriteGold
                )
            },
            onClick = {
                onDismiss()
                onToggleFavorite()
            }
        )

        // Assign / Change Custom Box Art
        DropdownMenuItem(
            text = {
                Text(
                    text = if (game.customCoverUri != null) "Сменить обложку" else "Обложка из галереи",
                    color = TextPrimary
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Color(0xFF38BDF8))
            },
            onClick = {
                onDismiss()
                onAssignCover()
            }
        )

        // Reset Custom Box Art if assigned
        if (game.customCoverUri != null) {
            DropdownMenuItem(
                text = { Text("Сбросить обложку", color = TextSecondary) },
                leadingIcon = {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = TextMuted)
                },
                onClick = {
                    onDismiss()
                    onRemoveCover()
                }
            )
        }

        // Game Details
        DropdownMenuItem(
            text = { Text("Подробности", color = TextPrimary) },
            leadingIcon = {
                Icon(Icons.Filled.Info, contentDescription = null, tint = TextSecondary)
            },
            onClick = {
                onDismiss()
                onOpenDetails()
            }
        )

        Divider(color = SurfaceBorderSubtle, thickness = 0.5.dp)

        // Remove from Library
        DropdownMenuItem(
            text = { Text("Удалить из библиотеки", color = DestructiveRed) },
            leadingIcon = {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = DestructiveRed)
            },
            onClick = {
                onDismiss()
                onRemove()
            }
        )
    }
}

/**
 * Detailed Information Dialog.
 */
@Composable
fun GameDetailsDialog(
    game: GameEntity,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = {
            Text(
                text = game.title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailRow("System", game.system)
                DetailRow("Region", game.region)
                if (game.gameCode != null) {
                    DetailRow("Game Code", game.gameCode)
                }
                DetailRow("File Size", game.formattedFileSize)
                DetailRow("Last Played", game.formattedLastPlayed)
                DetailRow("Total Playtime", game.formattedPlayTime)
                DetailRow("SRAM Save", if (game.sramPath != null) "Detected" else "None")
                Spacer(modifier = Modifier.height(4.dp))
                DetailRow("Storage URI", game.uri, isMonospace = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = AccentPrimary)
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String, isMonospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            maxLines = 1,
            fontWeight = if (isMonospace) FontWeight.Normal else FontWeight.Medium
        )
    }
}
