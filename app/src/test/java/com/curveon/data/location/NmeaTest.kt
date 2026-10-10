package com.curveon.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NmeaTest {
    @Test
    fun `sea level height from GGA`() {
        assertEquals(123.4, Nmea.ggaAltitude("\$GNGGA,123519.00,5947.6531,N,00508.6606,E,1,12,0.8,123.4,M,43.2,M,,*4F")!!, 0.01)
        assertEquals(-2.0, Nmea.ggaAltitude("\$GPGGA,123519,5947.65,N,00508.66,E,2,08,1.0,-2.0,M,43.2,M,,*47")!!, 0.01)
    }

    @Test
    fun `no height without a fix or from other sentences`() {
        assertNull(Nmea.ggaAltitude("\$GPGGA,123519,,,,,0,00,,,M,,M,,*66"))
        assertNull(Nmea.ggaAltitude("\$GPRMC,123519,A,5947.65,N,00508.66,E,0.0,0.0,101026,,,A*6C"))
        assertNull(Nmea.ggaAltitude("garbage"))
    }
}
