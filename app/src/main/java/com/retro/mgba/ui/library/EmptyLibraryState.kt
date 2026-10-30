package com.retro.mgba.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro.mgba.ui.navigation.handheldFocusable
import com.retro.mgba.ui.theme.AccentPrimary
import com.retro.mgba.ui.theme.GbaPurple
import com.retro.mgba.ui.theme.GbGreen
import com.retro.mgba.ui.theme.GbcCyan
import com.retro.mgba.ui.theme.SurfaceElevated
import com.retro.mgba.ui.theme.TextPrimary
import com.retro.mgba.ui.theme.TextSecondary

/**
 * DuckStation Empty State displayed when no ROMs have been scanned yet.
 */
@Composable
fun EmptyLibraryState(
    onSelectFolderClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Retro controller icon container
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(SurfaceElevated, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = AccentPrimary,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Библиотека игр пуста",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Выберите папку с вашими ROM-играми Game Boy Advance (.gba), Game Boy (.gb) или Game Boy Color (.gbc), чтобы отобразить их плитками с обложками.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // CTA Select Folder Button
        Button(
            onClick = onSelectFolderClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .height(48.dp)
                .handheldFocusable(onActivate = onSelectFolderClick)
        ) {
            Icon(
                imageVector = Icons.Filled.CreateNewFolder,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Выбрать папку с играми",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Supported format pills
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FormatBadge(".GBA", GbaPurple)
            FormatBadge(".GBC", GbcCyan)
            FormatBadge(".GB", GbGreen)
        }
    }
}

@Composable
private fun FormatBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}
