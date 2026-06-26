package com.tennisbird.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.tennisbird.app.game.CourtBounds
import com.tennisbird.app.game.GameRect
import com.tennisbird.app.game.RallyGate
import com.tennisbird.app.game.hitsBarrier
import com.tennisbird.app.game.lowerPanel
import com.tennisbird.app.game.markClearedGates
import com.tennisbird.app.game.upperPanel
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class TennisBirdView(context: Context) : View(context), Choreographer.FrameCallback {
    private enum class Screen {
        Menu,
        Playing,
        GameOver,
    }

    private enum class BallSkin(
        val storageKey: String,
        val titleRes: Int,
    ) {
        Classic("classic", R.string.classic_tennis),
        Clay("clay", R.string.clay_spin),
        Neon("neon", R.string.neon_smash),
    }

    private val prefs = context.getSharedPreferences("tennis_bird", Context.MODE_PRIVATE)
    private val random = Random(System.currentTimeMillis())
    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private var screen = Screen.Menu
    private var selectedSkin = BallSkin.entries.firstOrNull {
        it.storageKey == prefs.getString(KEY_SELECTED_SKIN, BallSkin.Classic.storageKey)
    } ?: BallSkin.Classic

    private var highScore = prefs.getInt(KEY_HIGH_SCORE, 0)
    private var score = 0
    private var birdX = 0f
    private var birdY = 0f
    private var verticalVelocity = 0f
    private var spin = 0f
    private var elapsed = 0f
    private var serveTimer = 0f
    private var lastFrameTimeNs = 0L
    private var isRunning = false
    private var gates = mutableListOf<RallyGate>()

    private val skinButtons = mutableMapOf<BallSkin, RectF>()
    private val playButton = RectF()
    private val retryButton = RectF()
    private val menuButton = RectF()

    init {
        isFocusable = true
    }

    fun resume() {
        if (!isRunning) {
            isRunning = true
            lastFrameTimeNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun pause() {
        if (isRunning) {
            isRunning = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        if (lastFrameTimeNs != 0L) {
            val deltaSeconds = ((frameTimeNanos - lastFrameTimeNs) / NANOS_PER_SECOND)
                .coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
            if (screen == Screen.Playing) updateCourt(deltaSeconds)
        }

        lastFrameTimeNs = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        layoutControls(width.toFloat(), height.toFloat())
        if (birdX == 0f) resetBird()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true

        when (screen) {
            Screen.Menu -> handleMenuTap(event.x, event.y)
            Screen.Playing -> volley()
            Screen.GameOver -> handleGameOverTap(event.x, event.y)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        layoutControls(width.toFloat(), height.toFloat())

        when (screen) {
            Screen.Menu -> drawMenu(canvas)
            Screen.Playing -> drawCourt(canvas, showHint = score == 0 && elapsed < 3.2f)
            Screen.GameOver -> {
                drawCourt(canvas, showHint = false)
                drawGameOver(canvas)
            }
        }
    }

    private fun handleMenuTap(x: Float, y: Float) {
        val tappedSkin = skinButtons.entries.firstOrNull { (_, rect) -> rect.contains(x, y) }?.key
        if (tappedSkin != null) {
            selectedSkin = tappedSkin
            prefs.edit().putString(KEY_SELECTED_SKIN, selectedSkin.storageKey).apply()
            invalidate()
            return
        }

        if (playButton.contains(x, y)) {
            startGame()
        }
    }

    private fun handleGameOverTap(x: Float, y: Float) {
        when {
            retryButton.contains(x, y) -> startGame()
            menuButton.contains(x, y) -> {
                screen = Screen.Menu
                resetBird()
                invalidate()
            }
            else -> startGame()
        }
    }

    private fun startGame() {
        score = 0
        elapsed = 0f
        serveTimer = 0f
        gates.clear()
        resetBird()
        screen = Screen.Playing
        invalidate()
    }

    private fun resetBird() {
        birdX = width * BIRD_X_RATIO
        birdY = height * START_Y_RATIO
        verticalVelocity = 0f
        spin = 0f
    }

    private fun volley() {
        verticalVelocity = -height * VOLLEY_STRENGTH_RATIO
    }

    private fun updateCourt(deltaSeconds: Float) {
        if (width == 0 || height == 0) return

        elapsed += deltaSeconds
        val floor = courtFloor()
        val radius = ballRadius()
        val speed = gateSpeed()
        val gateWidth = gateWidth()
        val laneHeight = laneHeight()

        verticalVelocity += gravity() * deltaSeconds
        birdY += verticalVelocity * deltaSeconds
        spin += speed * deltaSeconds * SPIN_SPEED

        gates = gates
            .map { gate -> gate.copy(x = gate.x - speed * deltaSeconds) }
            .filter { gate -> gate.x + gateWidth > -dp(24f) }
            .toMutableList()

        serveTimer += deltaSeconds
        if (serveTimer >= serveInterval()) {
            serveTimer = 0f
            addGate()
        }

        val (updatedGates, gainedScore) = markClearedGates(birdX, gates, gateWidth)
        gates = updatedGates.toMutableList()
        score += gainedScore

        val bounds = CourtBounds(
            gateWidth = gateWidth,
            laneHeight = laneHeight,
            worldHeight = height.toFloat(),
            courtFloor = floor,
        )
        val hitCourtEdge = birdY - radius < 0f || birdY + radius > floor
        if (hitCourtEdge || hitsBarrier(birdX, birdY, radius * COLLISION_RADIUS_RATIO, gates, bounds)) {
            endGame()
        }
    }

    private fun addGate() {
        val floor = courtFloor()
        val laneHeight = laneHeight()
        val minCenter = laneHeight / 2f + dp(48f)
        val maxCenter = floor - laneHeight / 2f - dp(54f)
        val centerY = if (maxCenter > minCenter) {
            random.nextDouble(minCenter.toDouble(), maxCenter.toDouble()).toFloat()
        } else {
            floor * 0.5f
        }
        gates += RallyGate(x = width + gateWidth(), laneCenterY = centerY)
    }

    private fun endGame() {
        screen = Screen.GameOver
        if (score > highScore) {
            highScore = score
            prefs.edit().putInt(KEY_HIGH_SCORE, highScore).apply()
        }
    }

    private fun drawMenu(canvas: Canvas) {
        drawBackground(canvas)

        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.menu_title),
            centerX = width / 2f,
            baselineY = height * 0.17f,
            maxWidth = width - dp(40f),
            preferredSize = dp(44f),
            color = Color.WHITE,
            bold = true,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.menu_subtitle),
            centerX = width / 2f,
            baselineY = height * 0.235f,
            maxWidth = width - dp(44f),
            preferredSize = dp(16f),
            color = Color.rgb(229, 248, 242),
            bold = false,
        )

        skinButtons.forEach { (skin, rect) ->
            drawSkinChoice(canvas, skin, rect, skin == selectedSkin)
        }

        drawButton(
            canvas = canvas,
            rect = playButton,
            text = resources.getString(R.string.play),
            fillColor = Color.rgb(216, 255, 64),
            textColor = Color.rgb(17, 36, 42),
        )

        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.best_score, highScore),
            centerX = width / 2f,
            baselineY = playButton.bottom + dp(42f),
            maxWidth = width - dp(40f),
            preferredSize = dp(18f),
            color = Color.WHITE,
            bold = true,
        )
    }

    private fun drawCourt(canvas: Canvas, showHint: Boolean) {
        drawBackground(canvas)
        drawGates(canvas)
        drawTennisBall(canvas, birdX, birdY, ballRadius(), selectedSkin, spin)
        drawScore(canvas)

        if (showHint) {
            drawTextFit(
                canvas = canvas,
                text = resources.getString(R.string.tap_to_fly),
                centerX = width / 2f,
                baselineY = height * 0.78f,
                maxWidth = width - dp(32f),
                preferredSize = dp(16f),
                color = Color.WHITE,
                bold = true,
            )
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        fillPaint.shader = null
        fillPaint.color = Color.argb(186, 8, 18, 24)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.game_over),
            centerX = width / 2f,
            baselineY = height * 0.34f,
            maxWidth = width - dp(40f),
            preferredSize = dp(36f),
            color = Color.WHITE,
            bold = true,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.score, score),
            centerX = width / 2f,
            baselineY = height * 0.41f,
            maxWidth = width - dp(40f),
            preferredSize = dp(22f),
            color = Color.rgb(216, 255, 64),
            bold = true,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.best_score, highScore),
            centerX = width / 2f,
            baselineY = height * 0.46f,
            maxWidth = width - dp(40f),
            preferredSize = dp(18f),
            color = Color.WHITE,
            bold = false,
        )

        drawButton(
            canvas = canvas,
            rect = retryButton,
            text = resources.getString(R.string.try_again),
            fillColor = Color.rgb(216, 255, 64),
            textColor = Color.rgb(17, 36, 42),
        )
        drawButton(
            canvas = canvas,
            rect = menuButton,
            text = resources.getString(R.string.menu),
            fillColor = Color.rgb(35, 132, 170),
            textColor = Color.WHITE,
        )
    }

    private fun drawBackground(canvas: Canvas) {
        val floor = courtFloor()
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            floor,
            intArrayOf(
                Color.rgb(20, 76, 105),
                Color.rgb(52, 155, 169),
                Color.rgb(236, 126, 94),
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), floor, fillPaint)
        fillPaint.shader = null

        drawCourtLights(canvas, floor)
        drawBackFence(canvas, floor)
        drawClayCourt(canvas, floor)
    }

    private fun drawCourtLights(canvas: Canvas, floor: Float) {
        fillPaint.color = Color.argb(80, 255, 242, 178)
        canvas.drawCircle(width * 0.16f, floor * 0.18f, dp(46f), fillPaint)
        canvas.drawCircle(width * 0.84f, floor * 0.22f, dp(38f), fillPaint)

        strokePaint.color = Color.argb(95, 235, 250, 255)
        strokePaint.strokeWidth = dp(2f)
        canvas.drawLine(width * 0.13f, floor * 0.25f, width * 0.09f, floor, strokePaint)
        canvas.drawLine(width * 0.87f, floor * 0.28f, width * 0.92f, floor, strokePaint)
    }

    private fun drawBackFence(canvas: Canvas, floor: Float) {
        val top = floor - dp(148f)
        fillPaint.color = Color.argb(128, 18, 42, 52)
        canvas.drawRect(0f, top, width.toFloat(), floor, fillPaint)

        strokePaint.color = Color.argb(95, 180, 222, 224)
        strokePaint.strokeWidth = dp(1f)
        var x = -dp(16f)
        while (x < width + dp(32f)) {
            canvas.drawLine(x, top, x + dp(76f), floor, strokePaint)
            canvas.drawLine(x + dp(76f), top, x, floor, strokePaint)
            x += dp(38f)
        }

        fillPaint.color = Color.rgb(31, 71, 78)
        canvas.drawRect(0f, floor - dp(16f), width.toFloat(), floor, fillPaint)
    }

    private fun drawClayCourt(canvas: Canvas, floor: Float) {
        fillPaint.color = Color.rgb(190, 79, 55)
        canvas.drawRect(0f, floor, width.toFloat(), height.toFloat(), fillPaint)

        val bandWidth = dp(64f)
        var x = -bandWidth
        var index = 0
        while (x < width + bandWidth) {
            fillPaint.color = if (index % 2 == 0) {
                Color.rgb(206, 92, 63)
            } else {
                Color.rgb(171, 66, 52)
            }
            val path = Path().apply {
                moveTo(x, floor)
                lineTo(x + bandWidth, floor)
                lineTo(x + bandWidth * 1.55f, height.toFloat())
                lineTo(x + bandWidth * 0.45f, height.toFloat())
                close()
            }
            canvas.drawPath(path, fillPaint)
            x += bandWidth
            index += 1
        }

        strokePaint.color = Color.argb(175, 255, 247, 224)
        strokePaint.strokeWidth = dp(2f)
        val serviceLine = floor + dp(28f)
        canvas.drawLine(0f, serviceLine, width.toFloat(), serviceLine, strokePaint)
        canvas.drawLine(width * 0.5f, serviceLine, width * 0.5f, height.toFloat(), strokePaint)
    }

    private fun drawGates(canvas: Canvas) {
        val bounds = CourtBounds(
            gateWidth = gateWidth(),
            laneHeight = laneHeight(),
            worldHeight = height.toFloat(),
            courtFloor = courtFloor(),
        )
        gates.forEach { gate ->
            drawNetPanel(canvas, gate.upperPanel(bounds).toRectF(), anchoredTop = true)
            drawNetPanel(canvas, gate.lowerPanel(bounds).toRectF(), anchoredTop = false)
        }
    }

    private fun drawNetPanel(canvas: Canvas, rect: RectF, anchoredTop: Boolean) {
        if (rect.height() <= dp(18f)) return

        fillPaint.shader = null
        fillPaint.color = Color.argb(70, 0, 0, 0)
        canvas.drawRoundRect(
            rect.left + dp(5f),
            rect.top + dp(5f),
            rect.right + dp(5f),
            rect.bottom + dp(5f),
            dp(10f),
            dp(10f),
            fillPaint,
        )

        fillPaint.color = Color.rgb(37, 94, 108)
        canvas.drawRoundRect(rect, dp(10f), dp(10f), fillPaint)

        val inner = RectF(rect.left + dp(8f), rect.top + dp(8f), rect.right - dp(8f), rect.bottom - dp(8f))
        fillPaint.color = Color.rgb(224, 244, 235)
        canvas.drawRoundRect(inner, dp(6f), dp(6f), fillPaint)

        strokePaint.color = Color.argb(150, 33, 91, 104)
        strokePaint.strokeWidth = dp(1.15f)
        canvas.save()
        canvas.clipRect(inner)
        var x = inner.left
        while (x <= inner.right) {
            canvas.drawLine(x, inner.top, x + inner.height() * 0.32f, inner.bottom, strokePaint)
            canvas.drawLine(x, inner.bottom, x + inner.height() * 0.32f, inner.top, strokePaint)
            x += dp(18f)
        }
        var y = inner.top
        while (y <= inner.bottom) {
            canvas.drawLine(inner.left, y, inner.right, y, strokePaint)
            y += dp(20f)
        }
        canvas.restore()

        fillPaint.color = Color.rgb(216, 255, 64)
        val capHeight = dp(16f)
        val cap = if (anchoredTop) {
            RectF(rect.left - dp(7f), rect.bottom - capHeight, rect.right + dp(7f), rect.bottom + dp(4f))
        } else {
            RectF(rect.left - dp(7f), rect.top - dp(4f), rect.right + dp(7f), rect.top + capHeight)
        }
        canvas.drawRoundRect(cap, dp(9f), dp(9f), fillPaint)
    }

    private fun drawScore(canvas: Canvas) {
        drawTextFit(
            canvas = canvas,
            text = score.toString(),
            centerX = width / 2f,
            baselineY = dp(64f),
            maxWidth = width - dp(40f),
            preferredSize = dp(42f),
            color = Color.WHITE,
            bold = true,
        )
    }

    private fun drawSkinChoice(canvas: Canvas, skin: BallSkin, rect: RectF, selected: Boolean) {
        fillPaint.shader = null
        fillPaint.color = if (selected) Color.rgb(216, 255, 64) else Color.argb(158, 16, 53, 65)
        canvas.drawRoundRect(rect, dp(16f), dp(16f), fillPaint)

        strokePaint.color = if (selected) Color.WHITE else Color.argb(150, 255, 255, 255)
        strokePaint.strokeWidth = if (selected) dp(3f) else dp(1.5f)
        canvas.drawRoundRect(rect, dp(16f), dp(16f), strokePaint)

        drawTennisBall(
            canvas = canvas,
            centerX = rect.centerX(),
            centerY = rect.top + rect.height() * 0.42f,
            radius = min(rect.width(), rect.height()) * 0.22f,
            skin = skin,
            rotation = 0f,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(skin.titleRes),
            centerX = rect.centerX(),
            baselineY = rect.bottom - dp(15f),
            maxWidth = rect.width() - dp(8f),
            preferredSize = dp(12.5f),
            color = if (selected) Color.rgb(17, 36, 42) else Color.WHITE,
            bold = true,
        )
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        text: String,
        fillColor: Int,
        textColor: Int,
    ) {
        fillPaint.shader = null
        fillPaint.color = Color.argb(85, 0, 0, 0)
        canvas.drawRoundRect(rect.left, rect.top + dp(5f), rect.right, rect.bottom + dp(5f), dp(16f), dp(16f), fillPaint)

        fillPaint.color = fillColor
        canvas.drawRoundRect(rect, dp(16f), dp(16f), fillPaint)

        drawTextFit(
            canvas = canvas,
            text = text,
            centerX = rect.centerX(),
            baselineY = textBaselineForCenter(rect.centerY(), dp(21f)),
            maxWidth = rect.width() - dp(24f),
            preferredSize = dp(21f),
            color = textColor,
            bold = true,
        )
    }

    private fun drawTennisBall(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        skin: BallSkin,
        rotation: Float,
    ) {
        fillPaint.shader = null
        fillPaint.color = Color.argb(85, 0, 0, 0)
        canvas.drawCircle(centerX + radius * 0.18f, centerY + radius * 0.24f, radius * 0.96f, fillPaint)

        canvas.save()
        canvas.rotate(rotation, centerX, centerY)
        when (skin) {
            BallSkin.Classic -> drawBallSurface(canvas, centerX, centerY, radius, Color.rgb(217, 255, 57), Color.rgb(139, 188, 33))
            BallSkin.Clay -> drawBallSurface(canvas, centerX, centerY, radius, Color.rgb(255, 182, 67), Color.rgb(200, 82, 53))
            BallSkin.Neon -> drawBallSurface(canvas, centerX, centerY, radius, Color.rgb(105, 245, 219), Color.rgb(51, 98, 230))
        }
        canvas.restore()
    }

    private fun drawBallSurface(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        bright: Int,
        dark: Int,
    ) {
        fillPaint.shader = RadialGradient(
            centerX - radius * 0.35f,
            centerY - radius * 0.38f,
            radius * 1.55f,
            intArrayOf(Color.WHITE, bright, dark),
            floatArrayOf(0f, 0.32f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        fillPaint.shader = null

        strokePaint.color = Color.argb(235, 245, 248, 238)
        strokePaint.strokeWidth = radius * 0.12f
        canvas.drawArc(
            centerX - radius * 1.2f,
            centerY - radius * 0.82f,
            centerX + radius * 0.2f,
            centerY + radius * 0.82f,
            -68f,
            136f,
            false,
            strokePaint,
        )
        canvas.drawArc(
            centerX - radius * 0.2f,
            centerY - radius * 0.82f,
            centerX + radius * 1.2f,
            centerY + radius * 0.82f,
            112f,
            136f,
            false,
            strokePaint,
        )

        strokePaint.color = Color.argb(105, 36, 52, 42)
        strokePaint.strokeWidth = radius * 0.05f
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
    }

    private fun drawTextFit(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        maxWidth: Float,
        preferredSize: Float,
        color: Int,
        bold: Boolean,
    ) {
        textPaint.color = color
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textPaint.textSize = preferredSize
        val measured = textPaint.measureText(text)
        if (measured > maxWidth) {
            textPaint.textSize = max(dp(10f), preferredSize * (maxWidth / measured))
        }
        canvas.drawText(text, centerX, baselineY, textPaint)
    }

    private fun layoutControls(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return

        val margin = dp(18f)
        val gap = dp(8f)
        val selectorWidth = (width - margin * 2f - gap * 2f) / 3f
        val selectorHeight = min(dp(118f), height * 0.17f)
        val selectorTop = height * 0.39f

        BallSkin.entries.forEachIndexed { index, skin ->
            val left = margin + index * (selectorWidth + gap)
            skinButtons[skin] = RectF(left, selectorTop, left + selectorWidth, selectorTop + selectorHeight)
        }

        val buttonWidth = min(width - margin * 2f, dp(236f))
        val buttonHeight = dp(58f)
        playButton.set(
            (width - buttonWidth) / 2f,
            height * 0.68f,
            (width + buttonWidth) / 2f,
            height * 0.68f + buttonHeight,
        )

        retryButton.set(
            (width - buttonWidth) / 2f,
            height * 0.54f,
            (width + buttonWidth) / 2f,
            height * 0.54f + buttonHeight,
        )
        menuButton.set(
            (width - buttonWidth) / 2f,
            retryButton.bottom + dp(14f),
            (width + buttonWidth) / 2f,
            retryButton.bottom + dp(14f) + buttonHeight,
        )
    }

    private fun textBaselineForCenter(centerY: Float, textSize: Float): Float {
        textPaint.textSize = textSize
        val metrics = textPaint.fontMetrics
        return centerY - (metrics.ascent + metrics.descent) / 2f
    }

    private fun courtFloor(): Float = height * COURT_FLOOR_RATIO

    private fun ballRadius(): Float = min(width, height) * BALL_RADIUS_RATIO

    private fun gateWidth(): Float = max(dp(66f), width * GATE_WIDTH_RATIO)

    private fun laneHeight(): Float {
        val base = height * LANE_HEIGHT_RATIO
        val tightening = min(score * dp(1.15f), dp(38f))
        return max(dp(164f), base - tightening)
    }

    private fun gravity(): Float = height * GRAVITY_RATIO

    private fun gateSpeed(): Float = width * SPEED_RATIO + score * dp(2.2f)

    private fun serveInterval(): Float = max(1.08f, 1.52f - score * 0.012f)

    private fun dp(value: Float): Float = value * density

    private fun GameRect.toRectF(): RectF = RectF(left, top, right, bottom)

    private companion object {
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_SELECTED_SKIN = "selected_skin"
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MAX_FRAME_DELTA_SECONDS = 0.033f
        private const val BIRD_X_RATIO = 0.28f
        private const val START_Y_RATIO = 0.43f
        private const val COURT_FLOOR_RATIO = 0.86f
        private const val BALL_RADIUS_RATIO = 0.046f
        private const val GATE_WIDTH_RATIO = 0.155f
        private const val LANE_HEIGHT_RATIO = 0.25f
        private const val GRAVITY_RATIO = 1.68f
        private const val VOLLEY_STRENGTH_RATIO = 0.58f
        private const val SPEED_RATIO = 0.46f
        private const val SPIN_SPEED = 0.18f
        private const val COLLISION_RADIUS_RATIO = 0.86f
    }
}
