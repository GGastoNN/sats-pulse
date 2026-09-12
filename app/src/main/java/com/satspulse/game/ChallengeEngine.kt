package com.satspulse.game

fun challengeProgress(challenge: ChallengeSpec, stats: PlayerStats, bestScores: Map<Int, Int>): Int = when (challenge.kind) {
    ChallengeKind.RUNS -> stats.totalRuns
    ChallengeKind.TOTAL_SCORE -> stats.totalScore
    ChallengeKind.BEST_COMBO -> stats.bestCombo
    ChallengeKind.PERFECT_RUNS -> stats.perfectRuns
    ChallengeKind.LEVEL_SCORE -> bestScores[challenge.levelId] ?: 0
}.coerceAtMost(challenge.target)

fun challengeCompleted(challenge: ChallengeSpec, stats: PlayerStats, bestScores: Map<Int, Int>): Boolean =
    challengeProgress(challenge, stats, bestScores) >= challenge.target
