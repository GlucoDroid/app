package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardChartRangeTests {

    @Test
    fun coerceChartYToDrawableRangeUsesInsetWhenThereIsRoom() {
        assertEquals(12f, coerceChartYToDrawableRange(12f, chartHeight = 100f, edgeInset = 6f), 0.001f)
        assertEquals(6f, coerceChartYToDrawableRange(-20f, chartHeight = 100f, edgeInset = 6f), 0.001f)
        assertEquals(94f, coerceChartYToDrawableRange(140f, chartHeight = 100f, edgeInset = 6f), 0.001f)
    }

    @Test
    fun coerceChartYToDrawableRangeHandlesCollapsedInsetRange() {
        val chartHeight = 1f
        val edgeInset = 19.5f

        assertEquals(1f, coerceChartYToDrawableRange(20f, chartHeight, edgeInset), 0.001f)
        assertEquals(0f, coerceChartYToDrawableRange(-20f, chartHeight, edgeInset), 0.001f)
        assertEquals(0.5f, coerceChartYToDrawableRange(Float.NaN, chartHeight, edgeInset), 0.001f)
    }

    @Test
    fun previewCenterTimeForWindowEndAnchorsPreviewAtRightEdge() {
        val windowEnd = 1_000_000_000L

        assertEquals(windowEnd, previewCenterTimeForWindowEnd(windowEnd) + 12L * 60L * 60L * 1000L)
    }

    @Test
    fun previewCenterTimeContainingViewportKeepsViewportInsidePreviewBand() {
        val hour = 60L * 60L * 1000L
        val previewCenter = 12L * hour

        assertEquals(12L * hour, previewCenterTimeContainingViewport(previewCenter, 12L * hour, 6L * hour))
        assertEquals(14L * hour, previewCenterTimeContainingViewport(previewCenter, 23L * hour, 6L * hour))
        assertEquals(10L * hour, previewCenterTimeContainingViewport(previewCenter, 1L * hour, 6L * hour))
    }
}
