package com.wledmusic.engine.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedLayoutTest {
    private fun LedLayout.isPermutation(): Boolean {
        val seen = BooleanArray(ledCount)
        for (y in 0 until height) for (x in 0 until width) {
            val i = index(x, y)
            if (i !in 0 until ledCount || seen[i]) return false
            seen[i] = true
        }
        return seen.all { it }
    }

    @Test
    fun serpentineMatrix() {
        val m = LedLayout.Matrix(16, 16, serpentine = true)
        assertEquals(0, m.index(0, 0))
        assertEquals(15, m.index(15, 0))
        assertEquals(31, m.index(0, 1))
        assertEquals(16, m.index(15, 1))
    }

    @Test
    fun horizontalFlip() {
        val m = LedLayout.Matrix(16, 16, serpentine = true, flipHorizontal = true)
        assertEquals(15, m.index(0, 0))
        assertEquals(0, m.index(15, 0))
    }

    @Test
    fun verticalFlip() {
        val m = LedLayout.Matrix(4, 3, flipVertical = true)
        assertEquals(8, m.index(0, 0))
        assertEquals(3, m.index(3, 2))
    }

    @Test
    fun rotationsSwapDimensionsAndStayBijective() {
        for (rotation in Rotation.values()) for (serp in listOf(false, true)) for (fh in listOf(false, true)) for (fv in listOf(false, true)) {
            val m = LedLayout.Matrix(5, 3, serp, rotation, fh, fv)
            assertTrue("$rotation serp=$serp fh=$fh fv=$fv", m.isPermutation())
        }
        val r90 = LedLayout.Matrix(5, 3, rotation = Rotation.R90)
        assertEquals(3, r90.width)
        assertEquals(5, r90.height)
    }

    @Test
    fun rotation90IsClockwise() {
        // Физическая панель 3×2: 0 1 2 / 3 4 5. Изображение повёрнуто по часовой на 90°:
        // логическая картинка 2×3: [3 0] / [4 1] / [5 2].
        val m = LedLayout.Matrix(3, 2, rotation = Rotation.R90)
        assertEquals(listOf(3, 0, 4, 1, 5, 2), (0 until 6).map { m.index(it % 2, it / 2) })
    }

    @Test
    fun rotation180ReversesOrder() {
        val m = LedLayout.Matrix(3, 2, rotation = Rotation.R180)
        assertEquals(listOf(5, 4, 3, 2, 1, 0), (0 until 6).map { m.index(it % 3, it / 3) })
    }

    @Test
    fun fourQuarterTurnsAreConsistent() {
        val r0 = LedLayout.Matrix(4, 4)
        val r270 = LedLayout.Matrix(4, 4, rotation = Rotation.R270)
        val r90 = LedLayout.Matrix(4, 4, rotation = Rotation.R90)
        // Верхний левый угол изображения при 90° — нижний левый LED панели; при 270° — верхний правый.
        assertEquals(12, r90.index(0, 0))
        assertEquals(3, r270.index(0, 0))
        assertEquals(0, r0.index(0, 0))
    }

    @Test
    fun stripAndReverse() {
        val s = LedLayout.Strip(60)
        assertFalse(s.is2D)
        assertEquals(60, s.width)
        assertEquals(1, s.height)
        assertEquals(59, LedLayout.Strip(60, reversed = true).index(0, 0))
    }

    @Test
    fun detectFromLampInfo() {
        assertEquals(LedLayout.Matrix(16, 16), LedLayout.detect(256, 16, 16))
        assertEquals(LedLayout.Strip(60), LedLayout.detect(60, null, null))
        assertNull(LedLayout.detect(2000, null, null))
        assertNull(LedLayout.detect(null, null, null))
        assertEquals(LedLayout.Strip(1024), LedLayout.detect(1024, 64, 64)) // матрица > лимита — не матрица
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyLedsRejected() {
        LedLayout.Matrix(33, 32)
    }
}
