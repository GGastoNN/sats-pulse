package com.satspulse.game

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SatsPulseTheme {
                SatsPulseApp()
            }
        }
    }
}

private data class PendingPurchase(
    val sku: String,
    val label: String,
    val priceSats: Int,
    val session: CheckoutSession? = null,
    val loading: Boolean = false,
    val checking: Boolean = false,
    val error: String? = null
)

@Composable
private fun SatsPulseApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GameStore(context.applicationContext) }
    val payments = remember { PaymentClient() }
    val unlocked by store.unlockedSkus.collectAsStateWithLifecycleCompat(emptySet())
    val selectedSkinSku by store.selectedSkin.collectAsStateWithLifecycleCompat("skin_default")
    val language by store.language.collectAsStateWithLifecycleCompat(AppLanguage.ES)
    val stats by store.stats.collectAsStateWithLifecycleCompat(PlayerStats())
    val claimed by store.claimedChallenges.collectAsStateWithLifecycleCompat(emptySet())
    val bestScores by store.bestScores.collectAsStateWithLifecycleCompat(emptyMap())
    val selectedSkin = SKINS.firstOrNull { it.sku == selectedSkinSku } ?: SKINS.first()

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var pending by remember { mutableStateOf<PendingPurchase?>(null) }
    val installId = remember { getInstallId(context) }

    fun beginPurchase(sku: String, label: String, price: Int) {
        pending = PendingPurchase(sku, label, price, loading = true)
        scope.launch {
            runCatching { payments.createCheckout(sku, installId) }
                .onSuccess { session ->
                    pending = pending?.copy(
                        session = session,
                        priceSats = session.priceSats,
                        loading = false,
                        checking = true,
                        error = null
                    )
                    openUrl(context, session.checkoutUrl)
                }
                .onFailure { error ->
                    pending = pending?.copy(loading = false, checking = false, error = error.message ?: "payment_error")
                }
        }
    }

    fun verifyPurchase() {
        val current = pending ?: return
        val session = current.session ?: return
        pending = current.copy(checking = true, error = null)
        scope.launch {
            runCatching { payments.status(session.orderToken) }
                .onSuccess { status ->
                    if (status.paid && status.sku == current.sku) {
                        store.unlockSku(current.sku)
                        pending = null
                    } else {
                        pending = pending?.copy(
                            checking = false,
                            error = when (status.status) {
                                "active", "unpaid" -> tr(language, "stillActive")
                                "verification_unavailable" -> tr(language, "verificationUnavailable")
                                else -> status.status
                            }
                        )
                    }
                }
                .onFailure { error ->
                    pending = pending?.copy(checking = false, error = error.message ?: "verification_error")
                }
        }
    }

    LaunchedEffect(pending?.session?.orderToken) {
        val session = pending?.session ?: return@LaunchedEffect
        repeat(24) {
            delay(3500)
            val active = pending ?: return@LaunchedEffect
            if (active.session?.orderToken != session.orderToken) return@LaunchedEffect
            runCatching { payments.status(session.orderToken) }
                .onSuccess { status ->
                    if (status.paid && status.sku == active.sku) {
                        store.unlockSku(active.sku)
                        pending = null
                        return@LaunchedEffect
                    }
                    pending = pending?.copy(checking = true, error = null)
                }
        }
        pending = pending?.copy(checking = false, error = tr(language, "stillActive"))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(selectedSkin.accentA, selectedSkin.accentB)
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                unlocked = unlocked,
                selectedSkin = selectedSkin,
                language = language,
                stats = stats,
                bestScores = bestScores,
                onLanguage = { scope.launch { store.setLanguage(it) } },
                onPlay = { level ->
                    if (level.unlockSku == null || level.unlockSku in unlocked) {
                        screen = Screen.Game(level.id)
                    } else {
                        beginPurchase(level.unlockSku, level.name.resolve(language), level.priceSats)
                    }
                },
                onShop = { screen = Screen.Shop },
                onChallenges = { screen = Screen.Challenges }
            )

            is Screen.Game -> GameScreen(
                level = LEVELS.first { it.id == current.levelId },
                skin = selectedSkin,
                language = language,
                store = store,
                onBack = { screen = Screen.Home }
            )

            Screen.Shop -> ShopScreen(
                unlocked = unlocked,
                selectedSku = selectedSkinSku,
                language = language,
                onBack = { screen = Screen.Home },
                onSkin = { skin ->
                    when {
                        skin.priceSats == 0 || skin.sku in unlocked -> scope.launch { store.selectSkin(skin.sku) }
                        else -> beginPurchase(skin.sku, skin.name.resolve(language), skin.priceSats)
                    }
                }
            )

            Screen.Challenges -> ChallengesScreen(
                language = language,
                stats = stats,
                claimed = claimed,
                bestScores = bestScores,
                onBack = { screen = Screen.Home },
                onClaim = { challenge -> scope.launch { store.claimChallenge(challenge) } },
                a = selectedSkin.accentA,
                b = selectedSkin.accentB
            )
        }
    }

    pending?.let { purchase ->
        PaymentDialog(
            purchase = purchase,
            language = language,
            onVerify = { verifyPurchase() },
            onOpen = { purchase.session?.let { openUrl(context, it.checkoutUrl) } },
            onClose = { if (!purchase.loading) pending = null }
        )
    }
}

