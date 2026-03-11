package com.happyhealth.bleplatform.api

enum class HpyConnectionState {
    IDLE,
    CONNECTING,
    HANDSHAKING,
    READY,
    CONNECTED_LIMITED,
    DOWNLOADING,
    WAITING,
    THROUGHPUT_TESTING,
    FW_UPDATING,
    FW_UPDATE_REBOOTING,
    RECONNECTING,
    DISCONNECTED,
}
