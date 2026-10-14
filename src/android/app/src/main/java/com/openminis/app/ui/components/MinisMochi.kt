package com.openminis.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Minis mochi (docs/design/companion/index.html), drawn in Compose: an
 * outline-free soft-vinyl ball with round ears, bean eyes and a thin smile,
 * shaped by light alone. At rest it breathes, blinks and twitches its ears.
 * Each time [cheer] changes it does the prototype's "done": jumps (squashing
 * as it lands), throws both hands up and waves them, ^ ^ eyes and an open
 * smile, the star on its head spins and glows, and stars burst out from
 * behind it — for
 * about 2.6 s, then back to rest.
 *
 * Coordinates follow the prototype's 240 × 240 viewBox, scaled to fit.
 */
@Composable
fun MinisMochi(cheer: Int, modifier: Modifier = Modifier, skin: MochiSkin = MochiSkin.Mint) {
    var now by remember { mutableFloatStateOf(0f) }
    var cheerAt by remember { mutableFloatStateOf(-10f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now = (it - start) / 1e9f }
    }
    LaunchedEffect(cheer) { if (cheer > 0) cheerAt = now }
    Canvas(modifier) {
        val k = min(size.width, size.height) / 240f
        translate((size.width - 240f * k) / 2, (size.height - 240f * k) / 2) {
            scale(k, k, Offset.Zero) { drawMochi(skin, now, now - cheerAt) }
        }
    }
}

/** Colours of one skin, as in the prototype's CSS variables. */
data class MochiSkin(
    val hi: Color, val bodyTop: Color, val bodyBot: Color, val shade: Color,
    val accent: Color, val accent2: Color, val cheek: Color, val ink: Color,
) {
    companion object {
        val Milk = MochiSkin(Color.White, Color(0xFFF8FAFC), Color(0xFFCFD9E3), Color(0xFF5F7A92), Color(0xFF2B98D6), Color(0xFF9ED5F1), Color(0xFFFFB0BF), Color(0xFF22262D))
        val Mint = MochiSkin(Color.White, Color(0xFFF5FCF8), Color(0xFFC5E6D7), Color(0xFF4F8574), Color(0xFF1FA57A), Color(0xFFA3E6CC), Color(0xFFFFB0B8), Color(0xFF1F2B27))
    }
}

private const val CHEER_S = 2.6f
private const val HOP_V = 230f
private const val GRAVITY = 1100f
private val HOP_T = 2 * HOP_V / GRAVITY

