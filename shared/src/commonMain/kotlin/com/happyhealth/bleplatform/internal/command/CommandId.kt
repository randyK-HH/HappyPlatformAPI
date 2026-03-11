package com.happyhealth.bleplatform.internal.command

object CommandId {
    const val GET_FILE_LENGTH: Byte = 0x00
    const val READ_FILE: Byte = 0x01
    const val START_DAQ: Byte = 0x04
    const val STOP_DAQ: Byte = 0x05
    const val SET_UTC: Byte = 0x0B
    const val GET_FRAMES: Byte = 0x17
    const val GET_DEVICE_STATUS: Byte = 0x1E.toByte()
    const val SET_DAQ_CONFIG: Byte = 0x2A
    const val GET_DAQ_CONFIG: Byte = 0x2B
    const val SET_FINGER_DETECTION: Byte = 0x2D
    const val ENABLE_SHIP_MODE: Byte = 0x30
    const val SET_INFO: Byte = 0x32
    const val IDENTIFY: Byte = 0x33
    const val CONFIGURE_L2CAP: Byte = 0x34
    const val GET_FRAMES_L2CAP: Byte = 0x35
    const val SET_CONNECTION_PARAMS: Byte = 0x37
    const val GET_EXTENDED_DEVICE_STATUS: Byte = 0x38
    const val SET_SYNC_FRAME: Byte = 0x19
    const val GET_SYNC_FRAME: Byte = 0x1A
    const val GET_FGSN: Byte = 0x22
    const val L2CAP_THROUGHPUT_TEST: Byte = 0x64
    const val ASSERT: Byte = 0xFD.toByte()

    const val UNRECOGNIZED_RESPONSE: Byte = 0xFF.toByte()

    const val L2CAP_ACTION_LISTEN: Byte = 0x10
    const val L2CAP_ACTION_CLOSE: Byte = 0x20
    const val L2CAP_TURBO_16MHZ: Byte = 0x00
    const val L2CAP_TURBO_48MHZ: Byte = 0x01
    const val L2CAP_TURBO_96MHZ: Byte = 0x02

    const val FLAGS_AUTO_SYNC_FRAME: UInt = 0x0004u

    const val NOTIF_SENDER_SUPERFRAME_CLOSE: Int = 0x04

    const val FRAME_SIZE: Int = 4096
    const val THROUGHPUT_PACKET_SIZE: Int = 245
}
