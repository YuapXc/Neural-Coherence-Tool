package io.github.neuralcoherence.probe.core

/** Geometry-only header bounds used without Android or Flutter dependencies. */
data class HeaderBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val height: Int
        get() = bottom - top

    val centerY: Int
        get() = (top + bottom) / 2
}

object HeaderBoundsResolver {
    @JvmStatic
    fun expandNetworkRight(
        network: HeaderBounds,
        settingsLeft: Int,
        friendCounts: List<HeaderBounds>,
    ): Int = friendCounts.asSequence()
        .filter { candidate ->
            candidate.right > network.right &&
                candidate.left < settingsLeft &&
                candidate.right < settingsLeft &&
                verticalOverlap(network, candidate) > 0 &&
                kotlin.math.abs(candidate.centerY - network.centerY) <=
                maxOf(network.height, candidate.height)
        }
        .maxOfOrNull(HeaderBounds::right)
        ?.coerceAtLeast(network.right)
        ?: network.right

    private fun verticalOverlap(first: HeaderBounds, second: HeaderBounds): Int =
        minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
}
