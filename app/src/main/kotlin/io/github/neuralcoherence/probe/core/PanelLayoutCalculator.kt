package io.github.neuralcoherence.probe.core

/** Pixel placement produced independently from Android views and Flutter reflection. */
data class PanelPlacement(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

object PanelLayoutCalculator {
    @JvmStatic
    @Suppress("LongParameterList")
    fun calculate(
        networkRight: Int,
        networkCenterY: Int,
        headerActionLeft: Int,
        settingsCenterY: Int,
        padding: Int,
        minimumWidth: Int,
        desiredWidth: Int,
        panelHeight: Int,
        flutterScreenX: Int,
        flutterScreenY: Int,
        contentScreenX: Int,
        contentScreenY: Int,
        contentWidth: Int,
        contentHeight: Int,
    ): PanelPlacement? {
        val availableWidth = headerActionLeft - networkRight - padding * 2
        if (availableWidth < minimumWidth) return null

        val width = minOf(desiredWidth, availableWidth)
        val localLeft = networkRight + padding + (availableWidth - width) / 2
        val localCenterY = (networkCenterY + settingsCenterY) / 2
        val localTop = localCenterY - panelHeight / 2
        val left = maxOf(
            0,
            minOf(
                flutterScreenX - contentScreenX + localLeft,
                contentWidth - width,
            ),
        )
        val top = maxOf(
            0,
            minOf(
                flutterScreenY - contentScreenY + localTop,
                contentHeight - panelHeight,
            ),
        )
        return PanelPlacement(left, top, width, panelHeight)
    }
}