private fun smooth(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** [t] seconds since start; [c] seconds since the last cheer (negative or large = not cheering). */
private fun DrawScope.drawMochi(s: MochiSkin, t: Float, c: Float) {
    val cheering = c in 0f..CHEER_S
    // How far into the cheer pose: in fast, out over the last 0.35 s.
    val pose = if (cheering) smooth(0f, 0.15f, c) * (1 - smooth(CHEER_S - 0.35f, CHEER_S, c)) else 0f

    // Body: breath + a closed-form hop + squash on landing.
    val breath = sin(t / 3.2f * 2 * PI.toFloat()) * 0.025f
    var hopY = 0f
    var hopV = 0f
    var squash = 0f
    if (cheering && c < HOP_T) {
        hopY = -HOP_V * c + GRAVITY * c * c / 2
        hopV = -HOP_V + GRAVITY * c
    } else if (cheering) {
        squash = 0.16f * exp(-12f * (c - HOP_T))
    }
    val stretch = max(-0.06f, min(0.1f, -hopV / 2400f))
    val sy = 1 + breath - squash + stretch
    val sx = 1 - breath * 0.5f + squash * 0.8f - stretch * 0.6f

    // Ground shadow, shrinking as it leaves the ground.
    val shadowScale = 1 + hopY / 120f
    drawOval(
        Brush.radialGradient(listOf(s.shade.copy(alpha = 0.28f), s.shade.copy(alpha = 0f)), Offset(120f, 211f), 64f * shadowScale),
        Offset(120f - 66f * shadowScale, 204f), Size(132f * shadowScale, 15f),
    )

    // Stars thrown out at the start of the cheer, from behind the body.
    if (cheering && c < 1f) {
        val k = 1 - c
        for (i in 0 until 12) {
            val a = i / 12f * 2 * PI.toFloat()
            val sp = 90f + ((i * 37) % 60)
            val r = 70f + sp * c
            val x = 120f + cos(a) * r
            val y = 135f + sin(a) * r - 40f * c + 20f * c * c
            val sc = (1f + ((i * 53) % 100) / 100f) * (0.6f + 0.4f * k)
            translate(x, y) { scale(sc, sc, Offset.Zero) { drawPath(STAR6, s.accent.copy(alpha = min(1f, k * 1.5f))) } }
        }
    }

    withTransform({
        translate(0f, hopY)
        scale(sx, sy, Offset(120f, 205f))
    }) {
        // Ears, a little wiggle at rest and a happy flutter in the cheer.
        val ear = sin(t * 1.3f) * 2 + pose * sin(t * 18f) * 6
        rotate(ear, Offset(86f, 95f)) { softBall(s, Offset(78f, 83f), 17.5f, 17.5f) }
        rotate(-ear, Offset(154f, 95f)) { softBall(s, Offset(162f, 83f), 17.5f, 17.5f) }

        val wave = sin(t * 10f) * 12f
        rotate(pose * (110f + wave), Offset(44f, 162f)) { softBall(s, Offset(38f, 172f), 11f, 14f) }

        // The body, lit from the top left; no outline.
        val body = BODY
        drawPath(body, Brush.radialGradient(0f to s.hi, 0.42f to s.bodyTop, 1f to s.bodyBot, center = Offset(96f, 103f), radius = 150f))
        drawPath(body, Brush.radialGradient(listOf(s.shade.copy(alpha = 0.38f), s.shade.copy(alpha = 0f)), Offset(144f, 201f), 120f))
        drawPath(body, Color.White.copy(alpha = 0.3f), style = Stroke(3f))
        // The big soft highlight, upper left.
        rotate(-30f, Offset(88f, 98f)) {
            drawOval(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0f)), Offset(88f, 98f), 32f),
                Offset(56f, 82f), Size(64f, 32f),
            )
        }

        // The brand star above the head: only in the cheer, spinning and glowing.
        if (pose > 0f) {
            val sc = pose
            translate(56f, 58f + sin(t * 2.4f) * 3) {
                drawCircle(Brush.radialGradient(listOf(s.accent.copy(alpha = 0.6f * sc), s.accent.copy(alpha = 0f)), Offset.Zero, 15f * sc), 15f * sc, Offset.Zero)
                rotate(c * 360f, Offset.Zero) { scale(sc, sc, Offset.Zero) { drawPath(STAR9, s.accent) } }
            }
        }

        translate(0f, 3f) { face(s, t, pose) }

        rotate(-pose * (110f + wave), Offset(196f, 162f)) { softBall(s, Offset(202f, 172f), 11f, 14f) }
    }

}

