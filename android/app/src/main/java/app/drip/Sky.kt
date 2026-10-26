package app.drip

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.sin

enum class SkyState { CLEAR, COMING, RAINING, NODATA }

/**
 * The animated sky band illustration from the design canvas: sun or storm cloud, a clothesline,
 * garments that drop in when clothes are marked out and sway (harder when rain is near), rain lines.
 * Everything is drawn, no assets, so it scales and recolours with the state.
 */
@Composable
fun SkyScene(state: SkyState, clothesOut: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "sky")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    val swayPhase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == SkyState.COMING) 1600 else 3400, easing = LinearEasing)), label = "sway")
    val rainPhase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "rain")
    val pulse by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pulse")

    // Garments drop in from above when toggled on, lift off when toggled off
    val garmentY by animateFloatAsState(if (clothesOut) 0f else -1f, tween(450, easing = EaseOutBack), label = "garment")
    val garmentAlpha by animateFloatAsState(if (clothesOut) 1f else 0f, tween(300), label = "galpha")
    val stormX by animateFloatAsState(if (state == SkyState.COMING || state == SkyState.RAINING) 0f else -1f, tween(900, easing = EaseOutCubic), label = "storm")
    val sunAlpha by animateFloatAsState(if (state == SkyState.CLEAR) 1f else 0f, tween(600), label = "sun")

    val dark = state == SkyState.COMING || state == SkyState.RAINING
    val rope = if (dark) Color(0xFF1F2D3D) else Color(0xFF5B6B7C)
    val post = if (dark) Color(0xFF5E5048) else Color(0xFF8C7B6A)
    val ground = when (state) { SkyState.CLEAR -> Color(0xFFCFE3EF); SkyState.COMING -> Color(0xFF34506D); SkyState.RAINING -> Color(0xFF22303F); SkyState.NODATA -> Color(0xFFCFD3CC) }

    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val w = size.width; val h = size.height
        // Sun
        if (sunAlpha > 0f) drawCircle(Color(0xFFF4C65A).copy(alpha = sunAlpha), radius = w * 0.1f, center = Offset(w * 0.76f, h * 0.3f))
        // Fair-weather clouds
        if (!dark) {
            val cx = w * 0.2f + drift * w * 0.08f
            cloud(Offset(cx, h * 0.38f), w * 0.11f, Color.White.copy(alpha = if (state == SkyState.NODATA) pulse else 1f))
        }
        // Storm cloud rolling in from the left (upwind)
        if (stormX > -1f) {
            val cx = w * 0.3f + stormX * w * 0.5f + drift * w * 0.04f
            cloud(Offset(cx, h * 0.3f), w * 0.2f, Color(0xFF2B3F57).copy(alpha = (stormX + 1f).coerceIn(0f, 1f)))
            if (state == SkyState.COMING) rainLines(cx - w * 0.2f, cx + w * 0.1f, h * 0.42f, h * 0.5f, rainPhase, 5, Color(0xFF9DC3E6))
        }
        // Full rain
        if (state == SkyState.RAINING) {
            rainLines(0f, w, h * 0.2f, h * 0.95f, rainPhase, 18, Color(0xFF8FB6DA).copy(alpha = 0.9f))
            rainLines(w * 0.05f, w * 1.05f, h * 0.35f, h * 0.98f, (rainPhase + 0.5f) % 1f, 14, Color(0xFF8FB6DA).copy(alpha = 0.7f))
        }
        // Ground, posts, rope
        drawRect(ground, Offset(0f, h * 0.92f), Size(w, h * 0.08f))
        drawRect(post, Offset(w * 0.035f, h * 0.49f), Size(w * 0.013f, h * 0.43f))
        drawRect(post, Offset(w * 0.952f, h * 0.49f), Size(w * 0.013f, h * 0.43f))
        val ropeY = h * 0.52f
        drawPath(Path().apply { moveTo(w * 0.048f, ropeY); quadraticBezierTo(w * 0.5f, ropeY + h * 0.07f, w * 0.952f, ropeY) }, rope, style = Stroke(2.5f * density, cap = StrokeCap.Round))
        // Garments
        if (garmentAlpha > 0f) {
            val amp = if (state == SkyState.COMING) 7f else 2.5f
            val lift = garmentY * h * 0.5f
            garment(Offset(w * 0.22f, ropeY + h * 0.03f + lift), w, h, Color(0xFFE9A23B).copy(alpha = garmentAlpha), angle(swayPhase, 0f, amp), 0)
            garment(Offset(w * 0.5f, ropeY + h * 0.06f + lift), w, h, (if (dark) Color(0xFF5A86B8) else Color(0xFF2F5D8A)).copy(alpha = garmentAlpha), angle(swayPhase, 0.33f, amp), 1)
            garment(Offset(w * 0.77f, ropeY + h * 0.03f + lift), w, h, (if (dark) Color(0xFFE8E2D4) else Color(0xFFF7F3EA)).copy(alpha = garmentAlpha), angle(swayPhase, 0.66f, amp), 2)
        }
    }
}

private fun angle(phase: Float, offset: Float, amp: Float) = (sin((phase + offset) * 2f * Math.PI) * amp).toFloat()

private fun DrawScope.cloud(c: Offset, r: Float, color: Color) {
    drawOval(color, Offset(c.x - r * 1.6f, c.y - r * 0.5f), Size(r * 3.2f, r))
    drawOval(color, Offset(c.x - r * 0.6f, c.y - r * 1.1f), Size(r * 2.0f, r * 1.6f))
    drawOval(color, Offset(c.x - r * 2.1f, c.y - r * 0.8f), Size(r * 1.8f, r * 1.3f))
}

private fun DrawScope.rainLines(x0: Float, x1: Float, y0: Float, y1: Float, phase: Float, n: Int, color: Color) {
    val span = y1 - y0
    for (i in 0 until n) {
        val x = x0 + (x1 - x0) * (i + 0.5f) / n
        val y = y0 + ((phase + i * 0.37f) % 1f) * span
        drawLine(color, Offset(x, y), Offset(x - 4f, y + 24f), strokeWidth = 1.6f * density, cap = StrokeCap.Round)
    }
}

private fun DrawScope.garment(pin: Offset, w: Float, h: Float, color: Color, deg: Float, kind: Int) {
    rotate(deg, pin) {
        drawRect(Color(0xFFC6A078), Offset(pin.x - 3f * density, pin.y - h * 0.04f), Size(6f * density, h * 0.05f))
        val p = Path()
        when (kind) {
            0 -> { p.moveTo(pin.x - w * 0.065f, pin.y); p.lineTo(pin.x + w * 0.065f, pin.y); p.lineTo(pin.x + w * 0.08f, pin.y + h * 0.26f); p.lineTo(pin.x - w * 0.08f, pin.y + h * 0.26f); p.close() }
            1 -> { p.addRect(androidx.compose.ui.geometry.Rect(pin.x - w * 0.09f, pin.y, pin.x + w * 0.09f, pin.y + h * 0.38f)) }
            else -> { p.moveTo(pin.x - w * 0.055f, pin.y); p.lineTo(pin.x + w * 0.055f, pin.y); p.lineTo(pin.x + w * 0.045f, pin.y + h * 0.1f); p.lineTo(pin.x + w * 0.09f, pin.y + h * 0.36f); p.lineTo(pin.x - w * 0.09f, pin.y + h * 0.36f); p.lineTo(pin.x - w * 0.045f, pin.y + h * 0.1f); p.close() }
        }
        drawPath(p, color)
    }
}
