package com.happyhealth.bleplatform.api

enum class HpyConnectionState {
    IDLE,
    CONNECTING,
    HANDSHAKING,
    READY,
    CONNECTED_LIMITED,
    DOWNLOADING,
    WAITING,
    FW_UPDATING,
    FW_UPDATE_REBOOTING,
    RECONNECTING,
    DISCONNECTED,
}
