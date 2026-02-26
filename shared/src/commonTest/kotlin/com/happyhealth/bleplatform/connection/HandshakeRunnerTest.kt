package com.happyhealth.bleplatform.connection

import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.connection.ConnectionConfig
import com.happyhealth.bleplatform.internal.connection.HandshakeRunner
import com.happyhealth.bleplatform.internal.model.DeviceStatusData
import com.happyhealth.bleplatform.internal.model.FirmwareTier
import com.happyhealth.bleplatform.internal.shim.PlatformTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandshakeRunnerTest {

    private val mockTimeSource = object : PlatformTimeSource {
        override fun getUtcTimeSeconds(): Long = 1708000000L
        override fun getGmtOffsetHours(): Int = -6
    }

    private fun makeStatus(sendUtcFlags: Int = 0xFF): DeviceStatusData {
        return DeviceStatusData(
            phyStatus = 0x01, chargerState = 0, chargingState = 0, chargingMode = 0,
            chargerBlockedReason = 0, chargerRevId = 0, chargerStatus = 0,
            batteryVoltage = 4000, soc = 75, daqMode = 0x03,
            unsyncedFrames = 5, syncFrameCount = 100u, syncFrameReboots = 5u,
            opportunisticSamplingState = 0, opportunisticStateTime = 0,
            shipModeStatus = 0, sleepState = 0, sendUtcFlags = sendUtcFlags,
            bootHandshakeFlag = 0, pseudoRingOnOff = 0, notifSender = 0,
            bleCi = 0, clockRate = 0,
        )
    }

    @Test
    fun tier0_completesImmediately() {
        val runner = HandshakeRunner(FirmwareTier.TIER_0, ConnectionConfig(), mockTimeSource)
        val first = runner.start()
        assertNull(first)
        assertTrue(runner.isComplete)
    }

    @Test
    fun tier1_startsWithGetDeviceStatus() {
        val runner = HandshakeRunner(FirmwareTier.TIER_1, ConnectionConfig(), mockTimeSource)
        val first = runner.start()
        assertNotNull(first)
        assertEquals("HS_GET_DEV_STATUS", first.tag)
        assertEquals(CommandId.GET_DEVICE_STATUS, first.data[0])
    }

    @Test
    fun tier2_startsWithGetDaqConfig() {
        val runner = HandshakeRunner(FirmwareTier.TIER_2, ConnectionConfig(), mockTimeSource)
        val first = runner.start()
        assertNotNull(first)
        assertEquals("HS_GET_DAQ_CONFIG", first.tag)
        assertEquals(CommandId.GET_DAQ_CONFIG, first.data[0])
    }

    @Test
    fun tier2_fullHandshake_withConditionals() {
        val runner = HandshakeRunner(FirmwareTier.TIER_2, ConnectionConfig(), mockTimeSource)

        // Step 1: GET_DAQ_CONFIG
        val cmd1 = runner.start()
        assertNotNull(cmd1)
        assertEquals("HS_GET_DAQ_CONFIG", cmd1.tag)

        // Step 2: GET_DEVICE_STATUS (after DAQ config response)
        val cmd2 = runner.onCommandComplete()
        assertNotNull(cmd2)
        assertEquals("HS_GET_DEV_STATUS", cmd2.tag)

        // Step 3: DeviceStatus received with all flags set (sendUtcFlags=0x03, needsFingerDet)
        val status = makeStatus(sendUtcFlags = 0x03) // needsSetUtc=true, needsSetInfo=true, needsFingerDet=true
        val cmd3 = runner.onDeviceStatusReceived(status)
        assertNotNull(cmd3)
        assertEquals("HS_SET_UTC", cmd3.tag)

        // Step 4: SET_INFO
        val cmd4 = runner.onCommandComplete()
        assertNotNull(cmd4)
        assertEquals("HS_SET_INFO", cmd4.tag)

        // Step 5: SET_FINGER_DETECTION
        val cmd5 = runner.onCommandComplete()
        assertNotNull(cmd5)
        assertEquals("HS_SET_FINGER_DET", cmd5.tag)

        // Done
        val cmd6 = runner.onCommandComplete()
        assertNull(cmd6)
        assertTrue(runner.isComplete)
    }

    @Test
    fun tier1_noSetInfo() {
        val runner = HandshakeRunner(FirmwareTier.TIER_1, ConnectionConfig(), mockTimeSource)

        // Step 1: GET_DEVICE_STATUS
        val cmd1 = runner.start()
        assertNotNull(cmd1)
        assertEquals("HS_GET_DEV_STATUS", cmd1.tag)

        // SendUtcFlags: needs SET_UTC (bit 0) + SET_INFO (bit 1) + finger det
        val status = makeStatus(sendUtcFlags = 0x03)
        val cmd2 = runner.onDeviceStatusReceived(status)
        assertNotNull(cmd2)
        assertEquals("HS_SET_UTC", cmd2.tag)

        // No SET_INFO for Tier 1 — should go to SET_FINGER_DETECTION
        val cmd3 = runner.onCommandComplete()
        assertNotNull(cmd3)
        assertEquals("HS_SET_FINGER_DET", cmd3.tag)

        val cmd4 = runner.onCommandComplete()
        assertNull(cmd4)
        assertTrue(runner.isComplete)
    }

    @Test
    fun tier2_noConditionals_whenFlagsZero() {
        val runner = HandshakeRunner(FirmwareTier.TIER_2, ConnectionConfig(), mockTimeSource)

        val cmd1 = runner.start()!! // GET_DAQ_CONFIG
        val cmd2 = runner.onCommandComplete()!! // GET_DEVICE_STATUS

        // sendUtcFlags=0x04: bit2 set means needsFingerDet=false, bits 0&1 clear
        val status = makeStatus(sendUtcFlags = 0x04)
        val cmd3 = runner.onDeviceStatusReceived(status)
        assertNull(cmd3)
        assertTrue(runner.isComplete)
    }

    @Test
    fun skipFingerDetection_config() {
        val runner = HandshakeRunner(
            FirmwareTier.TIER_2,
            ConnectionConfig(skipFingerDetection = true),
            mockTimeSource,
        )

        runner.start() // GET_DAQ_CONFIG
        runner.onCommandComplete() // GET_DEVICE_STATUS

        // sendUtcFlags=0x03: needsSetUtc=true (bit0), needsSetInfo=true (bit1),
        // needsFingerDet=true (bit2=0) but skipFingerDetection=true
        val status = makeStatus(sendUtcFlags = 0x03)
        val cmd = runner.onDeviceStatusReceived(status)
        assertNotNull(cmd)
        assertEquals("HS_SET_UTC", cmd.tag)

        val cmd2 = runner.onCommandComplete()
        assertNotNull(cmd2)
        assertEquals("HS_SET_INFO", cmd2.tag)

        // Should NOT produce SET_FINGER_DETECTION because skipFingerDetection=true
        val cmd3 = runner.onCommandComplete()
        assertNull(cmd3)
        assertTrue(runner.isComplete)
    }
}
