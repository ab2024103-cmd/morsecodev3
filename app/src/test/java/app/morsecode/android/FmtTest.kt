package app.morsecode.android

import app.morsecode.android.core.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §21.1 — Fmt size/speed/duration rendering. The expected strings are the
 * mocks' own copy (§6.4, §6.9, §6.11): a drift here changes what every meta
 * line in the product says.
 */
class FmtTest {

    private val kb = 1024L
    private val mb = kb * 1024
    private val gb = mb * 1024

    @Test
    fun sizeRendersOneDecimalAndDropsTrailingZero() {
        assertEquals("144 MB", Fmt.size(144 * mb))
        assertEquals("4.1 MB", Fmt.size((4.1 * mb).toLong()))
        assertEquals("357.1 KB", Fmt.size((357.1 * kb).toLong()))
        assertEquals("64 GB", Fmt.size(64 * gb))
        assertEquals("0 B", Fmt.size(0))
        assertEquals("512 B", Fmt.size(512))
    }

    @Test
    fun progressPairSharesTheLargerUnitAndPrintsItOnce() {
        assertEquals("48.9 / 144 MB", Fmt.progress((48.9 * mb).toLong(), 144 * mb))
        assertEquals("39.7 / 64 MB", Fmt.progress((39.7 * mb).toLong(), 64 * mb))
        assertEquals("13.1 / 18.2 MB", Fmt.progress((13.1 * mb).toLong(), (18.2 * mb).toLong()))
        assertEquals("0 / 144 MB", Fmt.progress(0, 144 * mb))
    }

    @Test
    fun speedMatchesTheMockMetaLines() {
        assertEquals("6.2 MB/s", Fmt.speed((6.2 * mb).toLong()))
        assertEquals("8.4 MB/s", Fmt.speed((8.4 * mb).toLong()))
        assertEquals("14.2 MB/s", Fmt.speed((14.2 * mb).toLong()))
        assertEquals("0 B/s", Fmt.speed(0))
    }

    @Test
    fun durationUsesClockFormAndGrowsToHours() {
        assertEquals("3:42", Fmt.duration(222_000))
        assertEquals("24:12", Fmt.duration(1_452_000))
        assertEquals("1:02:03", Fmt.duration(3_723_000))
        assertEquals("0:00", Fmt.duration(0))
        assertEquals("0:00", Fmt.duration(-5_000))
    }

    @Test
    fun percentIsClampedBothWays() {
        assertEquals("0%", Fmt.percent(0, 100))
        assertEquals("34%", Fmt.percent(34, 100))
        assertEquals("100%", Fmt.percent(100, 100))
        assertEquals("100%", Fmt.percent(120, 100))
        assertEquals("0%", Fmt.percent(10, 0))
    }

    @Test
    fun spokenDurationIsAnnounceable() {
        // §6.11: "2 minutes 43 seconds of 4 minutes 8 seconds"
        assertEquals("2 minutes 43 seconds", Fmt.spokenDuration(163_000))
        assertEquals("4 minutes 8 seconds", Fmt.spokenDuration(248_000))
        assertEquals("1 minute 1 second", Fmt.spokenDuration(61_000))
    }
}