@Composable
private fun PaymentDialog(
    purchase: PendingPurchase,
    language: AppLanguage,
    onVerify: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!purchase.loading) onClose() },
        containerColor = Color(0xFF0E1328),
        title = { Text(tr(language, "paymentTitle"), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(purchase.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${purchase.priceSats} sats", color = Color(0xFFFFD54A), fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(tr(language, "paymentBody"), color = Color(0xFFCBD2EF))
                Text(tr(language, "autoVerify"), color = Color(0xFF7EE8D0), fontSize = 12.sp)
                if (purchase.loading || purchase.checking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (purchase.loading) tr(language, "creating") else tr(language, "checking"))
                    }
                }
                purchase.error?.let { Text(it, color = Color(0xFFFF7A9E), fontWeight = FontWeight.SemiBold) }
            }
        },
        confirmButton = {
            if (purchase.session != null) {
                Button(
                    onClick = onVerify,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20E3B2), contentColor = Color(0xFF04100D))
                ) { Text(tr(language, "verify"), fontWeight = FontWeight.Black) }
            }
        },
        dismissButton = {
            Row {
                if (purchase.session != null) TextButton(onClick = onOpen) { Text(tr(language, "openPayment")) }
                TextButton(onClick = onClose, enabled = !purchase.loading) { Text(tr(language, "close")) }
            }
        }
    )
}

@Composable
private fun HomeScreen(
    unlocked: Set<String>,
    selectedSkin: SkinSpec,
    language: AppLanguage,
    stats: PlayerStats,
    bestScores: Map<Int, Int>,
    onLanguage: (AppLanguage) -> Unit,
    onPlay: (LevelSpec) -> Unit,
    onShop: () -> Unit,
    onChallenges: () -> Unit
) {
    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr(language, "developer"), fontSize = 10.sp, letterSpacing = 2.4.sp, color = Color(0xFF8B94B8), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("SATS", fontSize = 14.sp, letterSpacing = 5.sp, color = selectedSkin.accentA, fontWeight = FontWeight.Bold)
                        Text("PULSE", fontSize = 48.sp, lineHeight = 45.sp, fontWeight = FontWeight.Black)
                        Text(tr(language, "tagline"), color = Color(0xFFB8BED8), fontSize = 13.sp)
                    }
                    CoreBadge(selectedSkin.accentA, selectedSkin.accentB, 94)
                }
            }
            item { LanguageBar(language, onLanguage, selectedSkin.accentA) }
            item { RankCard(stats, language, selectedSkin.accentA, selectedSkin.accentB) }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniAction(tr(language, "challenges"), selectedSkin.accentA, Modifier.weight(1f), onChallenges)
                    MiniAction(tr(language, "shop"), selectedSkin.accentB, Modifier.weight(1f), onShop)
                }
            }
            items(LEVELS) { level ->
                LevelCard(
                    level = level,
                    language = language,
                    best = bestScores[level.id] ?: 0,
                    unlocked = level.unlockSku == null || level.unlockSku in unlocked,
                    onPlay = onPlay
                )
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun LanguageBar(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr(language, "language"), fontSize = 11.sp, letterSpacing = 2.sp, color = Color(0xFF8992B6), fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        AppLanguage.entries.forEach { item ->
            val active = item == language
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(if (active) accent.copy(alpha = .18f) else Color(0x330E1328), RoundedCornerShape(12.dp))
                    .border(1.dp, if (active) accent else Color.White.copy(alpha = .12f), RoundedCornerShape(12.dp))
                    .clickable { onLanguage(item) }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) { Text(languageLabel(item), fontSize = 11.sp, fontWeight = FontWeight.Black, color = if (active) accent else Color.White) }
        }
    }
}