private fun DrawScope.face(s: MochiSkin, t: Float, pose: Float) {
    // Cheeks: a soft blush, warmer when happy.
    val blush = 0.45f + 0.45f * pose
    for (cx in floatArrayOf(74f, 166f)) {
        drawOval(
            Brush.radialGradient(listOf(s.cheek.copy(alpha = blush), s.cheek.copy(alpha = 0f)), Offset(cx, 165f), 13f),
            Offset(cx - 13f, 158f), Size(26f, 14f),
        )
    }
    if (pose > 0.5f) {
        // ^ ^
        for (cx in floatArrayOf(92f, 148f)) {
            drawPath(Path().apply { moveTo(cx - 7, 153f); quadraticTo(cx, 143f, cx + 7, 153f) }, s.ink, style = Stroke(3.6f, cap = StrokeCap.Round))
        }
    } else {
        // Bean eyes with a blink every few seconds.
        val phase = (t % 4.3f) / 0.16f
        val lid = if (phase < 1f) (if (phase < 0.5f) 1 - phase * 1.8f else 0.1f + (phase - 0.5f) * 1.8f) else 1f
        for (cx in floatArrayOf(92f, 148f)) {
            scale(1f, max(0.08f, lid), Offset(cx, 150f)) {
                drawOval(s.ink, Offset(cx - 7, 140.5f), Size(14f, 19f))
                drawOval(Color.White.copy(alpha = 0.9f), Offset(cx - 4.6f, 142.8f), Size(4.8f, 5.6f))
                drawCircle(Color.White.copy(alpha = 0.5f), 1f, Offset(cx + 2.6f, 155f))
            }
        }
    }
    // Mouth: the thin smile at rest, opening into a big smile with a tongue in the cheer.
    val y = 166f
    val open = 0.45f * pose
    val curve = 6f + 3f * pose
    if (open >= 0.06f) {
        val w = 6.5f
        val bottom = y + max(3f, curve) + open * 13
        val m = Path().apply { moveTo(120 - w, y); quadraticTo(120f, y + curve * 0.25f, 120 + w, y); quadraticTo(120f, bottom, 120 - w, y); close() }
        drawPath(m, s.ink)
        val tongue = min(1f, (open - 0.15f) * 4)
        if (tongue > 0f) {
            val ty = y + (bottom - y) * 0.75f
            clipPathCompat(m) { drawOval(Color(0xFFFF8DA6).copy(alpha = tongue), Offset(115.5f, ty - 3), Size(9f, 6f)) }
        }
    } else {
        val cc = max(1.5f, curve * 0.9f)
        drawPath(Path().apply { moveTo(112.5f, y - 1); quadraticTo(120f, y + cc, 127.5f, y - 1) }, s.ink, style = Stroke(2.6f, cap = StrokeCap.Round))
    }
}

private inline fun DrawScope.clipPathCompat(p: Path, block: DrawScope.() -> Unit) =
    drawContext.canvas.run { save(); clipPath(p); block(); restore() }

/** A small round part (ear, hand) lit like the body. */
private fun DrawScope.softBall(s: MochiSkin, c: Offset, rx: Float, ry: Float) {
    val tl = Offset(c.x - rx, c.y - ry)
    val sz = Size(rx * 2, ry * 2)
    drawOval(Brush.radialGradient(0f to s.hi, 0.42f to s.bodyTop, 1f to s.bodyBot, center = Offset(tl.x + sz.width * 0.36f, tl.y + sz.height * 0.26f), radius = max(rx, ry) * 1.7f), tl, sz)
    drawOval(Brush.radialGradient(listOf(s.shade.copy(alpha = 0.38f), s.shade.copy(alpha = 0f)), Offset(tl.x + sz.width * 0.64f, tl.y + sz.height * 0.95f), max(rx, ry) * 1.5f), tl, sz)
}

/** Round on top, wider below, a little flattened at the bottom. */
private val BODY = Path().apply {
    moveTo(120f, 66f)
    cubicTo(171f, 66f, 203f, 104f, 205f, 150f)
    cubicTo(207f, 189f, 187f, 208f, 120f, 208f)
    cubicTo(53f, 208f, 33f, 189f, 35f, 150f)
    cubicTo(37f, 104f, 69f, 66f, 120f, 66f)
    close()
}

private fun star(r: Float, a: Float, b: Float) = Path().apply {
    moveTo(0f, -r)
    cubicTo(a, -b, b, -a, r, 0f)
    cubicTo(b, a, a, b, 0f, r)
    cubicTo(-a, b, -b, a, -r, 0f)
    cubicTo(-b, -a, -a, -b, 0f, -r)
    close()
}

private val STAR9 = star(9f, 1.4f, 2.5f)
private val STAR6 = Path().apply {
    moveTo(0f, -6f); quadraticTo(1f, -1f, 6f, 0f); quadraticTo(1f, 1f, 0f, 6f); quadraticTo(-1f, 1f, -6f, 0f); quadraticTo(-1f, -1f, 0f, -6f); close()
}
