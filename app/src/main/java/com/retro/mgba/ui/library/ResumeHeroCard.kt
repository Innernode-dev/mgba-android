package com.retro.mgba.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.retro.mgba.data.db.GameEntity
import com.retro.mgba.ui.navigation.handheldFocusable
import com.retro.mgba.ui.theme.AccentPrimary
import com.retro.mgba.ui.theme.ActiveIndicatorGreen
import com.retro.mgba.ui.theme.HeroBackgroundGradient
import com.retro.mgba.ui.theme.SurfaceBorderSubtle
import com.retro.mgba.ui.theme.TextMuted
import com.retro.mgba.ui.theme.TextPrimary
import com.retro.mgba.ui.theme.TextSecondary

/**
 * High-Impact "Resume Last Played" Hero Card for DuckStation UI.
 */
@Composable
fun ResumeHeroCard(
    game: GameEntity,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .handheldFocusable(
                shape = RoundedCornerShape(16.dp),
                onActivate = onResumeClick
            )
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onResumeClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorderSubtle)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HeroBackgroundGradient)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // High-Res Box Art or Cartridge Thumbnail Preview
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F0F14))
                ) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(game.customCoverUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Last played preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        loading = {
                            CartridgeCanvas(system = game.system, title = game.title)
                        },
                        error = {
                            CartridgeCanvas(system = game.system, title = game.title)
                        }
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Metadata Column & Resume Action
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        // "LAST PLAYED" micro-badge with pulsing dot
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(ActiveIndicatorGreen, CircleShape)
                            )
                            Text(
                                text = "НЕДАВНО ЗАПУСКАЛИ",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ActiveIndicatorGreen,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "•",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            // System Badge
                            Box(
                                modifier = Modifier
                                    .background(game.badgeColor, RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = game.system,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Game Title
                        Text(
                            text = game.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Subtitle: Time ago & Playtime
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = game.formattedLastPlayed,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "Время в игре: ${game.formattedPlayTime}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    // Quick Resume Button
                    Button(
                        onClick = onResumeClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.Start)
                            .height(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ПРОДОЛЖИТЬ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}
