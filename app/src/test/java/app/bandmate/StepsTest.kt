package app.bandmate

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StepsTest {
    @Test fun merge_takesHigherCountPerHour_neverAdds() {
        val band = intArrayOf(0, 500, 1200, 0)
        val phone = intArrayOf(300, 450, 1300, 0)
        assertArrayEquals(intArrayOf(300, 500, 1300, 0), Steps.merge(band, phone))
    }

    @Test fun merge_handlesDifferentLengths() {
        assertArrayEquals(intArrayOf(5, 7), Steps.merge(intArrayOf(5), intArrayOf(0, 7)))
    }
}
