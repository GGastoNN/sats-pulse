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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
import androidx.datastore.preferences.core.edit
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
    val selectedSkin = SKINS.firstOrNull { it.sku == selectedSkinSku } ?: SKINS.first()

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var pending by remember { mutableStateOf<PendingPurchase?>(null) }
    val installId = remember { getInstallId(context) }

    fun beginPurchase(sku: String, label: String, price: Int) {
        pending = PendingPurchase(sku, label, price, loading = true)
        scope.launch {
            runCatching { payments.createCheckout(sku, installId) }
                .onSuccess { session ->
                    pending = pending?.copy(session = session, priceSats = session.priceSats, loading = false, error = null)
                    openUrl(context, session.checkoutUrl)
                }
                .onFailure { error ->
                    pending = pending?.copy(loading = false, error = error.message ?: "No se pudo iniciar el pago")
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
                                "active" -> "Todavía no figura como pagado."
                                "verification_unavailable" -> "Este método de cobro no permite confirmar automáticamente el pago."
                                else -> "Estado: ${status.status}"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    pending = pending?.copy(checking = false, error = error.message ?: "No se pudo verificar")
                }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(selectedSkin.accentA, selectedSkin.accentB)
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                unlocked = unlocked,
                selectedSkin = selectedSkin,
                onPlay = { level ->
                    if (level.unlockSku == null || level.unlockSku in unlocked) {
                        screen = Screen.Game(level.id)
                    } else {
                        beginPurchase(level.unlockSku, level.name, level.priceSats)
                    }
                },
                onShop = { screen = Screen.Shop }
            )

            is Screen.Game -> GameScreen(
                level = LEVELS.first { it.id == current.levelId },
                skin = selectedSkin,
                store = store,
                onBack = { screen = Screen.Home }
            )

            Screen.Shop -> ShopScreen(
                unlocked = unlocked,
                selectedSku = selectedSkinSku,
                onBack = { screen = Screen.Home },
                onSkin = { skin ->
                    when {
                        skin.priceSats == 0 || skin.sku in unlocked -> scope.launch { store.selectSkin(skin.sku) }
                        else -> beginPurchase(skin.sku, skin.name, skin.priceSats)
                    }
                }
            )
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { if (!p.loading && !p.checking) pending = null },
            containerColor = Color(0xFF0E1328),
            title = { Text("Desbloqueo Lightning", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(p.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${p.priceSats} sats", color = Color(0xFFFFD54A), fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("El pago se procesa fuera del APK y el contenido se habilita cuando el servidor confirma el cobro.")
                    if (p.loading || p.checking) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(if (p.loading) "Creando pago…" else "Verificando…")
                        }
                    }
                    p.error?.let { Text(it, color = Color(0xFFFF7A9E), fontWeight = FontWeight.SemiBold) }
                }
            },
            confirmButton = {
                if (p.session != null) {
                    Button(
                        onClick = { verifyPurchase() },
                        enabled = !p.checking,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20E3B2), contentColor = Color(0xFF04100D))
                    ) { Text("Verificar pago", fontWeight = FontWeight.Black) }
                }
            },
            dismissButton = {
                Row {
                    if (p.session != null) {
                        TextButton(onClick = { openUrl(context, p.session.checkoutUrl) }) { Text("Abrir pago") }
                    }
                    TextButton(onClick = { pending = null }, enabled = !p.loading && !p.checking) { Text("Cerrar") }
                }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    unlocked: Set<String>,
    selectedSkin: SkinSpec,
    onPlay: (LevelSpec) -> Unit,
    onShop: () -> Unit
) {
    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SATS", fontSize = 14.sp, letterSpacing = 5.sp, color = selectedSkin.accentA, fontWeight = FontWeight.Bold)
                        Text("PULSE", fontSize = 46.sp, lineHeight = 44.sp, fontWeight = FontWeight.Black)
                        Text("Golpeá el núcleo. Encadená combos. Sentí cada impacto.", color = Color(0xFFB8BED8))
                    }
                    CoreBadge(selectedSkin.accentA, selectedSkin.accentB)
                }
            }
            item {
                NeonButton("TIENDA DE SKINS", selectedSkin.accentA, selectedSkin.accentB, onShop)
            }
            items(LEVELS) { level ->
                val isUnlocked = level.unlockSku == null || level.unlockSku in unlocked
                LevelCard(level, isUnlocked, onPlay)
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun LevelCard(level: LevelSpec, unlocked: Boolean, onPlay: (LevelSpec) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xCC10152D), Color(0xAA090C1A))),
                shape
            )
            .border(1.dp, Brush.linearGradient(listOf(level.accentA.copy(alpha = .8f), level.accentB.copy(alpha = .25f))), shape)
            .clickable { onPlay(level) }
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(Brush.radialGradient(listOf(level.accentA, level.accentB.copy(alpha = .25f))), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(level.id.toString(), fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF050713))
            }
            Spacer(Modifier.width(15.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(level.name, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(level.subtitle, color = Color(0xFFABB3D2), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("Meta ${level.targetScore} pts • ${level.seconds}s", fontSize = 12.sp, color = level.accentA)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (unlocked) "JUGAR" else "🔒", fontWeight = FontWeight.Black, color = if (unlocked) level.accentA else Color.White)
                if (!unlocked) Text("${level.priceSats} sats", color = Color(0xFFFFD54A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ShopScreen(
    unlocked: Set<String>,
    selectedSku: String,
    onBack: () -> Unit,
    onSkin: (SkinSpec) -> Unit
) {
    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", fontSize = 42.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
                    Column {
                        Text("SKIN LAB", fontSize = 34.sp, fontWeight = FontWeight.Black)
                        Text("Cambiá el pulso visual de todo el juego", color = Color(0xFFB8BED8))
                    }
                }
            }
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
                            Text(skin.name, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text(skin.description, color = Color(0xFFB8BED8))
                        }
                        Text(
                            when {
                                selected -> "ACTIVA"
                                owned -> "USAR"
                                else -> "${skin.priceSats} sats"
                            },
                            color = if (owned) skin.accentA else Color(0xFFFFD54A),
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

private data class Burst(val p: Offset, val bornNs: Long, val strength: Float)

@Composable
private fun GameScreen(level: LevelSpec, skin: SkinSpec, store: GameStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
    DisposableEffect(Unit) { onDispose { tone.release() } }

    var score by remember(level.id) { mutableIntStateOf(0) }
    var combo by remember(level.id) { mutableIntStateOf(0) }
    var misses by remember(level.id) { mutableIntStateOf(0) }
    var target by remember(level.id) { mutableStateOf(Offset(.5f, .48f)) }
    var frameNs by remember { mutableLongStateOf(System.nanoTime()) }
    var runId by remember(level.id) { mutableIntStateOf(0) }
    var startNs by remember(level.id) { mutableLongStateOf(System.nanoTime()) }
    var finished by remember(level.id) { mutableStateOf(false) }
    val bursts = remember(level.id) { mutableStateListOf<Burst>() }
    val bestFlow = remember(level.id) { store.bestScore(level.id) }
    val bestObserved by bestFlow.collectAsStateWithLifecycleCompat(0)

    LaunchedEffect(level.id, runId) {
        startNs = System.nanoTime()
        finished = false
        while (!finished) {
            androidx.compose.runtime.withFrameNanos { frameNs = it }
            val elapsed = (frameNs - startNs).coerceAtLeast(0L) / 1_000_000_000.0
            if (elapsed >= level.seconds) {
                finished = true
                store.saveBest(level.id, score)
                if (score >= level.targetScore) {
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
                    vibrate(vibrator, 55)
                }
            }
        }
    }

    val elapsedSec = ((frameNs - startNs).coerceAtLeast(0L) / 1_000_000_000.0)
    val remaining = (level.seconds - elapsedSec).coerceAtLeast(0.0)

    val activeA = if (skin.sku == "skin_default") level.accentA else skin.accentA
    val activeB = if (skin.sku == "skin_default") level.accentB else skin.accentB

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(level.id, finished, target) {
                    detectTapGestures { tap ->
                        if (finished) return@detectTapGestures
                        val normalizedTap = Offset(tap.x / size.width, tap.y / size.height)
                        val live = movingTarget(target, frameNs, level.driftSpeed)
                        val dx = (normalizedTap.x - live.x) * size.width
                        val dy = (normalizedTap.y - live.y) * size.height
                        val hitRadiusPx = level.targetRadius * density
                        val hit = hypot(dx.toDouble(), dy.toDouble()) <= hitRadiusPx
                        if (hit) {
                            combo += 1
                            val add = 70 + (combo.coerceAtMost(12) * 12)
                            score += add
                            bursts.removeAll { frameNs - it.bornNs > 700_000_000L }
                            bursts.add(Burst(live, frameNs, 1f + combo.coerceAtMost(10) / 10f))
                            target = nextTarget(score + combo * 17)
                            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 28)
                            vibrate(vibrator, (10 + combo.coerceAtMost(6) * 2).toLong())
                        } else {
                            misses += 1
                            combo = 0
                            vibrate(vibrator, 7)
                        }
                    }
                }
        ) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF050713), Color(0xFF080B1B), Color(0xFF050713))))
            drawStars(frameNs, activeA, activeB)

            val progress = (remaining / level.seconds).toFloat().coerceIn(0f, 1f)
            drawRoundRect(
                color = Color.White.copy(alpha = .10f),
                topLeft = Offset(24f, 42f),
                size = Size(size.width - 48f, 12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f)
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(activeA, activeB)),
                topLeft = Offset(24f, 42f),
                size = Size((size.width - 48f) * progress, 12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f)
            )

            val live = movingTarget(target, frameNs, level.driftSpeed)
            val center = Offset(live.x * size.width, live.y * size.height)
            val pulse = ((sin(frameNs / 160_000_000.0) + 1.0) / 2.0).toFloat()
            val r = level.targetRadius * density
            drawCircle(activeB.copy(alpha = .08f), r * (2.4f + pulse * .25f), center)
            drawCircle(activeA.copy(alpha = .12f), r * (1.8f + pulse * .18f), center)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, activeA, activeB.copy(alpha = .35f)), center, r),
                radius = r,
                center = center
            )
            drawCircle(Color.White.copy(alpha = .8f), r * .22f, center)
            drawCircle(activeA, r * (1.20f + pulse * .10f), center, style = Stroke(width = 3f))
            drawCircle(activeB.copy(alpha = .6f), r * (1.52f + pulse * .18f), center, style = Stroke(width = 2f))

            val now = frameNs
            bursts.forEach { burst ->
                val age = ((now - burst.bornNs) / 700_000_000f).coerceIn(0f, 1f)
                val c = Offset(burst.p.x * size.width, burst.p.y * size.height)
                val radius = r * (1.1f + age * 4.4f * burst.strength)
                drawCircle(activeA.copy(alpha = (1f - age) * .7f), radius, c, style = Stroke(width = (7f * (1f - age)).coerceAtLeast(1f)))
                repeat(8) { i ->
                    val angle = i / 8f * (PI * 2).toFloat() + age * 1.4f
                    val dist = radius * .72f
                    val p = Offset(c.x + cos(angle) * dist, c.y + sin(angle) * dist)
                    drawCircle(activeB.copy(alpha = (1f - age) * .8f), 5f * (1f - age) + 1f, p)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 42.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", fontSize = 42.sp, modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Column {
                Text(level.name.uppercase(), fontSize = 12.sp, letterSpacing = 2.sp, color = activeA, fontWeight = FontWeight.Bold)
                Text("${remaining.toInt() + if (remaining > 0) 1 else 0}s", fontSize = 26.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(score.toString(), fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text("x$combo combo", color = if (combo >= 5) Color(0xFFFFD54A) else Color(0xFFABB3D2), fontWeight = FontWeight.Bold)
            }
        }

        AnimatedVisibility(combo >= 4 && !finished, modifier = Modifier.align(Alignment.Center)) {
            Text(
                if (combo >= 10) "⚡ ULTRA x$combo" else "NICE x$combo",
                color = Color.White.copy(alpha = .65f),
                fontSize = if (combo >= 10) 25.sp else 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        }

        if (finished) {
            ResultOverlay(
                score = score,
                target = level.targetScore,
                best = maxOf(bestObserved, score),
                misses = misses,
                a = activeA,
                b = activeB,
                onRetry = {
                    score = 0
                    combo = 0
                    misses = 0
                    target = Offset(.5f, .48f)
                    bursts.clear()
                    frameNs = System.nanoTime()
                    runId += 1
                },
                onBack = onBack
            )
        } else {
            Text(
                "TAP EN EL NÚCLEO",
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
    score: Int,
    target: Int,
    best: Int,
    misses: Int,
    a: Color,
    b: Color,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    val passed = score >= target
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD9050713)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoreBadge(a, b, 110)
            Text(if (passed) "PULSO PERFECTO" else "CASI", fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(score.toString(), fontSize = 64.sp, fontWeight = FontWeight.Black, color = a)
            Text("Meta $target • Récord $best • Fallos $misses", color = Color(0xFFB8BED8))
            Spacer(Modifier.height(6.dp))
            NeonButton(if (passed) "VOLVER A NIVELES" else "REINTENTAR", a, b, if (passed) onBack else onRetry)
            if (!passed) TextButton(onClick = onBack) { Text("Salir") }
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
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun CoreBadge(a: Color, b: Color, sizeDp: Int = 88) {
    val transition = rememberInfiniteTransition(label = "core")
    val pulse by transition.animateFloat(
        0.82f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
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
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStars(frameNs: Long, a: Color, b: Color) {
    val t = frameNs / 1_000_000_000f
    repeat(44) { i ->
        val x = ((i * 73) % 101) / 101f * size.width
        val baseY = ((i * 47) % 97) / 97f * size.height
        val y = (baseY + (t * (4f + i % 5) * density)) % size.height
        val alpha = .08f + ((i % 7) / 7f) * .22f
        drawCircle(if (i % 2 == 0) a.copy(alpha = alpha) else b.copy(alpha = alpha), 1.2f + (i % 3), Offset(x, y))
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
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateWithLifecycleCompat(initial: T): androidx.compose.runtime.State<T> {
    // lifecycle-runtime-compose is intentionally avoided to keep dependencies lean.
    return this.collectAsState(initial = initial)
}
