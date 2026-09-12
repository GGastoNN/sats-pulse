package com.satspulse.game

import androidx.compose.ui.graphics.Color

data class LocalizedText(
    val es: String,
    val en: String,
    val pt: String
)

enum class AppLanguage(val code: String) {
    ES("es"), EN("en"), PT("pt");

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: ES
    }
}

data class LevelSpec(
    val id: Int,
    val name: LocalizedText,
    val subtitle: LocalizedText,
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
    val name: LocalizedText,
    val description: LocalizedText,
    val priceSats: Int,
    val accentA: Color,
    val accentB: Color
)

enum class ChallengeKind { RUNS, TOTAL_SCORE, BEST_COMBO, PERFECT_RUNS, LEVEL_SCORE }

data class ChallengeSpec(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val kind: ChallengeKind,
    val target: Int,
    val xpReward: Int,
    val levelId: Int? = null
)

data class PlayerStats(
    val xp: Int = 0,
    val totalRuns: Int = 0,
    val totalScore: Int = 0,
    val bestCombo: Int = 0,
    val perfectRuns: Int = 0
) {
    val rank: Int get() = (xp / 500) + 1
    val rankProgress: Float get() = (xp % 500) / 500f
}

val LEVELS = listOf(
    LevelSpec(1, LocalizedText("Neon Bloom", "Neon Bloom", "Neon Bloom"), LocalizedText("Entrá en ritmo", "Find the rhythm", "Entre no ritmo"), null, 0, 850, 24, 58f, 0.65f, Color(0xFF20E3B2), Color(0xFF7C5CFF)),
    LevelSpec(2, LocalizedText("Hyper Drift", "Hyper Drift", "Hyper Drift"), LocalizedText("Objetivos más veloces", "Faster targets", "Alvos mais rápidos"), "level_2", 120, 1350, 25, 49f, 0.88f, Color(0xFFFF4D8D), Color(0xFF7C5CFF)),
    LevelSpec(3, LocalizedText("Solar Rush", "Solar Rush", "Solar Rush"), LocalizedText("Combos que explotan", "Explosive combos", "Combos explosivos"), "level_3", 220, 1900, 26, 44f, 1.08f, Color(0xFFFFB800), Color(0xFFFF4D8D)),
    LevelSpec(4, LocalizedText("Quantum Rain", "Quantum Rain", "Quantum Rain"), LocalizedText("Precisión y velocidad", "Precision and speed", "Precisão e velocidade"), "level_4", 360, 2500, 27, 38f, 1.28f, Color(0xFF00D1FF), Color(0xFF20E3B2)),
    LevelSpec(5, LocalizedText("Singularity", "Singularity", "Singularity"), LocalizedText("El núcleo final", "The final core", "O núcleo final"), "level_5", 650, 3200, 28, 34f, 1.52f, Color(0xFFFFF36A), Color(0xFFFF4D8D)),
    LevelSpec(6, LocalizedText("Nova Circuit", "Nova Circuit", "Nova Circuit"), LocalizedText("Fiebre de combo", "Combo fever", "Febre de combo"), "level_6", 900, 3900, 29, 32f, 1.72f, Color(0xFF8CFF6A), Color(0xFF00D1FF)),
    LevelSpec(7, LocalizedText("Void Runner", "Void Runner", "Void Runner"), LocalizedText("Cero margen de error", "No room for error", "Sem margem para erro"), "level_7", 1250, 4700, 30, 30f, 1.92f, Color(0xFFB66CFF), Color(0xFFFF4D8D)),
    LevelSpec(8, LocalizedText("Omega Pulse", "Omega Pulse", "Omega Pulse"), LocalizedText("Dominá el pulso", "Master the pulse", "Domine o pulso"), "level_8", 1800, 5700, 32, 28f, 2.12f, Color(0xFFFFF36A), Color(0xFF00E5FF))
)

