package com.happyhealth.bleplatform.command

import com.happyhealth.bleplatform.internal.command.CommandBuilder
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.readUInt32
import com.happyhealth.bleplatform.internal.command.readUInt16
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandBuilderTest {

    @Test
    fun buildGetDeviceStatus_singleByte() {
        val cmd = CommandBuilder.buildGetDeviceStatus()
        assertEquals(1, cmd.size)
        assertEquals(CommandId.GET_DEVICE_STATUS, cmd[0])
    }

    @Test
    fun buildGetDaqConfig_singleByte() {
        val cmd = CommandBuilder.buildGetDaqConfig()
        assertEquals(1, cmd.size)
        assertEquals(CommandId.GET_DAQ_CONFIG, cmd[0])
    }

    @Test
    fun buildIdentify_singleByte() {
        val cmd = CommandBuilder.buildIdentify()
        assertEquals(1, cmd.size)
        assertEquals(CommandId.IDENTIFY, cmd[0])
    }

    @Test
    fun buildSetUtc_correctLayout() {
        val utc = 1708000000L
        val cmd = CommandBuilder.buildSetUtc(utc)
        assertEquals(13, cmd.size)
        assertEquals(CommandId.SET_UTC, cmd[0])
        assertEquals(utc.toUInt(), readUInt32(cmd, 1))
    }

    @Test
    fun buildSetInfo_checksumCorrect() {
        val cmd = CommandBuilder.buildSetInfo(-6)
        assertEquals(35, cmd.size)
        assertEquals(CommandId.SET_INFO, cmd[0])
        assertEquals((-6).toByte(), cmd[1])
        assertEquals(0xA0.toByte(), cmd[2])

        // Verify checksum
        var expected = 0x051B
        for (i in 1..32) expected += cmd[i].toUByte().toInt()
        val actual = (cmd[34].toUByte().toInt() shl 8) or cmd[33].toUByte().toInt()
        assertEquals(expected, actual)
    }

    @Test
    fun buildSetFingerDetection_twoBytes() {
        val cmd = CommandBuilder.buildSetFingerDetection()
        assertEquals(2, cmd.size)
        assertEquals(CommandId.SET_FINGER_DETECTION, cmd[0])
        assertEquals(0x01.toByte(), cmd[1])
    }

    @Test
    fun buildStartDaq_fiveBytes() {
        val cmd = CommandBuilder.buildStartDaq()
        assertEquals(5, cmd.size)
        assertEquals(CommandId.START_DAQ, cmd[0])
    }

    @Test
    fun buildStopDaq_fiveBytes() {
        val cmd = CommandBuilder.buildStopDaq()
        assertEquals(5, cmd.size)
        assertEquals(CommandId.STOP_DAQ, cmd[0])
    }

    @Test
    fun buildConfigureL2cap_listen() {
        val cmd = CommandBuilder.buildConfigureL2cap(listen = true, turbo48 = true)
        assertEquals(3, cmd.size)
        assertEquals(CommandId.CONFIGURE_L2CAP, cmd[0])
        assertEquals(CommandId.L2CAP_ACTION_LISTEN, cmd[1])
        assertEquals(CommandId.L2CAP_TURBO_48MHZ, cmd[2])
    }

    @Test
    fun buildConfigureL2cap_close() {
        val cmd = CommandBuilder.buildConfigureL2cap(listen = false, turbo48 = false)
        assertEquals(3, cmd.size)
        assertEquals(CommandId.CONFIGURE_L2CAP, cmd[0])
        assertEquals(CommandId.L2CAP_ACTION_CLOSE, cmd[1])
        assertEquals(CommandId.L2CAP_TURBO_16MHZ, cmd[2])
    }

    @Test
    fun buildGetFramesL2cap_correctLayout() {
        val cmd = CommandBuilder.buildGetFramesL2cap(
            syncFrameCount = 100u,
            syncFrameReboots = 5u,
            limit = 64,
        )
        assertEquals(23, cmd.size)
        assertEquals(CommandId.GET_FRAMES_L2CAP, cmd[0])
        assertEquals(100u, readUInt32(cmd, 1))
        assertEquals(5u, readUInt32(cmd, 5))
        assertEquals(0u, readUInt32(cmd, 9))  // To.frameCount
        assertEquals(0u, readUInt32(cmd, 13)) // To.reboots
        assertEquals(64u, readUInt32(cmd, 17))
        // Flags: 0x0004
        assertEquals(0x04.toByte(), cmd[21])
        assertEquals(0x00.toByte(), cmd[22])
    }

    @Test
    fun buildGetFileLength_memfault() {
        val cmd = CommandBuilder.buildGetFileLength(2u)
        assertEquals(3, cmd.size)
        assertEquals(CommandId.GET_FILE_LENGTH, cmd[0])
        assertEquals(0x02.toByte(), cmd[1])
        assertEquals(0x00.toByte(), cmd[2])
    }
}
