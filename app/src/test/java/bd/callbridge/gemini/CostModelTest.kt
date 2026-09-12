package bd.callbridge.gemini

import org.junit.Assert.assertEquals
import org.junit.Test

class CostModelTest {
    @Test
    fun `zero seconds costs nothing`() {
        assertEquals(0.0, CostModel.estimateUsd(0.0, 0.0), 1e-9)
    }

    @Test
    fun `one minute input only`() {
        assertEquals(0.005, CostModel.estimateUsd(60.0, 0.0), 1e-9)
    }

    @Test
    fun `one minute output only`() {
        assertEquals(0.018, CostModel.estimateUsd(0.0, 60.0), 1e-9)
    }

    @Test
    fun `three minute call, half input half output`() {
        // 90s in, 90s out -> 1.5 min each
        val expected = 1.5 * 0.005 + 1.5 * 0.018
        assertEquals(expected, CostModel.estimateUsd(90.0, 90.0), 1e-9)
    }
}
