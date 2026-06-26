package com.tennisbird.app.game

import kotlin.math.pow

data class RallyGate(
    val x: Float,
    val laneCenterY: Float,
    val cleared: Boolean = false,
)

data class GameRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun height(): Float = bottom - top
}

data class CourtBounds(
    val gateWidth: Float,
    val laneHeight: Float,
    val worldHeight: Float,
    val courtFloor: Float,
)

fun RallyGate.upperPanel(bounds: CourtBounds): GameRect =
    GameRect(x, 0f, x + bounds.gateWidth, laneCenterY - bounds.laneHeight / 2f)

fun RallyGate.lowerPanel(bounds: CourtBounds): GameRect =
    GameRect(x, laneCenterY + bounds.laneHeight / 2f, x + bounds.gateWidth, bounds.courtFloor)

fun circleIntersectsRect(
    centerX: Float,
    centerY: Float,
    radius: Float,
    rect: GameRect,
): Boolean {
    val closestX = centerX.coerceIn(rect.left, rect.right)
    val closestY = centerY.coerceIn(rect.top, rect.bottom)
    val dx = centerX - closestX
    val dy = centerY - closestY
    return dx.pow(2) + dy.pow(2) <= radius.pow(2)
}

fun hitsBarrier(
    centerX: Float,
    centerY: Float,
    radius: Float,
    gates: List<RallyGate>,
    bounds: CourtBounds,
): Boolean = gates.any { gate ->
    circleIntersectsRect(centerX, centerY, radius, gate.upperPanel(bounds)) ||
        circleIntersectsRect(centerX, centerY, radius, gate.lowerPanel(bounds))
}

fun markClearedGates(
    birdX: Float,
    gates: List<RallyGate>,
    gateWidth: Float,
): Pair<List<RallyGate>, Int> {
    var gained = 0
    val updated = gates.map { gate ->
        if (!gate.cleared && gate.x + gateWidth < birdX) {
            gained += 1
            gate.copy(cleared = true)
        } else {
            gate
        }
    }
    return updated to gained
}
