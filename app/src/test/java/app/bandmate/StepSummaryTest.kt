package app.bandmate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepSummaryTest {
    @Test fun message_showsTotalSplitAndGoal() {
        val (title, body) = StepSummary.message(StepTotals(band = 32, phone = 5455, merged = 5487), goal = 8000)
        assertEquals("5,487 steps today", title)
        assertEquals("4.1 km · 68% of 8,000 goal\nBand 32 · Phone 5,455", body)
    }

    @Test fun message_bandOnlyAndGoalReached() {
        val (_, body) = StepSummary.message(StepTotals(band = 9000, phone = 0, merged = 9000), goal = 8000)
        assertTrue(!body.contains("Phone"))
        assertTrue(body.endsWith("Goal reached!"))
    }
}
