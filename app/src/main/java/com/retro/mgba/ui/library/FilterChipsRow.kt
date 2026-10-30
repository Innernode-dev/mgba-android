package com.retro.mgba.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro.mgba.R
import com.retro.mgba.ui.navigation.handheldFocusable
import com.retro.mgba.ui.theme.AccentPrimary
import com.retro.mgba.ui.theme.FavoriteGold
import com.retro.mgba.ui.theme.GbaPurple
import com.retro.mgba.ui.theme.GbGreen
import com.retro.mgba.ui.theme.GbcCyan
import com.retro.mgba.ui.theme.SurfaceBorderSubtle
import com.retro.mgba.ui.theme.SurfaceCard
import com.retro.mgba.ui.theme.TextMuted
import com.retro.mgba.ui.theme.TextPrimary
import com.retro.mgba.ui.theme.TextSecondary

/**
 * DuckStation Filter Chips Row:
 * Categories: All, GBA, GB, GBC, Favorites.
 */
@Composable
fun FilterChipsRow(
    selectedFilter: GameFilter,
    onFilterSelected: (GameFilter) -> Unit,
    countsByFilter: Map<GameFilter, Int>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    val platformFilters = listOf(GameFilter.GBA, GameFilter.GB, GameFilter.GBC)
    val activePlatforms = platformFilters.filter { (countsByFilter[it] ?: 0) > 0 }
    val platformsWithGamesCount = activePlatforms.size

    val visibleFilters = mutableListOf<GameFilter>()

    // 1. Вкладка «Все» появляется ТОЛЬКО когда активных платформ 2 или больше
    if (platformsWithGamesCount >= 2) {
        visibleFilters.add(GameFilter.ALL)
    }

    // 2. Вкладка конкретной платформы отображается ТОЛЬКО если для неё есть хотя бы 1 игра
    visibleFilters.addAll(activePlatforms)

    // 3. Вкладка «Избранное» отображается, если в ней есть хотя бы 1 игра или она сейчас выбрана
    if ((countsByFilter[GameFilter.FAVORITES] ?: 0) > 0 || selectedFilter == GameFilter.FAVORITES) {
        visibleFilters.add(GameFilter.FAVORITES)
    }

    if (visibleFilters.isEmpty()) {
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        visibleFilters.forEach { filter ->
            val isSelected = filter == selectedFilter
            val count = countsByFilter[filter] ?: 0

            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(filter) },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // System indicator dot or star
                        when (filter) {
                            GameFilter.ALL -> {}
                            GameFilter.GBA -> DotIndicator(GbaPurple)
                            GameFilter.GB -> DotIndicator(GbGreen)
                            GameFilter.GBC -> DotIndicator(GbcCyan)
                            GameFilter.FAVORITES -> {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = if (isSelected) FavoriteGold else TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        val chipLabel = when (filter) {
                            GameFilter.ALL -> stringResource(R.string.tab_all)
                            GameFilter.GBA -> stringResource(R.string.tab_gba)
                            GameFilter.GB -> stringResource(R.string.tab_gb)
                            GameFilter.GBC -> stringResource(R.string.tab_gbc)
                            GameFilter.FAVORITES -> stringResource(R.string.tab_favorites)
                        }

                        Text(
                            text = chipLabel,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 12.sp
                        )

                        // Count pill
                        Text(
                            text = "($count)",
                            fontSize = 11.sp,
                            color = if (isSelected) TextPrimary else TextMuted
                        )
                    }
                },
                shape = RoundedCornerShape(20.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SurfaceCard,
                    labelColor = TextSecondary,
                    selectedContainerColor = AccentPrimary.copy(alpha = 0.25f),
                    selectedLabelColor = TextPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = SurfaceBorderSubtle,
                    selectedBorderColor = AccentPrimary,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.5.dp
                ),
                modifier = Modifier.handheldFocusable(
                    shape = RoundedCornerShape(20.dp),
                    onActivate = { onFilterSelected(filter) }
                )
            )
        }
    }
}

@Composable
private fun DotIndicator(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}
