package de.kettenblatt

import de.kettenblatt.data.Settings
import de.kettenblatt.data.SettingsCodec
import de.kettenblatt.data.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings mapping, tested through the codec so no Context is needed.
 *
 * The behaviour that matters here is what happens to *bad* input: a preferences
 * file must never be able to stop the app opening, or leave the rider stuck in a
 * state they cannot get out of on the road.
 */
class SettingsTest {

    private fun roundTrip(s: Settings): Settings {
        val encoded = SettingsCodec.encode(s)
        return SettingsCodec.decode { encoded[it] }
    }

    @Test
    fun `defaults survive a round trip`() {
        assertEquals(Settings(), roundTrip(Settings()))
    }

    @Test
    fun `every field round trips`() {
        val custom = Settings(
            units = Units.IMPERIAL,
            offRouteEnterM = 70.0,
            offRouteExitM = 30.0,
            autoDimEnabled = false,
            autoDimDelayMs = 45_000,
            autoDimWakeAheadM = 500.0,
            navigationZoom = 15.0,
            closeZoom = 19.0,
            keepScreenOn = false,
        )
        assertEquals(custom, roundTrip(custom))
    }

    @Test
    fun `nothing stored gives the tuned defaults`() {
        assertEquals(Settings(), SettingsCodec.decode { null })
    }

    @Test
    fun `unreadable values fall back per field`() {
        // A half-written or hand-edited file must not take the app down, and one
        // bad key must not discard the rest.
        val stored = mapOf(
            SettingsCodec.UNITS to "PARSECS",
            SettingsCodec.NAV_ZOOM to "not a number",
            SettingsCodec.AUTO_DIM to "perhaps",
            SettingsCodec.AUTO_DIM_DELAY to "20000",
        )
        val decoded = SettingsCodec.decode { stored[it] }

        assertEquals(Settings().units, decoded.units)
        assertEquals(Settings().navigationZoom, decoded.navigationZoom, 0.0)
        assertEquals(Settings().autoDimEnabled, decoded.autoDimEnabled)
        // The one readable value is still honoured.
        assertEquals(20_000L, decoded.autoDimDelayMs)
    }

    @Test
    fun `an inverted off-route pair is refused`() {
        // Clearing further out than it alerts would latch the alarm on with no
        // way back, which on the road is worse than ignoring the setting.
        val stored = mapOf(
            SettingsCodec.OFF_ROUTE_ENTER to "20.0",
            SettingsCodec.OFF_ROUTE_EXIT to "80.0",
        )
        val decoded = SettingsCodec.decode { stored[it] }

        assertTrue(decoded.isHysteresisSane)
        assertEquals(Settings().offRouteEnterM, decoded.offRouteEnterM, 0.0)
        assertEquals(Settings().offRouteExitM, decoded.offRouteExitM, 0.0)
    }

    @Test
    fun `a sane custom hysteresis pair is kept`() {
        val stored = mapOf(
            SettingsCodec.OFF_ROUTE_ENTER to "100.0",
            SettingsCodec.OFF_ROUTE_EXIT to "60.0",
        )
        val decoded = SettingsCodec.decode { stored[it] }

        assertEquals(100.0, decoded.offRouteEnterM, 0.0)
        assertEquals(60.0, decoded.offRouteExitM, 0.0)
    }
}
