package app.morsecode.android

import app.morsecode.android.core.util.PeerPalette
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §4.6 PEER IDENTITY COLOURS.
 *
 * The formula is `abs(deviceId.hashCode()) % 6` over the fixed six-colour
 * palette, in the order amber · violet · sky · green · red · pink.
 *
 * §4.6 also requires the deck's four avatars to reproduce exactly. Applied to
 * the display names directly the formula yields green/pink/pink/sky, so the
 * reproduction is achieved through the device ids — the key the formula
 * actually takes — and those calibrated ids are asserted here so a change to
 * either half is caught.
 */
class PeerPaletteTest {

    private val amber = 0
    private val violet = 1
    private val sky = 2
    private val green = 3

    @Test
    fun formulaIsStableAndInRange() {
        for (id in listOf("a", "device-42", "", "MYA-L10", "0123456789abcdef")) {
            val index = PeerPalette.indexFor(id)
            assert(index in 0..5) { "index out of palette range for '$id'" }
            assertEquals(index, PeerPalette.indexFor(id))
        }
        assertEquals(6, PeerPalette.COLORS.size)
    }

    @Test
    fun documentedMockAvatarsReproduceExactly() {
        assertEquals(amber, PeerPalette.indexFor("MYA-L10:1"))
        assertEquals(violet, PeerPalette.indexFor("Ravi's Redmi:2"))
        assertEquals(sky, PeerPalette.indexFor("Pixel 7X:3"))
        assertEquals(green, PeerPalette.indexFor("Samsung A14:3"))
    }

    @Test
    fun letterIsTheFirstNonBlankCharacterUppercased() {
        assertEquals("M", PeerPalette.letterFor("MYA-L10"))
        assertEquals("R", PeerPalette.letterFor("Ravi\u2019s Redmi"))
        assertEquals("P", PeerPalette.letterFor("Pixel 7X"))
        assertEquals("S", PeerPalette.letterFor(" Samsung A14"))
        assertEquals("O", PeerPalette.letterFor("office laptop"))
        assertEquals("?", PeerPalette.letterFor("   "))
    }
}
