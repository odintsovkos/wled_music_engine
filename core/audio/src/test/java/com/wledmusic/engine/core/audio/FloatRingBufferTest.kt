package com.wledmusic.engine.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class FloatRingBufferTest {
    @Test
    fun readsWhatWasWrittenAcrossWrap() {
        val rb = FloatRingBuffer(8)
        val out = FloatArray(6)
        rb.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        assertTrue(rb.read(out, 0, 6))
        rb.write(floatArrayOf(7f, 8f, 9f, 10f, 11f))
        assertTrue(rb.read(out, 0, 5))
        assertArrayEquals(floatArrayOf(7f, 8f, 9f, 10f, 11f), out.copyOf(5), 0f)
    }

    @Test
    fun readFailsWhenNotEnoughData() {
        val rb = FloatRingBuffer(8)
        rb.write(floatArrayOf(1f, 2f))
        assertFalse(rb.read(FloatArray(3), 0, 3))
        assertEquals(2, rb.available())
    }

    @Test
    fun overflowDropsNewSamplesWithoutBlocking() {
        val rb = FloatRingBuffer(4)
        assertEquals(4, rb.write(FloatArray(6) { it.toFloat() }))
        assertEquals(2L, rb.dropped)
        val out = FloatArray(4)
        rb.read(out, 0, 4)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f), out, 0f)
    }

    @Test
    fun skipDiscardsBacklog() {
        val rb = FloatRingBuffer(16)
        rb.write(FloatArray(10) { it.toFloat() })
        rb.skip(8)
        val out = FloatArray(2)
        assertTrue(rb.read(out, 0, 2))
        assertArrayEquals(floatArrayOf(8f, 9f), out, 0f)
        rb.skip(100)
        assertEquals(0, rb.available())
    }

    @Test
    fun concurrentProducerConsumerKeepsOrder() {
        val rb = FloatRingBuffer(1024)
        val total = 200_000
        val producer = thread {
            var next = 0
            val chunk = FloatArray(100)
            while (next < total) {
                for (i in chunk.indices) chunk[i] = (next + i).toFloat()
                if (rb.available() <= rb.capacity - chunk.size) {
                    rb.write(chunk)
                    next += chunk.size
                } else {
                    Thread.yield()
                }
            }
        }
        var expected = 0
        val block = FloatArray(50)
        while (expected < total) {
            if (rb.read(block, 0, block.size)) {
                for (v in block) assertEquals(expected++.toFloat(), v, 0f)
            } else {
                Thread.yield()
            }
        }
        producer.join()
        assertEquals(0L, rb.dropped)
    }
}
