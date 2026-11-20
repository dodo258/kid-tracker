package com.dodo258.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandTest {
    @Test
    fun mapsManufacturers() {
        assertEquals("samsung", Brand.of("samsung"))
        assertEquals("samsung", Brand.of("SAMSUNG"))
        assertEquals("huawei", Brand.of("HUAWEI"))
        assertEquals("xiaomi", Brand.of("Redmi"))
        assertEquals("oppo", Brand.of("OnePlus"))
        assertEquals("vivo", Brand.of("iQOO"))
        assertEquals("other", Brand.of("Google"))
    }

    @Test
    fun onlySamsungHidesFromRecents() {
        assertTrue(Brand.excludeFromRecents(Brand.of("samsung")))
        for (m in listOf("HUAWEI", "HONOR", "Xiaomi", "OPPO", "vivo", "Google")) {
            assertFalse(m, Brand.excludeFromRecents(Brand.of(m)))
        }
    }
}
