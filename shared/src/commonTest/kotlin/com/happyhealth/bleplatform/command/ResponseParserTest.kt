package com.happyhealth.bleplatform.command

import com.happyhealth.bleplatform.internal.command.ResponseParser
import com.happyhealth.bleplatform.internal.command.writeUInt32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseParserTest {

    @Test
    fun parseDeviceStatus_validPayload() {
        val data = ByteArray(34)
        data[0] = 0x1E  // command echo
        data[1] = 0x01  // phyStatus = OnFinger
        data[10] = 75   // SOC = 75%
        data[11] = 0x03 // DAQ On
        data[12] = 5    // unsyncedFrames lo
        data[13] = 0    // unsyncedFrames hi
        writeUInt32(data, 14, 100u) // syncFrameCount
        writeUInt32(data, 18, 5u)   // syncFrameReboots
        data[27] = 0x03 // sendUtcFlags: needsSetUtc + needsSetInfo
        data[30] = 0x04 // notifSender = SuperframeClose

        val status = ResponseParser.parseDeviceStatus(data)
        assertNotNull(status)
        assertEquals(0x01, status.phyStatus)
        assertEquals("OnFinger", status.phyString)
        assertEquals(75, status.soc)
        assertEquals(0x03, status.daqMode)
        assertEquals("On", status.daqString)
        assertEquals(5, status.unsyncedFrames)
        assertEquals(100u, status.syncFrameCount)
        assertEquals(5u, status.syncFrameReboots)
        assertTrue(status.needsSetUtc)
        assertTrue(status.needsSetInfo)
        assertEquals(0x04, status.notifSender)
        assertEquals("SuperframeClose", status.notifSenderString)
    }

    @Test
    fun parseDeviceStatus_tooShort_returnsNull() {
        val data = ByteArray(10)
        assertNull(ResponseParser.parseDeviceStatus(data))
    }

    @Test
    fun parseSetUtcResponse_valid() {
        val data = ByteArray(13)
        data[0] = 0x0B
        writeUInt32(data, 1, 1708000000u)
        writeUInt32(data, 9, 5u)

        val resp = ResponseParser.parseSetUtcResponse(data)
        assertNotNull(resp)
        assertEquals(1708000000u, resp.ringUtc)
        assertEquals(5u, resp.ringReboots)
    }

    @Test
    fun parseSetUtcResponse_tooShort_returnsNull() {
        assertNull(ResponseParser.parseSetUtcResponse(ByteArray(5)))
    }

    @Test
    fun parseConfigureL2capResponse_valid() {
        val data = byteArrayOf(0x34, 0x00, 0x01)
        val resp = ResponseParser.parseConfigureL2capResponse(data)
        assertNotNull(resp)
        assertEquals(0, resp.status)
        assertTrue(resp.turboAccepted)
    }

    @Test
    fun parseDaqConfig_valid() {
        val data = ByteArray(65)
        data[0] = 0x2B // command echo
        data[1] = 1    // version
        data[2] = 5    // mode = SPO2_100

        val config = ResponseParser.parseDaqConfig(data)
        assertNotNull(config)
        assertEquals(1, config.version)
        assertEquals(5, config.mode)
        assertEquals("SPO2_100", config.modeString)
    }

    @Test
    fun parseDaqConfig_tooShort_returnsNull() {
        assertNull(ResponseParser.parseDaqConfig(ByteArray(2)))
    }

    @Test
    fun parseGetFileLengthResponse_valid() {
        val data = ByteArray(5)
        data[0] = 0x00
        writeUInt32(data, 1, 1200u)
        assertEquals(1200u, ResponseParser.parseGetFileLengthResponse(data))
    }

    @Test
    fun parseExtendedDeviceStatus_valid() {
        val data = byteArrayOf(0x38, 0x02, 0x2C.toByte(), 0x01)
        val resp = ResponseParser.parseExtendedDeviceStatus(data)
        assertNotNull(resp)
        assertEquals(2, resp.bpState) // On
        assertEquals(300, resp.bpTimeLeftSec) // 0x012C = 300
    }
}