@Composable
private fun RankCard(stats: PlayerStats, language: AppLanguage, a: Color, b: Color) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(a.copy(alpha = .13f), b.copy(alpha = .10f))), shape)
            .border(1.dp, Color.White.copy(alpha = .10f), shape)
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${tr(language, "rank")} ${stats.rank}", fontSize = 17.sp, fontWeight = FontWeight.Black, color = a)
                Spacer(Modifier.weight(1f))
                Text("${stats.xp} XP", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD54A))
            }
            LinearProgressIndicator(
                progress = { stats.rankProgress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = a,
                trackColor = Color.White.copy(alpha = .08f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("RUNS ${stats.totalRuns}", fontSize = 11.sp, color = Color(0xFFABB3D2), fontWeight = FontWeight.Bold)
                Text("COMBO x${stats.bestCombo}", fontSize = 11.sp, color = Color(0xFFABB3D2), fontWeight = FontWeight.Bold)
                Text("PERF ${stats.perfectRuns}", fontSize = 11.sp, color = Color(0xFFABB3D2), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MiniAction(label: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(accent.copy(alpha = .12f), RoundedCornerShape(18.dp))
            .border(1.dp, accent.copy(alpha = .45f), RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun LevelCard(level: LevelSpec, language: AppLanguage, best: Int, unlocked: Boolean, onPlay: (LevelSpec) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0xCC10152D), Color(0xAA090C1A))), shape)
            .border(1.dp, Brush.linearGradient(listOf(level.accentA.copy(alpha = .8f), level.accentB.copy(alpha = .25f))), shape)
            .clickable { onPlay(level) }
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(58.dp).background(Brush.radialGradient(listOf(level.accentA, level.accentB.copy(alpha = .25f))), CircleShape),
                contentAlignment = Alignment.Center
            ) { Text(level.id.toString(), fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF050713)) }
            Spacer(Modifier.width(15.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(level.name.resolve(language), fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(level.subtitle.resolve(language), color = Color(0xFFABB3D2), fontSize = 13.sp)
                Spacer(Modifier.height(7.dp))
                Text("${tr(language, "goal")} ${level.targetScore} • ${level.seconds}s", fontSize = 11.sp, color = level.accentA)
                if (best > 0) Text("${tr(language, "best")} $best", fontSize = 11.sp, color = Color(0xFF8992B6))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (unlocked) tr(language, "play") else "🔒", fontWeight = FontWeight.Black, color = if (unlocked) level.accentA else Color.White)
                if (!unlocked) Text("${level.priceSats} sats", color = Color(0xFFFFD54A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ShopScreen(
    unlocked: Set<String>,
    selectedSku: String,
    language: AppLanguage,
    onBack: () -> Unit,
    onSkin: (SkinSpec) -> Unit
) {
    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { HeaderRow("SKIN LAB", tr(language, "skinIntro"), onBack) }
            items(SKINS) { skin ->
                val owned = skin.priceSats == 0 || skin.sku in unlocked
                val selected = skin.sku == selectedSku
                val shape = RoundedCornerShape(28.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC0D1227), shape)
                        .border(1.dp, Brush.linearGradient(listOf(skin.accentA, skin.accentB)), shape)
                        .clickable { onSkin(skin) }
                        .padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoreBadge(skin.accentA, skin.accentB, 72)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(skin.name.resolve(language), fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text(skin.description.resolve(language), color = Color(0xFFB8BED8), fontSize = 13.sp)
                        }
                        Text(
                            when {
                                selected -> tr(language, "active")
                                owned -> tr(language, "use")
                                else -> "${skin.priceSats} sats"
                            },
                            color = if (owned) skin.accentA else Color(0xFFFFD54A),
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ChallengesScreen(
    language: AppLanguage,
    stats: PlayerStats,
    claimed: Set<String>,
    bestScores: Map<Int, Int>,
    onBack: () -> Unit,
    onClaim: (ChallengeSpec) -> Unit,
    a: Color,
    b: Color
) {
    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { HeaderRow(tr(language, "challenges"), tr(language, "challengeIntro"), onBack) }
            item { RankCard(stats, language, a, b) }
            items(CHALLENGES) { challenge ->
                val progress = challengeProgress(challenge, stats, bestScores)
                val complete = challengeCompleted(challenge, stats, bestScores)
                val isClaimed = challenge.id in claimed
                val ratio = (progress.toFloat() / challenge.target.toFloat()).coerceIn(0f, 1f)
                val shape = RoundedCornerShape(24.dp)
                Box(
                    modifier = Modifier.fillMaxWidth().background(Color(0xCC0D1227), shape)
                        .border(1.dp, if (complete) a.copy(alpha = .7f) else Color.White.copy(alpha = .10f), shape)
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(challenge.title.resolve(language), fontSize = 18.sp, fontWeight = FontWeight.Black)
                                Text(challenge.description.resolve(language), color = Color(0xFFB8BED8), fontSize = 13.sp)
                            }
                            Text("+${challenge.xpReward} XP", color = Color(0xFFFFD54A), fontWeight = FontWeight.Black)
                        }
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = if (complete) a else b,
                            trackColor = Color.White.copy(alpha = .07f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${tr(language, "progress")}: $progress/${challenge.target}", color = Color(0xFF8992B6), fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            when {
                                isClaimed -> Text(tr(language, "claimed"), color = a, fontWeight = FontWeight.Black, fontSize = 12.sp)
                                complete -> Text(
                                    tr(language, "claim"),
                                    color = Color(0xFFFFD54A),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { onClaim(challenge) }.padding(6.dp)
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HeaderRow(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹", fontSize = 42.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
        Column {
            Text(title, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = Color(0xFFB8BED8), fontSize = 13.sp)
        }
    }
}

private data class Burst(val p: Offset, val bornNs: Long, val strength: Float)

@Composable
private fun GameScreen(level: LevelSpec, skin: SkinSpec, language: AppLanguage, store: GameStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
    DisposableEffect(Unit) { onDispose { tone.release() } }

    var score by remember(level.id) { mutableIntStateOf(0) }
    var combo by remember(level.id) { mutableIntStateOf(0) }
    var peakCombo by remember(level.id) { mutableIntStateOf(0) }
    var misses by remember(level.id) { mutableIntStateOf(0) }
    var target by remember(level.id) { mutableStateOf(Offset(.5f, .48f)) }
    var frameNs by remember { mutableLongStateOf(System.nanoTime()) }
    var runId by remember(level.id) { mutableIntStateOf(0) }
    var startNs by remember(level.id) { mutableLongStateOf(System.nanoTime()) }
    var finished by remember(level.id) { mutableStateOf(false) }
    var resultRecorded by remember(level.id, runId) { mutableStateOf(false) }
    var feedback by remember(level.id, runId) { mutableStateOf("") }
    var feedbackNs by remember(level.id, runId) { mutableLongStateOf(0L) }
    val bursts = remember(level.id, runId) { mutableStateListOf<Burst>() }
    val bestFlow = remember(level.id) { store.bestScore(level.id) }
    val bestObserved by bestFlow.collectAsStateWithLifecycleCompat(0)

    LaunchedEffect(level.id, runId) {
        startNs = System.nanoTime()
        finished = false
        resultRecorded = false
        while (!finished) {
            androidx.compose.runtime.withFrameNanos { frameNs = it }
            val elapsed = (frameNs - startNs).coerceAtLeast(0L) / 1_000_000_000.0
            if (elapsed >= level.seconds) finished = true
        }
    }

    LaunchedEffect(finished, runId) {
        if (finished && !resultRecorded) {
            resultRecorded = true
            store.recordRun(level.id, score, peakCombo, misses)
            if (score >= level.targetScore) {
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
                vibrate(vibrator, 55)
            }
        }
    }

    val elapsedSec = ((frameNs - startNs).coerceAtLeast(0L) / 1_000_000_000.0)
    val remaining = (level.seconds - elapsedSec).coerceAtLeast(0.0)
    val activeA = if (skin.sku == "skin_default") level.accentA else skin.accentA
    val activeB = if (skin.sku == "skin_default") level.accentB else skin.accentB
    val fever = combo >= 8

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(level.id, finished, target, combo) {
                    detectTapGestures { tap ->
                        if (finished) return@detectTapGestures
                        val normalizedTap = Offset(tap.x / size.width, tap.y / size.height)
                        val live = movingTarget(target, frameNs, level.driftSpeed)
                        val dx = (normalizedTap.x - live.x) * size.width
                        val dy = (normalizedTap.y - live.y) * size.height
                        val distancePx = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val hitRadiusPx = level.targetRadius * density
                        val hit = distancePx <= hitRadiusPx
                        if (hit) {
                            combo += 1
                            peakCombo = maxOf(peakCombo, combo)
                            val precision = (1f - distancePx / hitRadiusPx).coerceIn(0f, 1f)
                            val base = when {
                                precision > .72f -> 120
                                precision > .42f -> 85
                                else -> 55
                            }
                            val feverMultiplier = if (combo >= 8) 2 else 1
                            val add = (base + combo.coerceAtMost(15) * 10) * feverMultiplier
                            score += add
                            feedback = when {
                                precision > .72f -> tr(language, "perfect")
                                precision > .42f -> tr(language, "great")
                                else -> tr(language, "good")
                            } + " +$add"
                            feedbackNs = frameNs
                            bursts.removeAll { frameNs - it.bornNs > 750_000_000L }
                            bursts.add(Burst(live, frameNs, 1f + combo.coerceAtMost(12) / 10f))
                            target = nextTarget(score + combo * 17 + level.id * 41)
                            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 28)
                            vibrate(vibrator, (10 + combo.coerceAtMost(8) * 2).toLong())
                        } else {
                            combo = 0
                            misses += 1
                            feedback = tr(language, "miss")
                            feedbackNs = frameNs
                            tone.startTone(ToneGenerator.TONE_PROP_NACK, 45)
                            vibrate(vibrator, 18)
                        }
                    }
                }
        ) {
            drawRect(Color(0xFF050713))
            drawStars(frameNs, activeA, activeB)
            drawGrid(frameNs, activeA, fever)

            val progress = (remaining / level.seconds).toFloat().coerceIn(0f, 1f)
            drawRoundRect(
                color = Color.White.copy(alpha = .10f),
                topLeft = Offset(24f, 42f),
                size = Size(size.width - 48f, 12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f)
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(activeA, if (fever) Color(0xFFFFD54A) else activeB)),
                topLeft = Offset(24f, 42f),
                size = Size((size.width - 48f) * progress, 12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f)
            )

            val live = movingTarget(target, frameNs, level.driftSpeed)
            val center = Offset(live.x * size.width, live.y * size.height)
            val pulse = ((sin(frameNs / 160_000_000.0) + 1.0) / 2.0).toFloat()
            val r = level.targetRadius * density
            val ringColor = if (fever) Color(0xFFFFD54A) else activeA
            drawCircle(activeB.copy(alpha = .08f), r * (2.5f + pulse * .28f), center)
            drawCircle(ringColor.copy(alpha = .14f), r * (1.85f + pulse * .20f), center)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, ringColor, activeB.copy(alpha = .40f)), center, r),
                radius = r,
                center = center
            )
            drawCircle(Color.White.copy(alpha = .88f), r * .20f, center)
            drawCircle(ringColor, r * (1.20f + pulse * .10f), center, style = Stroke(width = 3f))
            drawCircle(activeB.copy(alpha = .65f), r * (1.54f + pulse * .18f), center, style = Stroke(width = 2f))

            val now = frameNs
            bursts.forEach { burst ->
                val age = ((now - burst.bornNs) / 750_000_000f).coerceIn(0f, 1f)
                val c = Offset(burst.p.x * size.width, burst.p.y * size.height)
                val radius = r * (1.1f + age * 4.7f * burst.strength)
                drawCircle(ringColor.copy(alpha = (1f - age) * .72f), radius, c, style = Stroke(width = (7f * (1f - age)).coerceAtLeast(1f)))
                repeat(10) { i ->
                    val angle = i / 10f * (PI * 2).toFloat() + age * 1.6f
                    val dist = radius * .74f
                    val p = Offset(c.x + cos(angle) * dist, c.y + sin(angle) * dist)
                    drawCircle(activeB.copy(alpha = (1f - age) * .82f), 5f * (1f - age) + 1f, p)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 42.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", fontSize = 42.sp, modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Column {
                Text(level.name.resolve(language).uppercase(), fontSize = 11.sp, letterSpacing = 2.sp, color = activeA, fontWeight = FontWeight.Bold)
                Text("${remaining.toInt() + if (remaining > 0) 1 else 0}s", fontSize = 26.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(score.toString(), fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text("x$combo ${tr(language, "combo")}", color = if (fever) Color(0xFFFFD54A) else Color(0xFFABB3D2), fontWeight = FontWeight.Bold)
            }
        }

        AnimatedVisibility(fever && !finished, modifier = Modifier.align(Alignment.TopCenter).padding(top = 112.dp)) {
            Box(
                modifier = Modifier.background(Color(0x33FFD54A), RoundedCornerShape(20.dp)).border(1.dp, Color(0x99FFD54A), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text("⚡ ${tr(language, "fever")} x2", color = Color(0xFFFFD54A), fontWeight = FontWeight.Black, letterSpacing = 2.sp) }
        }

        AnimatedVisibility(feedback.isNotBlank() && frameNs - feedbackNs < 650_000_000L && !finished, modifier = Modifier.align(Alignment.Center)) {
            Text(feedback, color = Color.White.copy(alpha = .82f), fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }

        if (finished) {
            ResultOverlay(
                language = language,
                score = score,
                target = level.targetScore,
                best = maxOf(bestObserved, score),
                misses = misses,
                peakCombo = peakCombo,
                a = activeA,
                b = activeB,
                onRetry = {
                    score = 0
                    combo = 0
                    peakCombo = 0
                    misses = 0
                    target = Offset(.5f, .48f)
                    bursts.clear()
                    feedback = ""
                    frameNs = System.nanoTime()
                    runId += 1
                },
                onBack = onBack
            )
        } else {
            Text(
                tr(language, "tapCore"),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp),
                color = Color.White.copy(alpha = .42f),
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ResultOverlay(
    language: AppLanguage,
    score: Int,
    target: Int,
    best: Int,
    misses: Int,
    peakCombo: Int,
    a: Color,
    b: Color,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    val passed = score >= target
    val stars = when {
        !passed -> 0
        score >= (target * 1.55f).toInt() && misses <= 1 -> 3
        score >= (target * 1.20f).toInt() -> 2
        else -> 1
    }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xD9050713)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoreBadge(a, b, 110)
            Text(if (passed) tr(language, "resultsWin") else tr(language, "resultsLose"), fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text(score.toString(), fontSize = 64.sp, fontWeight = FontWeight.Black, color = a)
            Text("${"★".repeat(stars)}${"☆".repeat(3 - stars)}", color = Color(0xFFFFD54A), fontSize = 28.sp, letterSpacing = 4.sp)
            Text("${tr(language, "goal")} $target • ${tr(language, "best")} $best", color = Color(0xFFB8BED8))
            Text("${tr(language, "misses")}: $misses • Combo x$peakCombo", color = Color(0xFF8992B6), fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            NeonButton(if (passed) tr(language, "back") else tr(language, "retry"), a, b, if (passed) onBack else onRetry)
            if (!passed) TextButton(onClick = onBack) { Text(tr(language, "exit")) }
        }
    }
}

@Composable
private fun NeonButton(label: String, a: Color, b: Color, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "button")
    val glow by transition.animateFloat(
        initialValue = .35f,
        targetValue = .8f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(a.copy(alpha = glow), b.copy(alpha = glow))), RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(22.dp))
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
}

@Composable
private fun CoreBadge(a: Color, b: Color, sizeDp: Int = 88) {
    val transition = rememberInfiniteTransition(label = "core")
    val pulse by transition.animateFloat(0.82f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(modifier = Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size((sizeDp * pulse).dp).background(Brush.radialGradient(listOf(a.copy(alpha = .45f), Color.Transparent)), CircleShape))
        Box(modifier = Modifier.size((sizeDp * .67f).dp).background(Brush.radialGradient(listOf(Color.White, a, b)), CircleShape))
        Text("ϟ", color = Color(0xFF050713), fontSize = (sizeDp * .34f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun AmbientBackground(a: Color, b: Color) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val shift by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(7000), RepeatMode.Reverse), label = "shift")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFF050713))
        drawCircle(a.copy(alpha = .11f), size.maxDimension * .55f, Offset(size.width * (.08f + shift * .14f), size.height * .18f))
        drawCircle(b.copy(alpha = .10f), size.maxDimension * .50f, Offset(size.width * (.92f - shift * .10f), size.height * .78f))
        repeat(7) { i ->
            val y = size.height * (i / 7f)
            drawLine(Color.White.copy(alpha = .018f), Offset(0f, y), Offset(size.width, y), 1f)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStars(frameNs: Long, a: Color, b: Color) {
    val t = frameNs / 1_000_000_000f
    repeat(54) { i ->
        val x = ((i * 73) % 101) / 101f * size.width
        val baseY = ((i * 47) % 97) / 97f * size.height
        val y = (baseY + (t * (4f + i % 5) * density)) % size.height
        val alpha = .07f + ((i % 7) / 7f) * .22f
        drawCircle(if (i % 2 == 0) a.copy(alpha = alpha) else b.copy(alpha = alpha), 1.2f + (i % 3), Offset(x, y))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(frameNs: Long, accent: Color, fever: Boolean) {
    val drift = ((frameNs / 20_000_000L) % 80).toFloat()
    val alpha = if (fever) .075f else .035f
    var y = -80f + drift
    while (y < size.height + 80f) {
        drawLine(accent.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y + 24f), 1f)
        y += 80f
    }
    repeat(7) { i ->
        val x = size.width * (i / 6f)
        drawLine(accent.copy(alpha = alpha * .7f), Offset(x, 0f), Offset(x, size.height), 1f)
    }
}

private fun movingTarget(base: Offset, frameNs: Long, speed: Float): Offset {
    val t = frameNs / 1_000_000_000.0
    val ox = sin(t * (1.35 + speed) * 1.7) * .035 * speed
    val oy = cos(t * (1.10 + speed) * 1.9) * .028 * speed
    return Offset((base.x + ox).toFloat().coerceIn(.11f, .89f), (base.y + oy).toFloat().coerceIn(.18f, .86f))
}

private fun nextTarget(seed: Int): Offset {
    val r = Random(seed * 1103515245 + 12345)
    return Offset(r.nextFloat() * .70f + .15f, r.nextFloat() * .58f + .24f)
}

private fun vibrate(vibrator: Vibrator, ms: Long) {
    runCatching { vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
}

private fun getInstallId(context: Context): String {
    val prefs = context.getSharedPreferences("install", Context.MODE_PRIVATE)
    val existing = prefs.getString("id", null)
    if (existing != null) return existing
    return java.util.UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
}

private fun openUrl(context: Context, raw: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateWithLifecycleCompat(initial: T): androidx.compose.runtime.State<T> =
    this.collectAsState(initial = initial)
