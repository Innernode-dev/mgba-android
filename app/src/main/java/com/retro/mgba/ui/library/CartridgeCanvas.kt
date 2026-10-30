package com.retro.mgba.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Procedural authentic retro Cartridge Canvas fallback.
 * Rendered when no box art image is present on disk.
 */
@Composable
fun CartridgeCanvas(
    system: String,
    title: String,
    modifier: Modifier = Modifier
) {
    val isGba = system.equals("GBA", ignoreCase = true)
    val isGb = system.equals("GB", ignoreCase = true)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121216)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when {
                isGba -> drawGbaCartridge(this)
                isGb -> drawGbCartridge(this)
                else -> drawGbcCartridge(this)
            }
        }

        // Title and system monogram printed on cartridge label
        Box(
            modifier = Modifier
                .fillMaxSize(0.72f)
                .padding(top = if (isGba) 32.dp else 48.dp, bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                color = Color(0xFFE2E8F0),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Convenience alias for CartridgeCanvas.
 */
@Composable
fun CartridgeFallback(
    system: String,
    title: String,
    modifier: Modifier = Modifier
) = CartridgeCanvas(system = system, title = title, modifier = modifier)

private fun drawGbaCartridge(scope: DrawScope) {
    val width = scope.size.width
    val height = scope.size.height
    val bodyColor = Color(0xFF3B1E6D)       // GBA Dark Violet Plastic
    val highlightColor = Color(0xFF5B3299)
    val labelBg = Color(0xFF1E152F)
    val goldContacts = Color(0xFFD97706)

    // Outer cartridge body
    scope.drawRoundRect(
        color = bodyColor,
        topLeft = Offset(width * 0.05f, height * 0.12f),
        size = Size(width * 0.90f, height * 0.80f),
        cornerRadius = CornerRadius(16f, 16f)
    )

    // Top grip bevel
    scope.drawRoundRect(
        color = highlightColor,
        topLeft = Offset(width * 0.15f, height * 0.15f),
        size = Size(width * 0.70f, height * 0.06f),
        cornerRadius = CornerRadius(6f, 6f)
    )

    // Label inset
    scope.drawRoundRect(
        color = labelBg,
        topLeft = Offset(width * 0.12f, height * 0.26f),
        size = Size(width * 0.76f, height * 0.54f),
        cornerRadius = CornerRadius(10f, 10f)
    )

    // Bottom pin contacts preview
    scope.drawRect(
        color = goldContacts,
        topLeft = Offset(width * 0.20f, height * 0.90f),
        size = Size(width * 0.60f, height * 0.02f)
    )
}

private fun drawGbCartridge(scope: DrawScope) {
    val width = scope.size.width
    val height = scope.size.height
    val bodyColor = Color(0xFF64748B)       // DMG Classic Grey
    val gripColor = Color(0xFF475569)
    val labelBg = Color(0xFF1E293B)

    // Main rectangular body
    scope.drawRoundRect(
        color = bodyColor,
        topLeft = Offset(width * 0.08f, height * 0.06f),
        size = Size(width * 0.84f, height * 0.88f),
        cornerRadius = CornerRadius(12f, 12f)
    )

    // Top grip ridges (DMG signature 5 horizontal bars)
    for (i in 0..4) {
        val yOffset = height * (0.09f + i * 0.028f)
        scope.drawRoundRect(
            color = gripColor,
            topLeft = Offset(width * 0.22f, yOffset),
            size = Size(width * 0.56f, height * 0.016f),
            cornerRadius = CornerRadius(4f, 4f)
        )
    }

    // Label inset
    scope.drawRoundRect(
        color = labelBg,
        topLeft = Offset(width * 0.14f, height * 0.28f),
        size = Size(width * 0.72f, height * 0.56f),
        cornerRadius = CornerRadius(8f, 8f)
    )
}

private fun drawGbcCartridge(scope: DrawScope) {
    val width = scope.size.width
    val height = scope.size.height
    val bodyColor = Color(0xFF0E7490)       // Translucent GBC Cyan/Teal
    val humpColor = Color(0xFF155E75)
    val labelBg = Color(0xFF082F49)

    // Top curved battery bulge
    scope.drawRoundRect(
        color = humpColor,
        topLeft = Offset(width * 0.20f, height * 0.04f),
        size = Size(width * 0.60f, height * 0.12f),
        cornerRadius = CornerRadius(20f, 20f)
    )

    // Main body
    scope.drawRoundRect(
        color = bodyColor,
        topLeft = Offset(width * 0.08f, height * 0.10f),
        size = Size(width * 0.84f, height * 0.84f),
        cornerRadius = CornerRadius(12f, 12f)
    )

    // Label inset
    scope.drawRoundRect(
        color = labelBg,
        topLeft = Offset(width * 0.14f, height * 0.26f),
        size = Size(width * 0.72f, height * 0.56f),
        cornerRadius = CornerRadius(8f, 8f)
    )
}
