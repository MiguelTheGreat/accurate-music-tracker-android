package com.musictracker.app.ui

import com.musictracker.app.ui.components.glass.calculateNavBarDragIndex
import com.musictracker.app.ui.components.glass.calculateNavBarDragPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidGlassNavBarDragTest {

    // 3 tabs, width = 100px, gap = 10px, horizontal padding = 16px
    // Tab 0 center: 16 + 50 = 66
    // Tab 1 center: 16 + 110 + 50 = 176
    // Tab 2 center: 16 + 220 + 50 = 286
    // Total contentWidth = 3 * 100 + 2 * 10 = 320, right edge = 336
    private val tabWidth = 100f
    private val tabGap = 10f
    private val hPad = 16f
    private val itemCount = 3

    @Test
    fun ltr_tabCentersMapToExactLogicalPositionsAndIndices() {
        assertEquals(0f, calculateNavBarDragPosition(66f, tabWidth, tabGap, hPad, itemCount, isRtl = false), 0.001f)
        assertEquals(1f, calculateNavBarDragPosition(176f, tabWidth, tabGap, hPad, itemCount, isRtl = false), 0.001f)
        assertEquals(2f, calculateNavBarDragPosition(286f, tabWidth, tabGap, hPad, itemCount, isRtl = false), 0.001f)

        assertEquals(0, calculateNavBarDragIndex(66f, tabWidth, tabGap, hPad, itemCount, isRtl = false))
        assertEquals(1, calculateNavBarDragIndex(176f, tabWidth, tabGap, hPad, itemCount, isRtl = false))
        assertEquals(2, calculateNavBarDragIndex(286f, tabWidth, tabGap, hPad, itemCount, isRtl = false))
    }

    @Test
    fun rtl_mirrorsHorizontalCoordinatesCleanly() {
        // In RTL, logical tab 0 is at the right (center = 286), logical tab 2 is at the left (center = 66).
        assertEquals(0f, calculateNavBarDragPosition(286f, tabWidth, tabGap, hPad, itemCount, isRtl = true), 0.001f)
        assertEquals(1f, calculateNavBarDragPosition(176f, tabWidth, tabGap, hPad, itemCount, isRtl = true), 0.001f)
        assertEquals(2f, calculateNavBarDragPosition(66f, tabWidth, tabGap, hPad, itemCount, isRtl = true), 0.001f)

        assertEquals(0, calculateNavBarDragIndex(286f, tabWidth, tabGap, hPad, itemCount, isRtl = true))
        assertEquals(1, calculateNavBarDragIndex(176f, tabWidth, tabGap, hPad, itemCount, isRtl = true))
        assertEquals(2, calculateNavBarDragIndex(66f, tabWidth, tabGap, hPad, itemCount, isRtl = true))
    }

    @Test
    fun boundaryClamping_clampsOutOfBoundsPointerX() {
        assertEquals(0f, calculateNavBarDragPosition(-50f, tabWidth, tabGap, hPad, itemCount, isRtl = false), 0.001f)
        assertEquals(2f, calculateNavBarDragPosition(999f, tabWidth, tabGap, hPad, itemCount, isRtl = false), 0.001f)
        assertEquals(0, calculateNavBarDragIndex(-50f, tabWidth, tabGap, hPad, itemCount, isRtl = false))
        assertEquals(2, calculateNavBarDragIndex(999f, tabWidth, tabGap, hPad, itemCount, isRtl = false))

        assertEquals(2f, calculateNavBarDragPosition(-50f, tabWidth, tabGap, hPad, itemCount, isRtl = true), 0.001f)
        assertEquals(0f, calculateNavBarDragPosition(999f, tabWidth, tabGap, hPad, itemCount, isRtl = true), 0.001f)
        assertEquals(2, calculateNavBarDragIndex(-50f, tabWidth, tabGap, hPad, itemCount, isRtl = true))
        assertEquals(0, calculateNavBarDragIndex(999f, tabWidth, tabGap, hPad, itemCount, isRtl = true))
    }

    @Test
    fun midpointTransitions_switchTabAcrossMidpoint() {
        // Midpoint between tab 0 (66) and tab 1 (176) is 121.
        assertEquals(0, calculateNavBarDragIndex(120.4f, tabWidth, tabGap, hPad, itemCount, isRtl = false))
        assertEquals(1, calculateNavBarDragIndex(121.6f, tabWidth, tabGap, hPad, itemCount, isRtl = false))

        // Midpoint between tab 1 (176) and tab 2 (286) is 231.
        assertEquals(1, calculateNavBarDragIndex(230.4f, tabWidth, tabGap, hPad, itemCount, isRtl = false))
        assertEquals(2, calculateNavBarDragIndex(231.6f, tabWidth, tabGap, hPad, itemCount, isRtl = false))
    }

    @Test
    fun degenerateInputs_returnZeroSafely() {
        assertEquals(0f, calculateNavBarDragPosition(150f, tabWidth, tabGap, hPad, itemCount = 1, isRtl = false), 0.001f)
        assertEquals(0, calculateNavBarDragIndex(150f, tabWidth, tabGap, hPad, itemCount = 0, isRtl = false))
        assertEquals(0f, calculateNavBarDragPosition(150f, tabWidthPx = 0f, tabGap, hPad, itemCount, isRtl = false), 0.001f)
        assertEquals(0, calculateNavBarDragIndex(150f, tabWidthPx = -10f, tabGap, hPad, itemCount, isRtl = true))
    }
}
