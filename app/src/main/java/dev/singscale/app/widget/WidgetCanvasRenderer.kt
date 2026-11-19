package dev.singscale.app.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import dev.singscale.app.core.TunnelStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-DPI procedural Canvas renderer for SingScale Home Screen Widgets.
 * Renders anti-aliased obsidian-titanium glass surfaces, directional specular rims,
 * 36-tick chronograph bezels, and radial aurora reactor cores impossible with static XML shapes.
 */
object WidgetCanvasRenderer {

    private enum class OrbVisualState {
        RUNNING,
        CONNECTING,
        ERROR,
        IDLE;

        companion object {
            fun from(status: TunnelStatus): OrbVisualState = when (status) {
                TunnelStatus.RUNNING -> RUNNING
                TunnelStatus.STARTING, TunnelStatus.NEEDS_LOGIN -> CONNECTING
                TunnelStatus.ERROR -> ERROR
                TunnelStatus.STOPPED, TunnelStatus.STOPPING -> IDLE
            }
        }
    }

    private val miniJewelCache = ConcurrentHashMap<OrbVisualState, Bitmap>(4)
    private val cardOrbCache = ConcurrentHashMap<OrbVisualState, Bitmap>(4)

    /**
     * Renders the 1x1 Quick Toggle Jewel (288x288 px supersampled 1:1 squircle).
     * Cached across updates for zero per-refresh Bitmap allocation & software-blur CPU cost.
     */
    fun renderMiniJewel(status: TunnelStatus): Bitmap {
        val key = OrbVisualState.from(status)
        val cached = miniJewelCache[key]
        if (cached != null && !cached.isRecycled) {
            return cached
        }
        val rendered = createMiniJewelBitmap(status)
        miniJewelCache[key] = rendered
        return rendered
    }

    private fun createMiniJewelBitmap(status: TunnelStatus): Bitmap {
        val size = 288
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val cx = size / 2f
        val cy = size / 2f
        val pad = 16f
        val bodyRect = RectF(pad, pad, size - pad, size - pad)
        val cornerR = 68f // Continuous squircle proportion (~26.5% of body width)

        val isRunning = status == TunnelStatus.RUNNING
        val isConnecting = status == TunnelStatus.STARTING || status == TunnelStatus.NEEDS_LOGIN
        val isError = status == TunnelStatus.ERROR

        // 1. Soft ambient drop shadow so the tile floats cleanly over any wallpaper
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(115, 0, 0, 0)
            maskFilter = BlurMaskFilter(11f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(bodyRect.left + 2f, bodyRect.top + 5f, bodyRect.right - 2f, bodyRect.bottom + 5f),
            cornerR,
            cornerR,
            shadowPaint
        )

        // 2. Deep Smoked Obsidian-Titanium Glass Base
        val baseColors = when {
            isRunning -> intArrayOf(
                Color.parseColor("#0C2A23"),
                Color.parseColor("#081818"),
                Color.parseColor("#060B12")
            )
            isConnecting -> intArrayOf(
                Color.parseColor("#0D283A"),
                Color.parseColor("#081726"),
                Color.parseColor("#060A13")
            )
            isError -> intArrayOf(
                Color.parseColor("#2B1019"),
                Color.parseColor("#160A12"),
                Color.parseColor("#080810")
            )
            else -> intArrayOf(
                Color.parseColor("#141C2E"),
                Color.parseColor("#0B101D"),
                Color.parseColor("#060911")
            )
        }
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = LinearGradient(
                bodyRect.left,
                bodyRect.top,
                bodyRect.right,
                bodyRect.bottom,
                baseColors,
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(bodyRect, cornerR, cornerR, basePaint)

        // 3. Inner Atmospheric Radial Aurora Bloom
        val auroraColor = when {
            isRunning -> Color.argb(92, 16, 185, 129)    // Emerald #10B981
            isConnecting -> Color.argb(86, 34, 211, 238) // Cyan #22D3EE
            isError -> Color.argb(80, 251, 113, 133)     // Coral #FB7185
            else -> Color.argb(28, 129, 140, 248)        // Subtle Indigo sheen
        }
        val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = android.graphics.RadialGradient(
                cx,
                cy,
                bodyRect.width() * 0.56f,
                intArrayOf(auroraColor, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(bodyRect, cornerR, cornerR, auroraPaint)

        // 4. Directional Top-Lit Specular Glass Rim
        val rimInset = 1.2f
        val rimRect = RectF(
            bodyRect.left + rimInset,
            bodyRect.top + rimInset,
            bodyRect.right - rimInset,
            bodyRect.bottom - rimInset
        )
        val rimColors = when {
            isRunning -> intArrayOf(
                Color.argb(210, 110, 231, 183),
                Color.argb(95, 16, 185, 129),
                Color.argb(35, 34, 211, 238)
            )
            isConnecting -> intArrayOf(
                Color.argb(210, 103, 232, 249),
                Color.argb(95, 34, 211, 238),
                Color.argb(35, 8, 145, 178)
            )
            else -> intArrayOf(
                Color.argb(72, 255, 255, 255),
                Color.argb(26, 148, 163, 184),
                Color.argb(14, 255, 255, 255)
            )
        }
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            shader = LinearGradient(
                rimRect.left,
                rimRect.top,
                rimRect.right,
                rimRect.bottom,
                rimColors,
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rimRect, cornerR - rimInset, cornerR - rimInset, rimPaint)

        // 5. Chronograph 36-Tick Precision Bezel + Core Reactor Orb
        drawChronographOrb(
            canvas = canvas,
            cx = cx,
            cy = cy,
            bezelOuterR = 92f,
            coreR = 62f,
            status = status
        )

        // 6. Subtle Top-Right Status Jewel LED Dot
        val ledX = bodyRect.right - 34f
        val ledY = bodyRect.top + 34f
        if (isRunning || isConnecting) {
            val ledGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isRunning) Color.argb(130, 52, 211, 153) else Color.argb(130, 34, 211, 238)
                maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(ledX, ledY, 7f, ledGlow)
        }
        val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                isRunning -> Color.parseColor("#34D399")
                isConnecting -> Color.parseColor("#67E8F9")
                isError -> Color.parseColor("#FB7185")
                else -> Color.parseColor("#334155")
            }
        }
        canvas.drawCircle(ledX, ledY, 4.5f, ledPaint)

        return bmp
    }

