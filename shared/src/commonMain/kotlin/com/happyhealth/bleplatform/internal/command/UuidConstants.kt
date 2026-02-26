package com.happyhealth.bleplatform.internal.command

object UuidConstants {
    // HCS Service
    const val UUID_HPY_HCS = "ff899c90-18ad-11eb-adc1-0242ac120002"
    const val UUID_HPY_CMD_RX = "ff899f60-18ad-11eb-adc1-0242ac120002"
    const val UUID_HPY_CMD_TX = "ff89a262-18ad-11eb-adc1-0242ac120002"
    const val UUID_HPY_STREAM_TX = "ff89a366-18ad-11eb-adc1-0242ac120002"
    const val UUID_HPY_DEBUG_TX = "ff89a438-18ad-11eb-adc1-0242ac120002"
    const val UUID_HPY_FRAME_TX = "ff89a500-18ad-11eb-adc1-0242ac120002"

    // Device Information Service
    const val UUID_SERVICE_DEVICE_INFO = "0000180a-0000-1000-8000-00805f9b34fb"
    const val UUID_CHAR_MODEL_NUMBER = "00002a24-0000-1000-8000-00805f9b34fb"
    const val UUID_CHAR_SERIAL_NUMBER = "00002a25-0000-1000-8000-00805f9b34fb"
    const val UUID_CHAR_FW_VERSION = "00002a26-0000-1000-8000-00805f9b34fb"
    const val UUID_CHAR_SW_VERSION = "00002a28-0000-1000-8000-00805f9b34fb"
    const val UUID_CHAR_MANUFACTURER_NAME = "00002a29-0000-1000-8000-00805f9b34fb"

    // Battery Service
    const val UUID_SERVICE_BATTERY = "0000180f-0000-1000-8000-00805f9b34fb"
    const val UUID_CHAR_BAT_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"

    // SUOTA Service
    const val UUID_SUOTA_SERVICE = "d20697cb-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_MEM_DEV = "d20697cc-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_GPIO_MAP = "d20697cd-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_MEM_INFO = "d20697ce-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_PATCH_LEN = "d20697cf-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_PATCH_DATA = "d20697d0-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_STATUS = "d20697d1-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_L2CAP_PSM = "d20697d2-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_VERSION = "d20697d3-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_MTU = "d20697d4-fab2-41f9-82c3-d36af65fbb26"
    const val UUID_SUOTA_PATCH_DATA_CHAR_SIZE = "d20697d5-fab2-41f9-82c3-d36af65fbb26"

    // CCC Descriptor
    const val UUID_CCC = "00002902-0000-1000-8000-00805f9b34fb"

    // L2CAP PSMs
    const val L2CAP_PSM = 130
    const val L2CAP_SUOTA_PSM = 129
}
