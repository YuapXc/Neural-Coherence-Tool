package io.github.neuralcoherence.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PanelLayoutCalculatorTest {
    @Test
    fun `centers desired width between anchors`() {
        val placement = calculate(headerActionLeft = 400)

        assertNotNull(placement)
        assertEquals(PanelPlacement(left = 194, top = 45, width = 128, height = 44), placement)
    }

    @Test
    fun `shrinks to available width at minimum boundary`() {
        val placement = calculate(headerActionLeft = 220)

        assertNotNull(placement)
        assertEquals(112, placement?.width)
        assertEquals(112, placement?.left)
    }

    @Test
    fun `rejects space narrower than minimum`() {
        assertNull(calculate(headerActionLeft = 219))
    }

    @Test
    fun `clamps placement to content bounds`() {
        val placement = PanelLayoutCalculator.calculate(
            networkRight = 0,
            networkCenterY = 0,
            headerActionLeft = 200,
            settingsCenterY = 0,
            padding = 4,
            minimumWidth = 112,
            desiredWidth = 128,
            panelHeight = 44,
            flutterScreenX = -300,
            flutterScreenY = -300,
            contentScreenX = 0,
            contentScreenY = 0,
            contentWidth = 500,
            contentHeight = 500,
        )

        assertEquals(0, placement?.left)
        assertEquals(0, placement?.top)
    }

    private fun calculate(headerActionLeft: Int): PanelPlacement? =
        PanelLayoutCalculator.calculate(
            networkRight = 100,
            networkCenterY = 50,
            headerActionLeft = headerActionLeft,
            settingsCenterY = 54,
            padding = 4,
            minimumWidth = 112,
            desiredWidth = 128,
            panelHeight = 44,
            flutterScreenX = 10,
            flutterScreenY = 20,
            contentScreenX = 2,
            contentScreenY = 5,
            contentWidth = 500,
            contentHeight = 500,
        )
}
