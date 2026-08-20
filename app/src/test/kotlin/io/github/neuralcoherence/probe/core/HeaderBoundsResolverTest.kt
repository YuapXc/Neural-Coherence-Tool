package io.github.neuralcoherence.probe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderBoundsResolverTest {
    private val network = HeaderBounds(left = 20, top = 10, right = 100, bottom = 40)

    @Test
    fun `includes friend count on the same header row`() {
        val right = HeaderBoundsResolver.expandNetworkRight(
            network = network,
            headerActionLeft = 300,
            friendCounts = listOf(HeaderBounds(left = 106, top = 12, right = 158, bottom = 38)),
        )

        assertEquals(158, right)
    }

    @Test
    fun `ignores counts outside the header gap`() {
        val right = HeaderBoundsResolver.expandNetworkRight(
            network = network,
            headerActionLeft = 300,
            friendCounts = listOf(
                HeaderBounds(left = 110, top = 80, right = 150, bottom = 110),
                HeaderBounds(left = 290, top = 12, right = 320, bottom = 38),
            ),
        )

        assertEquals(100, right)
    }
}
