package com.retro.mgba.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.retro.mgba.data.db.GameEntity
import com.retro.mgba.ui.navigation.handheldFocusable
import com.retro.mgba.ui.theme.FavoriteGold
import com.retro.mgba.ui.theme.FavoriteGoldUnselected
import com.retro.mgba.ui.theme.ScrimGradient
import com.retro.mgba.ui.theme.SurfaceCard

/**
 * Responsive 3:4 Box Art Grid Card Component.
 * Supports D-Pad focus navigation, long-press context menu, and favorite toggle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameGridCard(
    game: GameEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .handheldFocusable(
                shape = RoundedCornerShape(12.dp),
                onActivate = onClick,
                onAltAction = onLongClick,
                onSecondaryAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleFavorite()
                },
                onFocusStateChanged = { isFocused = it }
            )
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 8.dp else 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Box Art Image Loader with Cartridge Fallback (Square 1:1 for GB, GBC, GBA)
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(game.customCoverUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "${game.title} cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    CartridgeCanvas(system = game.system, title = game.title)
                },
                error = {
                    CartridgeCanvas(system = game.system, title = game.title)
                }
            )

            // Top Badges Row: System Badge (Left) & Favorite Star (Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // System Badge Pill
                Box(
                    modifier = Modifier
                        .background(
                            color = game.badgeColor.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = game.system,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                // Favorite Star Icon Button
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleFavorite()
                    },
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(0x77000000), shape = CircleShape)
                ) {
                    Icon(
                        imageVector = if (game.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (game.isFavorite) "Favorited" else "Favorite",
                        tint = if (game.isFavorite) FavoriteGold else FavoriteGoldUnselected,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Bottom Dark Scrim & Title/Metadata Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(ScrimGradient)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = game.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Region indicator
                        Text(
                            text = game.region,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFA1A1AA)
                        )

                        // File size indicator
                        Text(
                            text = game.formattedFileSize,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF71717A)
                        )
                    }
                }
            }
        }
    }
}
