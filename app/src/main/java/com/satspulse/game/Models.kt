package com.satspulse.game

import androidx.compose.ui.graphics.Color

data class LevelSpec(
    val id: Int,
    val name: String,
    val subtitle: String,
    val unlockSku: String?,
    val priceSats: Int,
    val targetScore: Int,
    val seconds: Int,
    val targetRadius: Float,
    val driftSpeed: Float,
    val accentA: Color,
    val accentB: Color
)

data class SkinSpec(
    val sku: String,
    val name: String,
    val description: String,
    val priceSats: Int,
    val accentA: Color,
    val accentB: Color
)

val LEVELS = listOf(
    LevelSpec(1, "Neon Bloom", "Entrá en ritmo", null, 0, 850, 24, 58f, 0.65f, Color(0xFF20E3B2), Color(0xFF7C5CFF)),
    LevelSpec(2, "Hyper Drift", "Objetivos más veloces", "level_2", 120, 1350, 25, 49f, 0.88f, Color(0xFFFF4D8D), Color(0xFF7C5CFF)),
    LevelSpec(3, "Solar Rush", "Combos que explotan", "level_3", 220, 1900, 26, 44f, 1.08f, Color(0xFFFFB800), Color(0xFFFF4D8D)),
    LevelSpec(4, "Quantum Rain", "Precisión y velocidad", "level_4", 360, 2500, 27, 38f, 1.28f, Color(0xFF00D1FF), Color(0xFF20E3B2)),
    LevelSpec(5, "Singularity", "El núcleo final", "level_5", 650, 3200, 28, 34f, 1.52f, Color(0xFFFFF36A), Color(0xFFFF4D8D))
)

val SKINS = listOf(
    SkinSpec("skin_default", "Pulse Original", "Neón limpio de fábrica", 0, Color(0xFF20E3B2), Color(0xFF7C5CFF)),
    SkinSpec("skin_cyber", "Cyber Aurora", "Brillos turquesa y violeta", 90, Color(0xFF00D1FF), Color(0xFF7C5CFF)),
    SkinSpec("skin_magma", "Magma Pop", "Impactos naranja y rosa", 160, Color(0xFFFFB800), Color(0xFFFF4D8D)),
    SkinSpec("skin_ice", "Zero Frost", "Azul eléctrico y hielo", 210, Color(0xFF00D1FF), Color(0xFFB9F6FF))
)

sealed interface Screen {
    data object Home : Screen
    data class Game(val levelId: Int) : Screen
    data object Shop : Screen
}

data class CheckoutSession(
    val orderToken: String,
    val sku: String,
    val priceSats: Int,
    val checkoutUrl: String
)

data class PurchaseStatus(
    val paid: Boolean,
    val sku: String?,
    val status: String
)
