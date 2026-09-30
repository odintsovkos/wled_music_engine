package com.wledmusic.engine.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun paramsRoundTrip() {
        val p = EffectParams(10, 20, 30, 40, PaletteId.OCEAN, 50)
        assertEquals(p, SettingsCodec.decodeParams(SettingsCodec.encodeParams(p)))
        assertNull(SettingsCodec.decodeParams("1,2,3"))
        assertNull(SettingsCodec.decodeParams("1,2,300,4,OCEAN,5"))
        assertNull(SettingsCodec.decodeParams("1,2,3,4,NOPE,5"))
    }

    @Test
    fun layoutRoundTrip() {
        val layouts = listOf(
            LedLayout.Strip(60, reversed = true),
            LedLayout.Matrix(16, 8, serpentine = true, rotation = Rotation.R270, flipHorizontal = true, flipVertical = false),
        )
        for (l in layouts) assertEquals(l, SettingsCodec.decodeLayout(SettingsCodec.encodeLayout(l)))
        assertNull(SettingsCodec.decodeLayout("matrix:64:64:false:R0:false:false"))
        assertNull(SettingsCodec.decodeLayout("garbage"))
        assertNull(SettingsCodec.decodeLayout(null))
    }
}