val SKINS = listOf(
    SkinSpec("skin_default", LocalizedText("Pulse Original", "Pulse Original", "Pulse Original"), LocalizedText("Neón limpio de fábrica", "Clean factory neon", "Neon limpo de fábrica"), 0, Color(0xFF20E3B2), Color(0xFF7C5CFF)),
    SkinSpec("skin_cyber", LocalizedText("Cyber Aurora", "Cyber Aurora", "Cyber Aurora"), LocalizedText("Turquesa y violeta", "Turquoise and violet", "Turquesa e violeta"), 90, Color(0xFF00D1FF), Color(0xFF7C5CFF)),
    SkinSpec("skin_magma", LocalizedText("Magma Pop", "Magma Pop", "Magma Pop"), LocalizedText("Naranja y rosa", "Orange and pink", "Laranja e rosa"), 160, Color(0xFFFFB800), Color(0xFFFF4D8D)),
    SkinSpec("skin_ice", LocalizedText("Zero Frost", "Zero Frost", "Zero Frost"), LocalizedText("Azul eléctrico y hielo", "Electric blue and ice", "Azul elétrico e gelo"), 210, Color(0xFF00D1FF), Color(0xFFB9F6FF)),
    SkinSpec("skin_void", LocalizedText("Void Prism", "Void Prism", "Void Prism"), LocalizedText("Violeta profundo y plasma", "Deep violet and plasma", "Violeta profundo e plasma"), 280, Color(0xFFB66CFF), Color(0xFFFF5FD2)),
    SkinSpec("skin_lime", LocalizedText("Toxic Lime", "Toxic Lime", "Toxic Lime"), LocalizedText("Verde ácido y cian", "Acid green and cyan", "Verde ácido e ciano"), 320, Color(0xFF8CFF6A), Color(0xFF00E5FF))
)

val CHALLENGES = listOf(
    ChallengeSpec("runs_5", LocalizedText("Calentamiento", "Warm-up", "Aquecimento"), LocalizedText("Jugá 5 partidas", "Play 5 runs", "Jogue 5 partidas"), ChallengeKind.RUNS, 5, 120),
    ChallengeSpec("score_10000", LocalizedText("Sobrecarga", "Overload", "Sobrecarga"), LocalizedText("Acumulá 10.000 puntos", "Reach 10,000 total points", "Acumule 10.000 pontos"), ChallengeKind.TOTAL_SCORE, 10_000, 180),
    ChallengeSpec("combo_12", LocalizedText("Cadena viva", "Live chain", "Corrente viva"), LocalizedText("Lográ combo x12", "Reach a x12 combo", "Alcance combo x12"), ChallengeKind.BEST_COMBO, 12, 220),
    ChallengeSpec("perfect_2", LocalizedText("Pulso limpio", "Clean pulse", "Pulso limpo"), LocalizedText("Terminá 2 partidas sin fallos", "Finish 2 runs with no misses", "Termine 2 partidas sem erros"), ChallengeKind.PERFECT_RUNS, 2, 260),
    ChallengeSpec("level3_2500", LocalizedText("Sol encendido", "Solar ignition", "Ignição solar"), LocalizedText("Superá 2.500 puntos en Solar Rush", "Score 2,500 in Solar Rush", "Faça 2.500 em Solar Rush"), ChallengeKind.LEVEL_SCORE, 2_500, 300, levelId = 3),
    ChallengeSpec("level5_4200", LocalizedText("Singularidad rota", "Broken singularity", "Singularidade rompida"), LocalizedText("Superá 4.200 puntos en Singularity", "Score 4,200 in Singularity", "Faça 4.200 em Singularity"), ChallengeKind.LEVEL_SCORE, 4_200, 420, levelId = 5)
)

sealed interface Screen {
    data object Home : Screen
    data class Game(val levelId: Int) : Screen
    data object Shop : Screen
    data object Challenges : Screen
}

data class CheckoutSession(
    val orderToken: String,
    val sku: String,
    val priceSats: Int,
    val checkoutUrl: String,
    val provider: String = "lightning"
)

data class PurchaseStatus(
    val paid: Boolean,
    val sku: String?,
    val status: String
)