    /**
     * Renders a crisp circular Chronograph Power Orb (192x192 px) for the 4x2 Status Card Widget.
     */
    fun renderCardOrb(status: TunnelStatus): Bitmap {
        val key = OrbVisualState.from(status)
        val cached = cardOrbCache[key]
        if (cached != null && !cached.isRecycled) {
            return cached
        }
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawChronographOrb(
            canvas = canvas,
            cx = size / 2f,
            cy = size / 2f,
            bezelOuterR = 84f,
            coreR = 56f,
            status = status
        )
        cardOrbCache[key] = bmp
        return bmp
    }

    private fun drawChronographOrb(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        bezelOuterR: Float,
        coreR: Float,
        status: TunnelStatus
    ) {
        val isRunning = status == TunnelStatus.RUNNING
        val isConnecting = status == TunnelStatus.STARTING || status == TunnelStatus.NEEDS_LOGIN
        val isError = status == TunnelStatus.ERROR

        // 1. 36-Tick Precision Bezel
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until 36) {
            val deg = i * 10.0 - 90.0
            val rad = Math.toRadians(deg)
            val isMajor = i % 3 == 0
            val tickLen = if (isMajor) 9.5f else 5.5f
            val rIn = bezelOuterR - tickLen

            tickPaint.strokeWidth = if (isMajor) 2.6f else 1.7f
            tickPaint.color = when {
                isRunning -> if (isMajor) Color.argb(215, 52, 211, 153) else Color.argb(105, 16, 185, 129)
                isConnecting -> if (isMajor) Color.argb(210, 103, 232, 249) else Color.argb(95, 34, 211, 238)
                isError -> if (isMajor) Color.argb(190, 251, 113, 133) else Color.argb(85, 244, 63, 94)
                else -> if (isMajor) Color.argb(115, 100, 116, 139) else Color.argb(55, 71, 85, 105)
            }

            canvas.drawLine(
                cx + (rIn * cos(rad)).toFloat(),
                cy + (rIn * sin(rad)).toFloat(),
                cx + (bezelOuterR * cos(rad)).toFloat(),
                cy + (bezelOuterR * sin(rad)).toFloat(),
                tickPaint
            )
        }

        // 2. Soft Luminous Core Halo when Active
        if (isRunning || isConnecting) {
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isRunning) Color.argb(110, 16, 185, 129) else Color.argb(95, 34, 211, 238)
                maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(cx, cy, coreR * 0.96f, haloPaint)
        }

        // 3. Tactile Inner Core Button Dome
        val coreColors = when {
            isRunning -> intArrayOf(
                Color.parseColor("#34D399"),
                Color.parseColor("#10B981"),
                Color.parseColor("#059669")
            )
            isConnecting -> intArrayOf(
                Color.parseColor("#15455E"),
                Color.parseColor("#0C283B"),
                Color.parseColor("#081826")
            )
            isError -> intArrayOf(
                Color.parseColor("#4C1626"),
                Color.parseColor("#2A0D18"),
                Color.parseColor("#170810")
            )
            else -> intArrayOf(
                Color.parseColor("#1D2842"),
                Color.parseColor("#12192C"),
                Color.parseColor("#0B101D")
            )
        }
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = LinearGradient(
                cx,
                cy - coreR,
                cx,
                cy + coreR,
                coreColors,
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, coreR, corePaint)

        // Core Specular Rim
        val coreRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            shader = LinearGradient(
                cx,
                cy - coreR,
                cx,
                cy + coreR,
                if (isRunning) {
                    intArrayOf(Color.argb(210, 209, 250, 229), Color.argb(60, 4, 120, 87))
                } else if (isConnecting) {
                    intArrayOf(Color.argb(210, 103, 232, 249), Color.argb(60, 8, 145, 178))
                } else {
                    intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(20, 255, 255, 255))
                },
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, coreR - 1.1f, coreRimPaint)

        // 4. Sculptural Precision Power Glyph
        val glyphColor = when {
            isRunning -> Color.parseColor("#022419")
            isConnecting -> Color.parseColor("#67E8F9")
            isError -> Color.parseColor("#FDA4AF")
            else -> Color.parseColor("#E2E8F0")
        }
        val glyphStroke = coreR * 0.082f
        val glyphR = coreR * 0.36f
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = glyphStroke
            strokeCap = Paint.Cap.ROUND
            color = glyphColor
        }

        // Arc from -52 deg to +232 deg (leaving clean 76-deg top gap for vertical stem)
        val arcRect = RectF(cx - glyphR, cy - glyphR + 2f, cx + glyphR, cy + glyphR + 2f)
        canvas.drawArc(arcRect, -50f, 280f, false, glyphPaint)

        // Vertical stem
        canvas.drawLine(
            cx,
            cy - glyphR - coreR * 0.05f,
            cx,
            cy - coreR * 0.02f,
            glyphPaint
        )
    }
}
