package com.retro.mgba.ui.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro.mgba.R
import com.retro.mgba.scanner.ScanProgress
import com.retro.mgba.ui.theme.AccentPrimary
import com.retro.mgba.ui.theme.SurfaceElevated
import com.retro.mgba.ui.theme.TextPrimary

/**
 * Animated Scanning Feedback Bar displayed while FastRomScanner is indexing.
 */
@Composable
fun ScanFeedbackBar(
    scanProgress: ScanProgress,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanRotate")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val count = when (scanProgress) {
        is ScanProgress.Scanning -> scanProgress.totalDiscovered
        is ScanProgress.BatchDiscovered -> scanProgress.totalDiscovered
        is ScanProgress.Completed -> scanProgress.totalDiscovered
        else -> 0
    }

    val dirName = when (scanProgress) {
        is ScanProgress.Scanning -> scanProgress.currentDirectory
        is ScanProgress.Started -> scanProgress.folderDisplayName
        else -> "storage"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceElevated, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = "Scanning",
                        tint = AccentPrimary,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(rotation)
                    )
                    Text(
                        text = stringResource(R.string.scan_scanning_dir, dirName),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                Text(
                    text = stringResource(R.string.scan_found_count, count),
                    fontSize = 12.sp,
                    color = AccentPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = AccentPrimary,
                trackColor = Color(0xFF262634)
            )
        }
    }
}
