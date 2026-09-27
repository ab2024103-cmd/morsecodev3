package app.morsecode.android

import app.morsecode.android.core.util.Accent
import org.junit.Assert.assertEquals
import org.junit.Test

/** §4.4: exactly five swatches, in this order, the first is the default. */
class AccentTest {

    @Test
    fun exactlyFiveSwatchesInTheSpecifiedOrder() {
        val keys = Accent.values().map { it.key }
        assertEquals(listOf("sunflower", "leaf", "ember", "violet", "sky"), keys)
    }

    @Test
    fun sunflowerIsTheDefaultAndUnknownKeysFallBackToIt() {
        assertEquals(Accent.SUNFLOWER, Accent.DEFAULT)
        assertEquals(Accent.SUNFLOWER, Accent.fromKey(null))
        assertEquals(Accent.SUNFLOWER, Accent.fromKey("teal"))
        assertEquals(Accent.SKY, Accent.fromKey("sky"))
    }
}
