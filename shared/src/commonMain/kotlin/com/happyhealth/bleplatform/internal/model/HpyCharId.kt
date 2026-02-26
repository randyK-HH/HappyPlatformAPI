package com.happyhealth.bleplatform.internal.model

enum class HpyCharId {
    // HCS Service characteristics
    CMD_RX,         // Write Without Response — App -> Ring commands
    CMD_TX,         // Notify — Ring -> App command responses
    STREAM_TX,      // Notify — Ring -> App stream data (Memfault, file reads)
    DEBUG_TX,       // Notify — Ring -> App debug log output
    FRAME_TX,       // Notify — Ring -> App GATT frame data (HPY2 download)

    // Device Information Service characteristics
    DIS_SERIAL_NUMBER,
    DIS_FW_VERSION,
    DIS_SW_VERSION,
    DIS_MANUFACTURER_NAME,
    DIS_MODEL_NUMBER,

    // SUOTA Service characteristics
    SUOTA_MEM_DEV,
    SUOTA_GPIO_MAP,
    SUOTA_MEM_INFO,
    SUOTA_PATCH_LEN,
    SUOTA_PATCH_DATA,
    SUOTA_STATUS,
    SUOTA_L2CAP_PSM,
    SUOTA_VERSION,
    SUOTA_MTU,
    SUOTA_PATCH_DATA_CHAR_SIZE,
}
