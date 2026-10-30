package com.retro.mgba.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// OLED Dark Base Palette
val OledBackground = Color(0xFF0C0C0E)
val SurfaceLowest = Color(0xFF101014)
val SurfaceCard = Color(0xFF16161D)
val SurfaceElevated = Color(0xFF1E1E28)
val SurfaceContainerHigh = Color(0xFF262634)
val SurfaceBorderSubtle = Color(0xFF2A2A3A)
val SurfaceBorderHighlight = Color(0xFF3F3F56)

// Text Colors
val TextPrimary = Color(0xFFF3F4F6)
val TextSecondary = Color(0xFF9CA3AF)
val TextMuted = Color(0xFF6B7280)
val TextOnAccent = Color(0xFFFFFFFF)

// Primary Accent & Handheld Glow
val AccentPrimary = Color(0xFF8B5CF6)       // Electric Violet
val AccentPrimaryHover = Color(0xFF7C3AED)
val AccentGlow = Color(0xFFA78BFA)
val FocusBorderGlow = Color(0xFF9061F9)

// System-Specific Badges & Accents
val GbaPurple = Color(0xFF8B5CF6)           // Game Boy Advance
val GbaPurpleDark = Color(0xFF4C1D95)
val GbaPurpleBadge = Color(0xFF6D28D9)

val GbGreen = Color(0xFF84CC16)             // Game Boy DMG
val GbGreenDark = Color(0xFF365314)
val GbGreenBadge = Color(0xFF4D7C0F)
val GbCartridgeGrey = Color(0xFF94A3B8)

val GbcCyan = Color(0xFF06B6D4)              // Game Boy Color Cyan
val GbcCyanBadge = Color(0xFF0891B2)
val GbcMagenta = Color(0xFFEC4899)           // Game Boy Color Magenta

// Status & Special Accents
val FavoriteGold = Color(0xFFF59E0B)
val FavoriteGoldUnselected = Color(0x66FFFFFF)
val DestructiveRed = Color(0xFFEF4444)
val ActiveIndicatorGreen = Color(0xFF10B981)

// Gradients
val HeroBackgroundGradient = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF1E1E2C),
        Color(0xFF121218)
    )
)

val FocusGradientBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF8B5CF6),
        Color(0xFF06B6D4)
    )
)

val ScrimGradient = Brush.verticalGradient(
    colors = listOf(
        Color.Transparent,
        Color(0xCC000000),
        Color(0xF00C0C0E)
    )
)
