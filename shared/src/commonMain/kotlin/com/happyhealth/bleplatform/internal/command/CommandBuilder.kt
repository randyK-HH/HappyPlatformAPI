package com.happyhealth.bleplatform.internal.command

object CommandBuilder {

    fun buildGetDeviceStatus(): ByteArray =
        byteArrayOf(CommandId.GET_DEVICE_STATUS)

    fun buildGetExtendedDeviceStatus(): ByteArray =
        byteArrayOf(CommandId.GET_EXTENDED_DEVICE_STATUS)

    fun buildGetDaqConfig(): ByteArray =
        byteArrayOf(CommandId.GET_DAQ_CONFIG)

    fun buildStartDaq(): ByteArray =
        byteArrayOf(CommandId.START_DAQ, 0x00, 0x00, 0x00, 0x00)

    fun buildStopDaq(): ByteArray =
        byteArrayOf(CommandId.STOP_DAQ, 0x00, 0x00, 0x00, 0x00)

    fun buildIdentify(): ByteArray =
        byteArrayOf(CommandId.IDENTIFY)

    fun buildSetUtc(utcSeconds: Long): ByteArray {
        val cmd = ByteArray(13)
        cmd[0] = CommandId.SET_UTC
        writeUInt32(cmd, 1, utcSeconds.toUInt())
        return cmd
    }

    fun buildSetInfo(gmtOffsetHours: Int): ByteArray {
        val cmd = ByteArray(35)
        cmd[0] = CommandId.SET_INFO
        cmd[1] = gmtOffsetHours.toByte()       // GMT offset (signed)
        cmd[2] = 0xA0.toByte()                 // App type
        // cmd[3..6] = app version (0x00000000, already zero)
        // cmd[7..32] = reserved (already zero)

        // Checksum: seed 0x051B + sum of data bytes [1..32], stored LE at [33..34]
        var checksum = 0x051B
        for (i in 1..32) checksum += cmd[i].toUByte().toInt()
        cmd[33] = (checksum and 0xFF).toByte()
        cmd[34] = ((checksum shr 8) and 0xFF).toByte()
        return cmd
    }

    fun buildSetFingerDetection(enable: Boolean = true): ByteArray =
        byteArrayOf(CommandId.SET_FINGER_DETECTION, if (enable) 0x01 else 0x00)

    fun buildConfigureL2cap(listen: Boolean, turbo48: Boolean): ByteArray {
        return byteArrayOf(
            CommandId.CONFIGURE_L2CAP,
            if (listen) CommandId.L2CAP_ACTION_LISTEN else CommandId.L2CAP_ACTION_CLOSE,
            if (turbo48) CommandId.L2CAP_TURBO_48MHZ else CommandId.L2CAP_TURBO_16MHZ,
        )
    }

    fun buildGetFramesL2cap(
        syncFrameCount: UInt,
        syncFrameReboots: UInt,
        limit: Int,
    ): ByteArray {
        val cmd = ByteArray(23)
        cmd[0] = CommandId.GET_FRAMES_L2CAP
        writeUInt32(cmd, 1, syncFrameCount)
        writeUInt32(cmd, 5, syncFrameReboots)
        writeUInt32(cmd, 17, limit.toUInt())
        cmd[21] = (CommandId.FLAGS_AUTO_SYNC_FRAME and 0xFFu).toByte()
        cmd[22] = (CommandId.FLAGS_AUTO_SYNC_FRAME shr 8).toByte()
        return cmd
    }

    fun buildGetFramesGatt(
        syncFrameCount: UInt,
        syncFrameReboots: UInt,
        limit: Int,
    ): ByteArray {
        val cmd = ByteArray(23)
        cmd[0] = CommandId.GET_FRAMES
        writeUInt32(cmd, 1, syncFrameCount)
        writeUInt32(cmd, 5, syncFrameReboots)
        writeUInt32(cmd, 17, limit.toUInt())
        cmd[21] = (CommandId.FLAGS_AUTO_SYNC_FRAME and 0xFFu).toByte()
        cmd[22] = (CommandId.FLAGS_AUTO_SYNC_FRAME shr 8).toByte()
        return cmd
    }

    fun buildGetFileLength(fileId: UShort): ByteArray {
        val cmd = ByteArray(3)
        cmd[0] = CommandId.GET_FILE_LENGTH
        writeUInt16(cmd, 1, fileId)
        return cmd
    }

    fun buildReadFile(fileId: UShort, offset: UInt, length: UInt): ByteArray {
        val cmd = ByteArray(11)
        cmd[0] = CommandId.READ_FILE
        writeUInt16(cmd, 1, fileId)
        writeUInt32(cmd, 3, offset)
        writeUInt32(cmd, 7, length)
        return cmd
    }

    fun buildL2capThroughputTest(numPackets: Int): ByteArray {
        val cmd = ByteArray(3)
        cmd[0] = CommandId.L2CAP_THROUGHPUT_TEST
        writeUInt16(cmd, 1, numPackets.toUShort())
        return cmd
    }
}
