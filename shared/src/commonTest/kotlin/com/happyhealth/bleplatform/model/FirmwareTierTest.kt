package com.happyhealth.bleplatform.model

import com.happyhealth.bleplatform.internal.model.FirmwareTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirmwareTierTest {

    @Test
    fun bootloader_isTier0() {
        assertEquals(FirmwareTier.TIER_0, FirmwareTier.fromVersionString("0.0.0.0"))
    }

    @Test
    fun manufacturingFw_isTier0() {
        assertEquals(FirmwareTier.TIER_0, FirmwareTier.fromVersionString("1.5.0.1"))
        assertEquals(FirmwareTier.TIER_0, FirmwareTier.fromVersionString("1.5.2.3"))
    }

    @Test
    fun legacyAppFw_isTier1() {
        assertEquals(FirmwareTier.TIER_1, FirmwareTier.fromVersionString("2.4.0.0"))
        assertEquals(FirmwareTier.TIER_1, FirmwareTier.fromVersionString("2.4.1.5"))
    }

    @Test
    fun currentAppFw_isTier2() {
        assertEquals(FirmwareTier.TIER_2, FirmwareTier.fromVersionString("2.5.0.54"))
        assertEquals(FirmwareTier.TIER_2, FirmwareTier.fromVersionString("2.5.1.0"))
        assertEquals(FirmwareTier.TIER_2, FirmwareTier.fromVersionString("2.5.2.1"))
    }

    @Test
    fun futureFw_isTier2() {
        assertEquals(FirmwareTier.TIER_2, FirmwareTier.fromVersionString("3.0.0.0"))
    }

    @Test
    fun emptyString_isTier0() {
        assertEquals(FirmwareTier.TIER_0, FirmwareTier.fromVersionString(""))
    }

    @Test
    fun l2capDownload_supported() {
        assertTrue(FirmwareTier.supportsL2capDownload("2.5.0.54"))
        assertTrue(FirmwareTier.supportsL2capDownload("2.5.0.99"))
        assertTrue(FirmwareTier.supportsL2capDownload("2.5.1.0"))
        assertTrue(FirmwareTier.supportsL2capDownload("2.5.2.0"))
        assertTrue(FirmwareTier.supportsL2capDownload("2.5.2.1"))
    }

    @Test
    fun l2capDownload_notSupported() {
        assertFalse(FirmwareTier.supportsL2capDownload("2.5.0.53"))
        assertFalse(FirmwareTier.supportsL2capDownload("2.5.0.0"))
        assertFalse(FirmwareTier.supportsL2capDownload("2.4.0.0"))
        assertFalse(FirmwareTier.supportsL2capDownload("0.0.0.0"))
        assertFalse(FirmwareTier.supportsL2capDownload(""))
    }
}
