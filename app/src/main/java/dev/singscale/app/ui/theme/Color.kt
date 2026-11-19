package dev.singscale.app.ui.theme

import androidx.compose.ui.graphics.Color
import dev.singscale.app.core.QualityGrade
import dev.singscale.app.core.TransportPathType
import dev.singscale.app.core.TunnelStatus

// Deep OLED Titanium-Obsidian Surface Hierarchy
val ObsidianBg = Color(0xFF05070E)
val ObsidianSurface = Color(0xFF0B0F1B)
val ObsidianCard = Color(0xFF101626)
val ObsidianCardBottom = Color(0xFF0B101D)
val ObsidianElevated = Color(0xFF172036)
val ObsidianBorder = Color(0xFF212D48)

// Specular Rim Lighting Tokens
val GlassRimTop = Color(0x26FFFFFF)
val GlassRimMid = Color(0x0DFFFFFF)
val GlassRimBottom = Color(0x05FFFFFF)
val GlassDockBg = Color(0xEB0B101E)

// Luminous Brand & Semantic Accents
val CyanPrimary = Color(0xFF22D3EE)
val CyanBright = Color(0xFF67E8F9)
val CyanDeep = Color(0xFF0891B2)
val IndigoAccent = Color(0xFF818CF8)
val IndigoDeep = Color(0xFF4F46E5)

val EmeraldGood = Color(0xFF10B981)
val EmeraldSoft = Color(0xFF34D399)
val AmberWarn = Color(0xFFFBBF24)
val CoralAlert = Color(0xFFFB7185)

// High-Contrast Editorial Text Hierarchy
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

fun TransportPathType.accentColor(): Color = when (this) {
    TransportPathType.DIRECT -> EmeraldGood
    TransportPathType.PEER_RELAY -> CyanPrimary
    TransportPathType.DERP -> AmberWarn
    TransportPathType.OFFLINE -> TextMuted
}

fun QualityGrade.accentColor(): Color = when (this) {
    QualityGrade.S -> EmeraldGood
    QualityGrade.A -> CyanPrimary
    QualityGrade.B -> AmberWarn
    QualityGrade.C -> CoralAlert
    QualityGrade.OFFLINE -> TextMuted
}

fun TunnelStatus.accentColor(): Color = when (this) {
    TunnelStatus.RUNNING -> EmeraldGood
    TunnelStatus.STARTING -> CyanPrimary
    TunnelStatus.NEEDS_LOGIN -> AmberWarn
    TunnelStatus.STOPPING -> AmberWarn
    TunnelStatus.ERROR -> CoralAlert
    TunnelStatus.STOPPED -> CyanPrimary
}
